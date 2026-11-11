package ads.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import ads.common.Partitions;
import ads.serving.Catalog;
import ads.serving.ServingService;

/**
 * 과금 컨슈머를 실제 Redis 스트림, Postgres 장부에 붙여 확인합니다.
 * 테스트 전용 광고: A = 901(예산 1000, 파티션 1), B = 902(예산 300, 파티션 2), 카테고리 billtest.
 * 클릭은 2단계 수집이 넣는 것과 같은 모양으로 스트림에 직접 넣거나, 실제 API로 보냅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BillingConsumerTest {

    static final long A = 901;
    static final long B = 902;

    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    Catalog catalog;
    @Autowired
    TestRestTemplate rest;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("ads:*"));
        jdbc.update("DELETE FROM duplicate_clicks");
        jdbc.update("DELETE FROM click_ledger");
        jdbc.update("DELETE FROM ads WHERE id IN (901, 902)");
        jdbc.update("DELETE FROM products WHERE id IN (9901, 9902)");
        jdbc.update("INSERT INTO products (id, name, brand, category, price, popularity) VALUES "
                + "(9901, '테스트 상품 A', 'brand-a', 'billtest', 10000, 10), (9902, '테스트 상품 B', 'brand-b', 'billtest', 10000, 9)");
        jdbc.update("INSERT INTO ads (id, product_id, bid, base_pctr, platform_weight, budget, spent) VALUES "
                + "(901, 9901, 300, 0.0200, 1.00, 1000, 0), (902, 9902, 200, 0.0200, 1.00, 300, 0)");
        catalog.refresh();
    }

    /** 테스트 전용 광고와 그 장부를 지워, 로컬에서 앱을 띄웠을 때 통계에 남지 않게 합니다. */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM duplicate_clicks WHERE ad_id IN (901, 902)");
        jdbc.update("DELETE FROM click_ledger WHERE ad_id IN (901, 902)");
        jdbc.update("DELETE FROM ads WHERE id IN (901, 902)");
        jdbc.update("DELETE FROM products WHERE id IN (9901, 9902)");
    }

    void click(long adId, String clickId, String user, int cpc, long seconds) {
        redis.opsForStream().add(Partitions.streamKeyOfAd(adId), Map.of(
                "type", "CLICK", "eventId", clickId, "impressionId", "imp-" + clickId, "adId", Long.toString(adId),
                "userId", user, "cpc", Integer.toString(cpc), "eventTime", Long.toString(seconds * 1000),
                "receivedAt", "0"));
    }

    BillingConsumer consumer(long adId, String name, int batch) {
        BillingConsumer c = new BillingConsumer(Partitions.of(adId), name, batch, 100, redis, jdbc, tx);
        c.start();
        return c;
    }

    static void drain(BillingConsumer c) {
        while (c.pollOnce(true) > 0) {
            // 밀린 항목을 끝까지 처리
        }
    }

    String result(String clickId) {
        return jdbc.queryForObject("SELECT result FROM click_ledger WHERE click_id = ?", String.class, clickId);
    }

    long spent(long adId) {
        return jdbc.queryForObject("SELECT spent FROM ads WHERE id = ?", Long.class, adId);
    }

    long ledgerSum(long adId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM click_ledger WHERE ad_id = ?", Long.class, adId);
    }

    /*
     * 재현: 기본 예시 c1~c7을 스트림(파티션 1, 2)에 넣고 파티션별 컨슈머로 처리합니다.
     * 증명: 장부, 예산(ads.spent), 재전송 기록이 표대로 남고, 장부 합계 = 쓴 돈. B는 소진되어 Redis에 소진 표시.
     */
    @Test
    void 기본_예시를_스트림으로_처리하면_장부와_예산이_표대로_남는다() {
        click(A, "c1", "u1", 200, 0);
        click(A, "c1", "u1", 200, 1);
        click(A, "c2", "u1", 200, 5);
        click(A, "c3", "u2", 200, 6);
        click(A, "c4", "u1", 200, 12);
        click(B, "c5", "u3", 150, 20);
        click(B, "c6", "u4", 150, 21);
        click(B, "c7", "u5", 150, 22);

        drain(consumer(A, "billing-a", 10));
        drain(consumer(B, "billing-b", 10));

        assertThat(spent(A)).isEqualTo(600);
        assertThat(spent(B)).isEqualTo(300);
        assertThat(ledgerSum(A)).isEqualTo(spent(A));
        assertThat(result("c2")).isEqualTo("INVALID_CLICK");
        assertThat(result("c7")).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duplicate_clicks", Integer.class)).isEqualTo(1);
        assertThat(redis.opsForSet().isMember("ads:exhausted", "902")).isTrue();
    }

    /*
     * 재현: 컨슈머가 장부 커밋은 했는데 ACK를 보내기 직전에 죽습니다. 재시작한 컨슈머가 ACK 안 된 항목을 다시 받습니다.
     * 증명: 장부에 같은 스트림 항목이 이미 있어 "재배달"로 보고 ACK만 합니다. 과금 1번, 재전송 기록 0, 밀린 항목 0.
     */
    @Test
    void 커밋_직후_ACK_전에_죽고_재시작해도_이중_과금이_없다() {
        click(A, "c1", "u1", 200, 0);
        consumer(A, "billing-a", 10).pollOnce(false); // 커밋 후 ACK 없이 죽음

        drain(consumer(A, "billing-a", 10));           // 같은 이름으로 재시작

        assertThat(spent(A)).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM click_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM duplicate_clicks", Integer.class)).isZero();
        assertThat(redis.opsForStream().pending(Partitions.streamKeyOfAd(A), BillingConsumer.GROUP).getTotalPendingMessages()).isZero();
    }

    /*
     * 재현: u1의 클릭을 과금한 뒤 컨슈머가 재시작합니다(메모리 상태가 사라짐). 그리고 u1이 5초 뒤 다시 누릅니다.
     * 증명: 재시작하면서 장부에서 잔액(800)과 u1의 마지막 과금 시각을 다시 만들기 때문에 10초 규칙이 이어집니다.
     */
    @Test
    void 재시작하면_장부에서_잔액과_10초_창을_복구한다() {
        click(A, "c1", "u1", 200, 0);
        drain(consumer(A, "billing-a", 10));

        BillingConsumer restarted = consumer(A, "billing-a", 10);
        click(A, "c2", "u1", 200, 5);
        drain(restarted);

        assertThat(restarted.balance(A)).isEqualTo(800);
        assertThat(result("c2")).isEqualTo("INVALID_CLICK");
    }

    /*
     * 재현(잘못된 구성): 같은 파티션을 컨슈머 둘(x, y)이 나눠 읽습니다. 예산 300, cpc 150인 B에 서로 다른 사용자 클릭 4개.
     *        Redis가 앞의 2개를 x에, 뒤의 2개를 y에 줍니다. 둘은 각자 메모리에 "잔액 300"을 들고 판정합니다.
     * 증명: 4개 모두 과금되어 쓴 돈 600, 예산 300을 넘깁니다(초과 집행). 같은 사용자의 연타가 둘로 갈리면 10초 규칙도 같은 이유로 뚫립니다.
     *       장부의 click_id 유니크는 "같은 클릭 두 번"만 막을 뿐, 서로 다른 클릭이 같은 예산을 동시에 깎는 것은 막지 못합니다.
     */
    @Test
    void 대조_실험_한_파티션을_컨슈머_둘이_나눠_읽으면_예산을_넘겨_과금한다() {
        for (int i = 1; i <= 4; i++) {
            click(B, "b" + i, "user" + i, 150, i);
        }
        BillingConsumer x = consumer(B, "billing-x", 2);
        BillingConsumer y = consumer(B, "billing-y", 2);

        x.pollOnce(true);
        y.pollOnce(true);

        assertThat(spent(B)).isEqualTo(600);
        assertThat(spent(B)).isGreaterThan(300);
    }

    /*
     * 재현(올바른 구성): 같은 클릭 4개를 파티션당 컨슈머 하나가 처리합니다.
     * 증명: 2개 과금(300), 2개는 BUDGET_EXHAUSTED. 한 광고는 한 곳에서만 처리되어 예산을 넘지 않습니다.
     */
    @Test
    void 대조_실험_파티션당_컨슈머_하나면_예산을_넘지_않는다() {
        for (int i = 1; i <= 4; i++) {
            click(B, "b" + i, "user" + i, 150, i);
        }

        drain(consumer(B, "billing-b", 2));

        assertThat(spent(B)).isEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM click_ledger WHERE result = 'BUDGET_EXHAUSTED'", Integer.class))
                .isEqualTo(2);
    }

    /*
     * 재현: u1이 85초와 100초에 클릭했는데(15초 간격, 둘 다 과금이 맞음), 네트워크 때문에 100초 클릭이 먼저 도착했습니다.
     * 증명(알려진 한계): 100초 클릭을 먼저 과금한 뒤 85초 클릭이 오면 "85 − 100 = −15초 < 10초"로 INVALID_CLICK이 됩니다.
     *       정상 클릭 하나를 놓쳐 매출이 줄어듭니다. 이벤트 시간으로 판단해도, 도착 순서가 뒤바뀌면 "마지막 과금 시각" 하나로는
     *       부족합니다. 해결하려면 잠깐 모아 이벤트 시간 순으로 정렬하거나(워터마크), 허용 지연을 넘은 클릭을 배치로 다시 판정해야 합니다
     *       (docs/tradeoffs.md 18).
     */
    @Test
    void 늦게_도착한_정상_클릭이_무과금된다_알려진_한계() {
        click(A, "late-100", "u1", 200, 100);
        click(A, "late-085", "u1", 200, 85);

        drain(consumer(A, "billing-a", 10));

        assertThat(result("late-100")).isEqualTo("CHARGED");
        assertThat(result("late-085")).isEqualTo("INVALID_CLICK");
    }

    /*
     * 재현: B의 예산이 소진되어 컨슈머가 Redis에 소진 표시를 남겼지만, 서빙의 카탈로그 스냅샷은 아직 새로 고쳐지지 않았습니다.
     *       그 사이 사용자가 B 광고를 받아 누릅니다.
     * 증명: 스냅샷이 바뀌기 전까지 B는 계속 나가고, 그 클릭은 BUDGET_EXHAUSTED로 과금되지 않습니다(미청구 금액 = 낙찰 CPC).
     *       스냅샷을 새로 고친 뒤에는 B가 후보에서 빠집니다. 소진 표시 지연이 곧 "광고는 나갔는데 돈은 못 받는" 손실입니다.
     */
    @Test
    void 소진_표시가_서빙에_반영되기_전까지_나간_광고의_클릭은_청구하지_못한다() {
        click(B, "x1", "u1", 150, 0);
        click(B, "x2", "u2", 150, 1);
        BillingConsumer billing = consumer(B, "billing-b", 10);
        drain(billing);
        assertThat(redis.opsForSet().isMember("ads:exhausted", "902")).isTrue();

        ServingService.ServeResult stale = rest.getForObject("/ads?category=billtest&userId=late-user", ServingService.ServeResult.class);
        ServingService.ServedItem servedB = stale.items().stream().filter(i -> Long.valueOf(B).equals(i.adId())).findFirst().orElseThrow();
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (demo browser)");
        rest.exchange("/events/click?token=" + servedB.token() + "&clickId=after-exhausted&eventTime=5000",
                HttpMethod.POST, new HttpEntity<>(headers), String.class);
        drain(billing);

        assertThat(result("after-exhausted")).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(jdbc.queryForObject("SELECT cpc FROM click_ledger WHERE click_id = 'after-exhausted'", Integer.class))
                .isEqualTo(servedB.cpc());

        catalog.refresh();
        ServingService.ServeResult fresh = rest.getForObject("/ads?category=billtest&userId=late-user", ServingService.ServeResult.class);
        assertThat(fresh.auction()).extracting(a -> a.adId() + ":" + a.result()).contains("902:REJECTED:BUDGET_EXHAUSTED");
    }
}
