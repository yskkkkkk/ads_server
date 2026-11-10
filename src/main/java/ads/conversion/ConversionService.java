package ads.conversion;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import ads.common.ImpressionTokens;
import ads.event.EventCollector;
import ads.serving.Ad;
import ads.serving.Catalog;

/**
 * 구매(전환) 한 건을 받아 어느 광고 덕분인지 정하고 기록합니다.
 *
 * 지키는 규칙
 * - 구매자를 찾는 단서는 두 가지: URL로 이어받은 노출 토큰(안에 사용자 ID가 있음)과 로그인 사용자 ID.
 *   둘 중 있는 것으로 클릭 기록(ads:clicks:{사용자}, 2단계 수집이 남김)을 읽어 Attribution 규칙에 넘깁니다.
 * - 같은 주문(orderId)은 한 번만 기록합니다. conversions.order_id 기본 키로 INSERT가 무시되면
 *   처음 기록된 결과를 그대로 돌려줍니다(재전송해도 매출이 두 번 잡히지 않음).
 *
 * 왜 이 방식인지
 * - 귀속 판단(Attribution)은 순수 함수로 두고, 이 클래스는 재료(클릭 기록)를 모으고 결과를 저장하는 일만 합니다.
 * - 중복 주문 판정을 "먼저 조회하고 없으면 INSERT"로 하지 않고 기본 키에 맡깁니다. 같은 주문이 동시에 두 번 와도
 *   DB가 하나만 받아 줍니다. 4단계 과금 장부(click_id 유니크)와 같은 원리입니다.
 *
 * 이 규칙이 깨지면
 * - 주문 중복을 막지 못하면 한 번의 구매가 두 번 매출로 잡혀 ROAS가 부풀립니다.
 * - 클릭 기록을 한 경로(토큰)로만 찾으면, URL에서 토큰이 빠진 구매는 로그인 사용자의 것이어도 모두 "광고와 무관한 매출"이 됩니다.
 */
@Service
public class ConversionService {

    public record Outcome(String orderId, long productId, int amount, Long adId, String clickId,
                          Attribution.By attributedBy, Attribution.Reason reason, boolean duplicate) {
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final Catalog catalog;

    public ConversionService(StringRedisTemplate redis, JdbcTemplate jdbc, Catalog catalog) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    public Outcome record(String orderId, long productId, int amount,
                          ImpressionTokens.Claims token, String loginUserId, long orderedAt) {
        Set<String> buyers = new LinkedHashSet<>();
        if (loginUserId != null) {
            buyers.add(loginUserId);
        }
        if (token != null) {
            buyers.add(token.userId());
        }

        List<Attribution.Click> clicks = new ArrayList<>();
        buyers.forEach(userId -> clicks.addAll(clickHistory(userId)));

        Attribution.Result result = Attribution.attribute(productId, orderedAt,
                token == null ? null : token.impressionId(), !buyers.isEmpty(), clicks);
        Attribution.Click click = result.click();

        int inserted = jdbc.update("""
                INSERT INTO conversions (order_id, user_id, product_id, amount, ad_id, click_id,
                                         attributed_by, reason, ordered_at, clicked_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (order_id) DO NOTHING
                """,
                orderId, loginUserId, productId, amount,
                click == null ? null : click.adId(), click == null ? null : click.clickId(),
                result.by().name(), result.reason().name(), new Timestamp(orderedAt),
                click == null ? null : new Timestamp(click.clickedAt()));

        if (inserted == 0) {
            return jdbc.queryForObject("""
                    SELECT order_id, product_id, amount, ad_id, click_id, attributed_by, reason
                      FROM conversions WHERE order_id = ?
                    """,
                    (rs, i) -> new Outcome(rs.getString("order_id"), rs.getLong("product_id"), rs.getInt("amount"),
                            (Long) rs.getObject("ad_id"), rs.getString("click_id"),
                            Attribution.By.valueOf(rs.getString("attributed_by")),
                            Attribution.Reason.valueOf(rs.getString("reason")), true),
                    orderId);
        }
        return new Outcome(orderId, productId, amount, click == null ? null : click.adId(),
                click == null ? null : click.clickId(), result.by(), result.reason(), false);
    }

    /** ads:clicks:{사용자} 의 항목 "impressionId|adId|clickId", 점수 = 클릭 시각(ms). */
    private List<Attribution.Click> clickHistory(String userId) {
        Set<ZSetOperations.TypedTuple<String>> entries =
                redis.opsForZSet().rangeWithScores(EventCollector.clickHistoryKey(userId), 0, -1);
        List<Attribution.Click> clicks = new ArrayList<>();
        if (entries == null) {
            return clicks;
        }
        Map<Long, Ad> ads = catalog.snapshot().adsById();
        for (ZSetOperations.TypedTuple<String> e : entries) {
            String[] parts = e.getValue().split("\\|");
            Ad ad = ads.get(Long.parseLong(parts[1]));
            if (ad != null) {
                clicks.add(new Attribution.Click(parts[0], ad.id(), ad.productId(), parts[2], e.getScore().longValue()));
            }
        }
        return clicks;
    }

    /**
     * 광고별 귀속 매출과 추정 ROAS, 귀속되지 않은 매출, 귀속 경로별 건수.
     * 추정 광고비는 2단계 수집이 클릭마다 더한 낙찰 CPC 합입니다(재전송 제외, 10초 규칙, 예산 같은 과금 규칙은 적용 전).
     * 실제 광고비는 4단계 과금 장부가 정하고, 5단계 리포트에서 둘의 차이를 봅니다.
     */
    public Map<String, Object> stats() {
        Map<Long, long[]> byAd = new LinkedHashMap<>();
        jdbc.query("SELECT ad_id, count(*) AS orders, sum(amount) AS revenue FROM conversions "
                        + "WHERE ad_id IS NOT NULL GROUP BY ad_id ORDER BY ad_id",
                rs -> {
                    byAd.put(rs.getLong("ad_id"), new long[] {rs.getLong("orders"), rs.getLong("revenue")});
                });

        List<Map<String, Object>> ads = new ArrayList<>();
        for (Ad ad : catalog.snapshot().adsById().values().stream().sorted((a, b) -> Long.compare(a.id(), b.id())).toList()) {
            long[] ordersRevenue = byAd.getOrDefault(ad.id(), new long[] {0, 0});
            String spendValue = redis.opsForValue().get(EventCollector.estimatedSpendKey(ad.id()));
            long spend = spendValue == null ? 0 : Long.parseLong(spendValue);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("adId", ad.id());
            row.put("brand", ad.brand());
            row.put("orders", ordersRevenue[0]);
            row.put("revenue", ordersRevenue[1]);
            row.put("estimatedSpend", spend);
            row.put("estimatedRoas", spend == 0 ? null : (double) ordersRevenue[1] / spend);
            ads.add(row);
        }

        Map<String, Object> unattributed = jdbc.queryForMap(
                "SELECT count(*) AS orders, coalesce(sum(amount), 0) AS revenue FROM conversions WHERE ad_id IS NULL");

        Map<String, Long> paths = new LinkedHashMap<>();
        jdbc.query("SELECT attributed_by, reason, count(*) AS n FROM conversions GROUP BY 1, 2 ORDER BY 1, 2",
                rs -> {
                    paths.put(rs.getString("attributed_by") + ":" + rs.getString("reason"), rs.getLong("n"));
                });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ads", ads);
        result.put("unattributed", unattributed);
        result.put("attributionPaths", paths);
        return result;
    }
}
