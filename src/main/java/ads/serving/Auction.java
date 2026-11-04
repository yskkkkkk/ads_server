package ads.serving;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 서빙 ④ 랭킹과 ⑤ 옥션: 광고 자리(슬롯) N개를 누구에게 얼마에 팔지 정합니다.
 *
 * 지키는 규칙
 * - 랭킹은 입찰가 순이 아니라 점수 순입니다.
 *     eCPM = 입찰가 × pCTR × 1000        (노출 1,000번당 기대 수익)
 *     점수 = eCPM × 플랫폼 가중치         (마진, 프로모션 반영. 1.0이면 순수 eCPM)
 * - 가격은 2등가(GSP)입니다. k등은 k+1등에게 자리를 뺏기지 않을 최소 금액만 냅니다.
 *     내 입찰가 b일 때 내 점수 = b × 내 pCTR × 1000 × 내 가중치 ≥ 다음 순위 점수 가 되는 최소 b
 *     → 낙찰 CPC = ⌈다음 순위 점수 ÷ (내 pCTR × 1000 × 내 가중치)⌉ + 최소 단위(10원)
 * - 낙찰 CPC는 내 입찰가를 넘지 않고, 최저가보다 낮지 않습니다. 다음 순위가 없으면 최저가를 냅니다.
 *
 * 왜 이 방식인지
 * - 입찰가 순이면 돈만 많이 낸, 클릭되지 않을 광고가 1등을 합니다. 플랫폼은 CPC 광고에서 클릭이 나야 돈을 받으므로
 *   "클릭 확률 × 클릭당 가격"이 큰 광고가 실제로 더 많은 돈을 벌어 줍니다(CLAUDE.md 3-3, 3-12).
 * - 2등가를 쓰면 광고주는 "내가 낼 수 있는 최대 금액"을 정직하게 입찰해도 손해를 보지 않습니다.
 *   1등가라면 광고주마다 입찰가를 깎아 보는 눈치 싸움이 생깁니다(CLAUDE.md 3-4).
 * - 스프링, DB를 모르는 순수 함수라서 시드 데이터의 숫자를 그대로 넣어 단위 테스트로 확인합니다(AuctionTest).
 * - 돈 계산의 부동소수점 오차(예: 0.03 × 1000 = 30.000000000000004) 때문에 올림 전에 아주 작은 값을 뺍니다.
 *
 * 이 규칙이 깨지면
 * - 입찰가 순 랭킹: 무관한 광고가 나가 사용자는 떠나고, 클릭이 줄어 광고주 성과와 플랫폼 수익이 함께 떨어집니다.
 * - 가격이 입찰가를 넘으면 광고주 동의 없이 더 많이 청구하는 것이고, 최저가 아래로 팔면 지면 가치가 무너집니다.
 */
public final class Auction {

    public record Bidder(Ad ad, double pctr) {
    }

    /** 경매에 참여한 광고 한 줄. price가 null이면 낙찰받지 못한 광고입니다. */
    public record Entry(Ad ad, double pctr, double ecpm, double score, int rank, Integer price) {
        public boolean won() {
            return price != null;
        }
    }

    private static final double EPSILON = 1e-9;

    private Auction() {
    }

    public static List<Entry> run(List<Bidder> bidders, int slots, int reserveCpc, int minIncrement) {
        List<Bidder> ranked = new ArrayList<>(bidders);
        ranked.sort(Comparator.comparingDouble(Auction::score).reversed()
                .thenComparingLong(b -> b.ad().id()));

        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Bidder me = ranked.get(i);
            Integer price = null;
            if (i < slots) {
                Bidder next = i + 1 < ranked.size() ? ranked.get(i + 1) : null;
                price = price(me, next, reserveCpc, minIncrement);
            }
            entries.add(new Entry(me.ad(), me.pctr(), ecpm(me), score(me), i + 1, price));
        }
        return entries;
    }

    private static int price(Bidder me, Bidder next, int reserveCpc, int minIncrement) {
        if (next == null) {
            return Math.min(me.ad().bid(), reserveCpc);
        }
        double minimumBid = score(next) / (me.pctr() * 1000 * me.ad().platformWeight());
        int secondPrice = (int) Math.ceil(minimumBid - EPSILON) + minIncrement;
        return Math.min(me.ad().bid(), Math.max(reserveCpc, secondPrice));
    }

    static double ecpm(Bidder b) {
        return b.ad().bid() * b.pctr() * 1000;
    }

    static double score(Bidder b) {
        return ecpm(b) * b.ad().platformWeight();
    }
}
