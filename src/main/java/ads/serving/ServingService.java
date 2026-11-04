package ads.serving;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;

import ads.common.ImpressionTokens;

/**
 * 서빙 경로 전체(① 요청 → ⑥ 응답)를 순서대로 엮습니다.
 *
 * 지키는 규칙
 * - 순서: 후보 추리기(CandidateFilter) → 예측(Prediction) → 랭킹, 옥션(Auction) → 블렌딩(Blender) → 노출 토큰 발급.
 * - 광고 고르기(①~⑤)는 timeout-ms(50ms) 안에 끝나야 합니다. 넘기거나 오류가 나면 광고 없이 일반 상품만 응답합니다.
 * - 연속으로 실패하면 서킷 브레이커가 열려 한동안 광고 고르기를 건너뜁니다.
 * - 광고 자리를 몇 개 요청받아 몇 개 채웠는지 세어 채움률(fill rate)을 냅니다.
 *
 * 왜 이 순서와 방식인지
 * - 페이지는 광고 없이도 완성되어야 하고, 광고 때문에 느려지면 안 됩니다(CLAUDE.md 3-2 "서빙 경로는 빨라야 한다").
 *   그래서 일반 상품 목록은 시간 제한 밖에서 먼저 준비하고, 광고 고르기만 별도 스레드에서 시간 제한을 겁니다.
 * - 광고를 못 채운 자리는 곧 매출 손실이라 채움률로 숫자를 남깁니다. 시간 초과, 서킷 차단, 후보 없음을 나눠 셉니다.
 * - debugDelayMs는 "저장소가 느려진 상황"을 흉내 내는 장애 주입 손잡이입니다. 최대 max-debug-delay-ms로 제한합니다.
 *
 * 이 규칙이 깨지면
 * - 시간 제한이 없으면 Redis가 느려질 때 모든 페이지가 함께 느려집니다.
 * - 후보 거르기 전에 옥션을 하면 나갈 수 없는 광고가 2등 가격을 정해 낙찰가가 틀어집니다.
 */
@Service
public class ServingService {

    public enum Outcome { FILLED, NO_CANDIDATE, TIMEOUT, ERROR, CIRCUIT_OPEN }

    /** 경매표 한 줄. 응답에 그대로 보여줘 "왜 이 광고가 이겼나/졌나"를 읽을 수 있게 합니다. */
    public record AuctionRow(long adId, String brand, int bid, double basePctr, double pctr, double ecpm,
                             double platformWeight, double score, Integer rank, Integer price, String result) {
    }

    public record ServedItem(int position, String type, long productId, String name, String brand, int price,
                             Long adId, Integer cpc, String token) {
    }

    public record ServeResult(String slot, String category, String userId, boolean personalize, String recentBrand,
                              Outcome outcome, long elapsedMs, List<ServedItem> items, List<AuctionRow> auction) {
    }

    public record Stats(long requestedSlots, long filledSlots, double fillRate,
                        Map<Outcome, Long> outcomes, CircuitBreaker.State circuit) {
    }

    private record Selection(String recentBrand, List<Auction.Entry> entries, List<CandidateFilter.Rejected> rejected) {
    }

    private final Catalog catalog;
    private final UserSignals userSignals;
    private final ImpressionTokens tokens;
    private final ServingProperties props;
    private final CircuitBreaker breaker;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final AtomicLong requestedSlots = new AtomicLong();
    private final AtomicLong filledSlots = new AtomicLong();
    private final Map<Outcome, AtomicLong> outcomes = new EnumMap<>(Outcome.class);

    public ServingService(Catalog catalog, UserSignals userSignals, ImpressionTokens tokens, ServingProperties props) {
        this.catalog = catalog;
        this.userSignals = userSignals;
        this.tokens = tokens;
        this.props = props;
        this.breaker = new CircuitBreaker(props.circuitFailureThreshold(), props.circuitOpenMs());
        for (Outcome o : Outcome.values()) {
            outcomes.put(o, new AtomicLong());
        }
    }

