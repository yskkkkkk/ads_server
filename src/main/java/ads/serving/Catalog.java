package ads.serving;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 광고와 일반 상품의 메모리 스냅샷. 서빙은 요청마다 DB를 부르지 않고 이것을 읽습니다.
 *
 * 지키는 규칙
 * - 몇 초마다(catalog-refresh-ms) DB의 광고, 상품과 Redis의 소진 표시(ads:exhausted)를 통째로 다시 읽어 바꿔 끼웁니다.
 * - 새로 읽다가 실패하면 이전 스냅샷을 그대로 씁니다. 조금 낡은 광고라도 내보내는 편이 광고를 못 내는 것보다 낫습니다.
 *
 * 왜 이 방식인지
 * - 서빙 경로의 시간 예산은 50ms입니다. 서버(춘천)에서 DB(싱가포르)까지 왕복만으로 이 예산을 다 쓸 수 있어서,
 *   후보 목록처럼 모든 사용자에게 같은 데이터는 미리 메모리에 올려 둡니다(docs/tradeoffs.md 7).
 * - 사용자마다 다른 데이터(최근 본 브랜드, 노출 횟수)는 미리 올릴 수 없어 요청마다 Redis를 한 번 부릅니다(UserSignals).
 *
 * 이 규칙이 깨지면
 * - 요청마다 DB를 부르면 서버에서는 광고 조회가 늘 50ms를 넘겨 채움률이 0%가 됩니다.
 * - 대가: 스냅샷이 새로 고쳐지기 전까지는 예산이 막 소진된 광고도 계속 나갑니다. 이 간격만큼 초과 집행이 생길 수 있습니다
 *   (4단계 "소진 표시 지연 동안의 초과 집행" 시나리오).
 */
@Component
public class Catalog {

    private static final Logger log = LoggerFactory.getLogger(Catalog.class);
    static final String EXHAUSTED_KEY = "ads:exhausted";

    public record Snapshot(Map<String, List<Ad>> adsByCategory,
                           Map<String, List<Product>> productsByCategory,
                           Map<Long, Ad> adsById,
                           Map<Long, Product> productsById,
                           Set<Long> exhaustedAdIds,
                           Instant loadedAt) {
    }

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private volatile Snapshot snapshot = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Instant.EPOCH);

    public Catalog(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    @Scheduled(fixedDelayString = "${ads.serving.catalog-refresh-ms}")
    public void refresh() {
        try {
            List<Ad> ads = jdbc.query("""
                    SELECT a.id, a.product_id, p.name, p.brand, p.category, p.price,
                           a.bid, a.base_pctr, a.platform_weight, a.budget, a.spent
                      FROM ads a JOIN products p ON p.id = a.product_id
                    """,
                    (rs, i) -> new Ad(rs.getLong("id"), rs.getLong("product_id"), rs.getString("name"),
                            rs.getString("brand"), rs.getString("category"), rs.getInt("price"), rs.getInt("bid"),
                            rs.getDouble("base_pctr"), rs.getDouble("platform_weight"),
                            rs.getInt("budget"), rs.getInt("spent")));

            List<Product> products = jdbc.query("""
                    SELECT id, name, brand, category, price FROM products ORDER BY popularity DESC, id
                    """,
                    (rs, i) -> new Product(rs.getLong("id"), rs.getString("name"), rs.getString("brand"),
                            rs.getString("category"), rs.getInt("price")));

            Set<String> exhausted = redis.opsForSet().members(EXHAUSTED_KEY);
            Set<Long> exhaustedIds = exhausted == null ? Set.of()
                    : exhausted.stream().map(Long::valueOf).collect(Collectors.toUnmodifiableSet());

            snapshot = new Snapshot(
                    ads.stream().collect(Collectors.groupingBy(Ad::category)),
                    products.stream().collect(Collectors.groupingBy(Product::category)),
                    ads.stream().collect(Collectors.toUnmodifiableMap(Ad::id, a -> a)),
                    products.stream().collect(Collectors.toUnmodifiableMap(Product::id, p -> p)),
                    exhaustedIds,
                    Instant.now());
        } catch (RuntimeException e) {
            log.warn("catalog refresh failed, keep previous snapshot loaded at {}: {}", snapshot.loadedAt(), e.toString());
        }
    }
}
