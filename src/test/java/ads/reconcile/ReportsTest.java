package ads.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

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

import ads.billing.BillingConsumer;
import ads.common.Partitions;
import ads.serving.Catalog;
import ads.serving.ServingService;

/**
 * 1~4단계를 실제 API로 한 바퀴 돌린 뒤 5단계 리포트를 확인합니다. 시드 광고 #2(streetfit, 상품 102, 낙찰 CPC 410원).
 *
 * 시나리오: 사용자 8명에게 광고 #2가 그려지고(RENDERED 8), 그중 4명 화면에서 viewable(4).
 *           한 사용자가 클릭 k1, 같은 k1 재전송, 2초 뒤 k2(10초 규칙에 걸림). 그리고 상품 102를 89,000원에 삼.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReportsTest {

    static final long T0 = 1_800_000_000_000L;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    Catalog catalog;
    @Autowired
    EventLogConsumer eventLog;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("ads:*"));
        jdbc.update("DELETE FROM event_log");
        jdbc.update("DELETE FROM duplicate_clicks");
        jdbc.update("DELETE FROM click_ledger");
        jdbc.update("DELETE FROM conversions");
        jdbc.update("UPDATE ads SET spent = 0");
        catalog.refresh();
        eventLog.restart();
    }

    void post(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (demo browser)");
        rest.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    String firstAdToken(String userId) {
        ServingService.ServeResult r = rest.getForObject("/ads?category=shoes&userId=" + userId, ServingService.ServeResult.class);
        return r.items().stream().filter(i -> "AD".equals(i.type())).findFirst().orElseThrow().token();
    }

    /** 시나리오의 이벤트를 만들고 클릭한 사용자의 토큰을 돌려줍니다. */
    String collectScenario() {
        String clickerToken = null;
        for (int i = 1; i <= 8; i++) {
            String token = firstAdToken("viewer" + i);
            post("/events/impression?type=RENDERED&token=" + token);
            if (i <= 4) {
                post("/events/impression?type=VIEWABLE&token=" + token);
            }
            if (i == 1) {
                clickerToken = token;
            }
        }
        post("/events/click?token=" + clickerToken + "&clickId=k1&eventTime=" + T0);
        post("/events/click?token=" + clickerToken + "&clickId=k1&eventTime=" + T0);
        post("/events/click?token=" + clickerToken + "&clickId=k2&eventTime=" + (T0 + 2000));
        return clickerToken;
    }

    void bill() {
        BillingConsumer billing = new BillingConsumer(Partitions.of(2), "billing-2", 50, 100, redis, jdbc, tx);
        billing.start();
        while (billing.pollOnce(true) > 0) {
            // 끝까지
        }
    }

    void aggregate() {
        while (eventLog.pollOnce() > 0) {
            // 끝까지
        }
    }

    Map<?, ?> reconciliationOfAd2() {
        Map<?, ?> r = rest.getForObject("/reports/reconciliation", Map.class);
        return ((List<Map<?, ?>>) r.get("ads")).stream().filter(a -> a.get("ad_id").equals(2)).findFirst().orElseThrow();
    }

    Map<?, ?> performanceOfAd2() {
        List<Map<?, ?>> rows = rest.getForObject("/reports/performance", List.class);
        return rows.stream().filter(a -> a.get("ad_id").equals(2)).findFirst().orElseThrow();
    }

    /*
     * 재현: 시나리오를 수집하고 과금 컨슈머(그룹 billing)와 집계 컨슈머(그룹 stats)가 같은 스트림을 각자 끝까지 읽습니다.
     * 증명: 서로 다른 출처의 숫자가 맞습니다.
     *       수집한 클릭 이벤트 3 = 장부 2(과금 1, INVALID_CLICK 1) + 재전송 1, 장부 과금 합계 410 = 쓴 돈 410 → OK.
     *       미청구 금액은 INVALID_CLICK의 낙찰 CPC 410원.
     */
    @Test
    void 모든_단계가_끝나면_수집_장부_예산이_서로_맞는다() {
        collectScenario();
        bill();
        aggregate();

        Map<?, ?> ad2 = reconciliationOfAd2();

        assertThat(ad2.get("click_events")).isEqualTo(3);
        assertThat(ad2.get("ledger_rows")).isEqualTo(2);
        assertThat(ad2.get("duplicate")).isEqualTo(1);
        assertThat(ad2.get("ledger_charged")).isEqualTo(410);
        assertThat(ad2.get("spent")).isEqualTo(410);
        assertThat(ad2.get("unbilled")).isEqualTo(410);
        assertThat(ad2.get("status")).isEqualTo("OK");
    }

    /*
     * 재현: 이벤트는 집계됐는데 과금 컨슈머가 아직 돌지 않았습니다(밀림).
     * 증명: 처리 대기 3건, LAGGING. 돈은 아직 아무도 건드리지 않아 MISMATCH는 아닙니다.
     */
    @Test
    void 과금이_밀리면_LAGGING과_처리_대기_건수가_보인다() {
        collectScenario();
        aggregate();

        Map<?, ?> ad2 = reconciliationOfAd2();

        assertThat(ad2.get("pending")).isEqualTo(3);
        assertThat(ad2.get("status")).isEqualTo("LAGGING");
    }

    /*
     * 재현: 누군가 장부 없이 예산을 직접 고쳤습니다(반쪽 상태 흉내: 쓴 돈만 100원 늘어남).
     * 증명: 건수는 맞아도 돈이 달라 MISMATCH. 전체 판정도 MISMATCH가 됩니다.
     */
    @Test
    void 장부와_쓴_돈이_다르면_MISMATCH로_잡아낸다() {
        collectScenario();
        bill();
        aggregate();
        jdbc.update("UPDATE ads SET spent = spent + 100 WHERE id = 2");

        assertThat(reconciliationOfAd2().get("status")).isEqualTo("MISMATCH");
        assertThat(rest.getForObject("/reports/reconciliation", Map.class).get("status")).isEqualTo("MISMATCH");
    }

    /*
     * 재현: 같은 시나리오에 구매 1건(89,000원)을 더합니다.
     * 증명: 광고주가 보는 숫자가 정의에 따라 달라집니다.
     *   - CTR: 클릭 2(재전송 뺌) ÷ 렌더링 8 = 0.25, ÷ viewable 4 = 0.5. 같은 클릭인데 분모 정의로 두 배 차이.
     *   - ROAS: 실제 광고비(장부) 410원 기준 약 217배, 추정 광고비(과금 규칙 전) 820원 기준 약 108배.
     *     추정은 10초 규칙에 걸린 k2까지 광고비로 쳐서 ROAS를 절반으로 낮춰 봅니다. 차이 410원 = 미청구 금액.
     */
    @Test
    void 성과_리포트는_CTR_두_기준과_실제_추정_ROAS를_나란히_보여준다() {
        String token = collectScenario();
        post("/events/conversion?orderId=r1&productId=102&amount=89000&orderedAt=" + (T0 + 60_000) + "&token=" + token);
        bill();
        aggregate();

        Map<?, ?> ad2 = performanceOfAd2();

        assertThat(ad2.get("rendered")).isEqualTo(8);
        assertThat(ad2.get("viewable")).isEqualTo(4);
        assertThat(ad2.get("clicks")).isEqualTo(2);
        assertThat(ad2.get("ctrRendered")).isEqualTo(0.25);
        assertThat(ad2.get("ctrViewable")).isEqualTo(0.5);
        assertThat(ad2.get("spend")).isEqualTo(410);
        assertThat(ad2.get("estimatedSpend")).isEqualTo(820);
        assertThat((Double) ad2.get("roas")).isBetween(217.0, 217.1);
        assertThat((Double) ad2.get("estimatedRoas")).isBetween(108.5, 108.6);
    }
}