    public ServeResult serve(String slot, String category, String userId, boolean personalize, int debugDelayMs) {
        long start = System.currentTimeMillis();
        Catalog.Snapshot snapshot = catalog.snapshot();
        List<Product> organic = snapshot.productsByCategory().getOrDefault(category, List.of());
        int adSlots = props.adPositions().size();
        requestedSlots.addAndGet(adSlots);

        if (!breaker.allowRequest(start)) {
            return organicOnly(slot, category, userId, personalize, Outcome.CIRCUIT_OPEN, start, organic);
        }

        int delay = Math.min(Math.max(debugDelayMs, 0), props.maxDebugDelayMs());
        CompletableFuture<Selection> future = CompletableFuture.supplyAsync(
                () -> select(snapshot, category, userId, personalize, delay), executor);
        Selection selection;
        try {
            selection = future.get(props.timeoutMs(), TimeUnit.MILLISECONDS);
            breaker.onSuccess();
        } catch (TimeoutException e) {
            future.cancel(true);
            breaker.onFailure(System.currentTimeMillis());
            return organicOnly(slot, category, userId, personalize, Outcome.TIMEOUT, start, organic);
        } catch (ExecutionException e) {
            breaker.onFailure(System.currentTimeMillis());
            return organicOnly(slot, category, userId, personalize, Outcome.ERROR, start, organic);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return organicOnly(slot, category, userId, personalize, Outcome.ERROR, start, organic);
        }

        List<Auction.Entry> winners = selection.entries().stream().filter(Auction.Entry::won).toList();
        Outcome outcome = winners.isEmpty() ? Outcome.NO_CANDIDATE : Outcome.FILLED;
        outcomes.get(outcome).incrementAndGet();
        filledSlots.addAndGet(winners.size());

        long now = System.currentTimeMillis();
        List<ServedItem> items = new ArrayList<>();
        for (Blender.Slot s : Blender.blend(organic, winners, props.adPositions(), props.listSize())) {
            Product p = s.product();
            if (s.isAd()) {
                Ad ad = s.ad().ad();
                int cpc = s.ad().price();
                items.add(new ServedItem(s.position(), "AD", p.id(), p.name(), p.brand(), p.price(),
                        ad.id(), cpc, tokens.issue(ad.id(), userId, cpc, now)));
            } else {
                items.add(new ServedItem(s.position(), "ORGANIC", p.id(), p.name(), p.brand(), p.price(),
                        null, null, null));
            }
        }
        return new ServeResult(slot, category, userId, personalize, selection.recentBrand(), outcome,
                now - start, items, auctionTable(selection));
    }

    /** ①~⑤: 이 메서드 전체가 시간 제한 안에서 돕니다. */
    private Selection select(Catalog.Snapshot snapshot, String category, String userId, boolean personalize, int delayMs) {
        if (delayMs > 0) {
            sleep(delayMs); // 장애 주입: 느린 저장소 흉내
        }
        List<Ad> ads = snapshot.adsByCategory().getOrDefault(category, List.of());
        UserSignals.Signals signals = userSignals.load(userId, ads);

        CandidateFilter.Result filtered = CandidateFilter.apply(ads, snapshot.exhaustedAdIds(),
                signals.impressionsLastHour(), props.reserveCpc(), props.frequencyCapPerHour());

        List<Auction.Bidder> bidders = filtered.passed().stream()
                .map(ad -> new Auction.Bidder(ad,
                        Prediction.pctr(ad, signals.recentBrand(), personalize, props.personalizationBoost())))
                .toList();

        List<Auction.Entry> entries = Auction.run(bidders, props.adPositions().size(),
                props.reserveCpc(), props.minIncrement());
        return new Selection(signals.recentBrand(), entries, filtered.rejected());
    }

    private ServeResult organicOnly(String slot, String category, String userId, boolean personalize,
                                    Outcome outcome, long start, List<Product> organic) {
        outcomes.get(outcome).incrementAndGet();
        List<ServedItem> items = new ArrayList<>();
        for (Blender.Slot s : Blender.blend(organic, List.of(), props.adPositions(), props.listSize())) {
            Product p = s.product();
            items.add(new ServedItem(s.position(), "ORGANIC", p.id(), p.name(), p.brand(), p.price(), null, null, null));
        }
        return new ServeResult(slot, category, userId, personalize, null, outcome,
                System.currentTimeMillis() - start, items, List.of());
    }

    private static List<AuctionRow> auctionTable(Selection selection) {
        List<AuctionRow> rows = new ArrayList<>();
        for (Auction.Entry e : selection.entries()) {
            Ad ad = e.ad();
            rows.add(new AuctionRow(ad.id(), ad.brand(), ad.bid(), ad.basePctr(), e.pctr(), e.ecpm(),
                    ad.platformWeight(), e.score(), e.rank(), e.price(), e.won() ? "WON" : "LOST"));
        }
        for (CandidateFilter.Rejected r : selection.rejected()) {
            Ad ad = r.ad();
            rows.add(new AuctionRow(ad.id(), ad.brand(), ad.bid(), ad.basePctr(), ad.basePctr(), 0, ad.platformWeight(),
                    0, null, null, "REJECTED:" + r.reason()));
        }
        return rows;
    }

    public Stats stats() {
        long requested = requestedSlots.get();
        long filled = filledSlots.get();
        Map<Outcome, Long> counts = new EnumMap<>(Outcome.class);
        outcomes.forEach((k, v) -> counts.put(k, v.get()));
        return new Stats(requested, filled, requested == 0 ? 0 : (double) filled / requested, counts, breaker.state());
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
