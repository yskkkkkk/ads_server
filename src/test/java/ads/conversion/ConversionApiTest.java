package ads.conversion;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import ads.serving.Catalog;
import ads.serving.ServingService;

/**
 * 서빙(1단계) → 클릭(2단계) → 구매(3단계)를 실제 API로 이어서 확인합니다.
 * 시드 데이터에서 첫 광고 칸은 광고 #2(streetfit, 상품 102, 낙찰 CPC 410원)입니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConversionApiTest {

    static final long DAY = 24 * 60 * 60 * 1000L;
    static final long T0 = 1_800_000_000_000L;

    @Autowired
    TestRestTemplate rest;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Catalog catalog;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("ads:*"));
        jdbc.update("DELETE FROM conversions");
        catalog.refresh();
    }

    /** userId로 목록을 받아 첫 광고(#2)를 clickId로 t 시각에 누르고, 그 노출 토큰을 돌려줍니다. */
    String clickFirstAd(String userId, String clickId, long t) {
        ServingService.ServeResult r = rest.getForObject("/ads?category=shoes&userId=" + userId, ServingService.ServeResult.class);
        String token = r.items().stream().filter(i -> "AD".equals(i.type())).findFirst().orElseThrow().token();
        post("/events/click?token=" + token + "&clickId=" + clickId + "&eventTime=" + t);
        return token;
    }

    ResponseEntity<Map> post(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, "Mozilla/5.0 (demo browser)");
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), Map.class);
    }

    Map<?, ?> buy(String query) {
        return post("/events/conversion?amount=89000&" + query).getBody();
    }

    /*
     * 재현: 광고 #2를 누르고, 상품 페이지 URL로 이어받은 토큰과 함께 1시간 뒤 상품 102를 삽니다. 로그인은 안 함.
     * 증명: 토큰 안의 사용자 ID로 클릭 기록을 찾아 광고 #2에 귀속합니다(TOKEN 경로).
     */
    @Test
    void 토큰을_이어받은_구매는_클릭한_광고에_귀속된다() {
        String token = clickFirstAd("u1", "c1", T0);

        Map<?, ?> r = buy("orderId=o1&productId=102&orderedAt=" + (T0 + DAY / 24) + "&token=" + token);

        assertThat(r.get("adId")).isEqualTo(2);
        assertThat(r.get("attributedBy")).isEqualTo("TOKEN");
        assertThat(r.get("clickId")).isEqualTo("c1");
    }

    /*
     * 재현: 광고 #2를 누른 뒤 상품 페이지로 가는 길에 URL 토큰이 빠졌습니다(파라미터 체이닝 단절). 사용자는 로그인 상태입니다.
     * 증명: 리테일 미디어는 구매자를 알기 때문에 그 사용자의 클릭 기록으로 다시 이어 귀속합니다(USER_HISTORY, 클로즈드 루프).
     */
    @Test
    void 토큰이_빠져도_로그인_사용자면_클릭_기록으로_귀속된다() {
        clickFirstAd("u2", "c2", T0);

        Map<?, ?> r = buy("orderId=o2&productId=102&orderedAt=" + (T0 + DAY) + "&userId=u2");

        assertThat(r.get("adId")).isEqualTo(2);
        assertThat(r.get("attributedBy")).isEqualTo("USER_HISTORY");
    }

    /*
     * 재현: 토큰이 빠졌고 로그인도 하지 않은 구매.
     * 증명: 클릭 기록은 있지만 누구의 구매인지 몰라 귀속하지 못하고, "광고와 무관한 매출"로 남습니다.
     *       이 매출만큼 광고 #2의 ROAS가 실제보다 낮게 잡힙니다.
     */
    @Test
    void 토큰도_로그인도_없으면_어느_광고에도_귀속되지_않는다() {
        clickFirstAd("u3", "c3", T0);

        Map<?, ?> r = buy("orderId=o3&productId=102&orderedAt=" + (T0 + DAY));

        assertThat(r.get("attributedBy")).isEqualTo("NONE");
        assertThat(r.get("reason")).isEqualTo("NO_IDENTITY");
        Map<?, ?> stats = rest.getForObject("/conversions/stats", Map.class);
        assertThat(((Map<?, ?>) stats.get("unattributed")).get("revenue")).isEqualTo(89000);
    }

    /*
     * 재현: 클릭 후 정확히 7일 뒤의 구매와, 7일 + 1초 뒤의 구매.
     * 증명: 7일까지는 귀속, 넘으면 OUTSIDE_WINDOW. 판단은 서버가 받은 시각이 아니라 클릭과 구매가 일어난 시각(이벤트 시간) 기준입니다.
     */
    @Test
    void 클릭_후_7일까지만_귀속한다() {
        String token = clickFirstAd("u4", "c4", T0);

        Map<?, ?> inside = buy("orderId=o4a&productId=102&orderedAt=" + (T0 + 7 * DAY) + "&token=" + token);
        Map<?, ?> outside = buy("orderId=o4b&productId=102&orderedAt=" + (T0 + 7 * DAY + 1000) + "&token=" + token);

        assertThat(inside.get("reason")).isEqualTo("ATTRIBUTED");
        assertThat(outside.get("reason")).isEqualTo("OUTSIDE_WINDOW");
    }

    /*
     * 재현: 광고 #2(상품 102)를 누르고 다른 상품 106을 삽니다.
     * 증명: 같은 상품만 귀속하는 규칙이라 NO_CLICK. (같은 브랜드 다른 상품까지 쳐주는 할로 효과는 여기서 다루지 않음)
     */
    @Test
    void 클릭한_광고와_다른_상품을_사면_귀속하지_않는다() {
        String token = clickFirstAd("u5", "c5", T0);

        Map<?, ?> r = buy("orderId=o5&productId=106&orderedAt=" + (T0 + DAY) + "&token=" + token);

        assertThat(r.get("reason")).isEqualTo("NO_CLICK");
    }

    /*
     * 재현: 결제 완료 요청이 네트워크 재시도로 두 번 옵니다(같은 orderId).
     * 증명: 두 번째는 duplicate=true로 처음 결과를 돌려주고, 매출은 한 번만 잡힙니다.
     */
    @Test
    void 같은_주문이_두_번_와도_매출은_한_번만_센다() {
        String token = clickFirstAd("u6", "c6", T0);
        String query = "orderId=o6&productId=102&orderedAt=" + (T0 + DAY) + "&token=" + token;

        buy(query);
        Map<?, ?> again = buy(query);

        assertThat(again.get("duplicate")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT sum(amount) FROM conversions", Integer.class)).isEqualTo(89000);
    }

    /*
     * 재현: 광고 #2 클릭(같은 clickId)이 재전송으로 두 번 오고, 89,000원 구매가 귀속됩니다.
     * 증명: 추정 광고비는 처음 보는 clickId만 더해 410원(820원 아님), 추정 ROAS = 89,000 ÷ 410 ≈ 217배.
     *       (10초 규칙, 예산 같은 과금 규칙은 4단계 장부가 적용하고, 5단계에서 추정과 실제의 차이를 봅니다)
     */
    @Test
    void 추정_ROAS는_귀속_매출을_재전송_뺀_추정_광고비로_나눈다() {
        String token = clickFirstAd("u7", "c7", T0);
        post("/events/click?token=" + token + "&clickId=c7&eventTime=" + T0);

        buy("orderId=o7&productId=102&orderedAt=" + (T0 + DAY) + "&token=" + token);

        Map<?, ?> stats = rest.getForObject("/conversions/stats", Map.class);
        Map<?, ?> ad2 = ((List<Map<?, ?>>) stats.get("ads")).stream()
                .filter(a -> a.get("adId").equals(2)).findFirst().orElseThrow();
        assertThat(ad2.get("revenue")).isEqualTo(89000);
        assertThat(ad2.get("estimatedSpend")).isEqualTo(410);
        assertThat((Double) ad2.get("estimatedRoas")).isBetween(217.0, 217.1);
    }

    /*
     * 재현: 남의 광고에 매출을 붙이려고 토큰 서명을 바꿔 보냅니다.
     * 증명: 400으로 거절하고 아무것도 기록하지 않습니다.
     */
    @Test
    void 서명이_틀린_토큰의_구매는_받지_않는다() {
        String token = clickFirstAd("u8", "c8", T0);
        String forged = token.substring(0, token.indexOf('.') + 1) + "forged";

        ResponseEntity<String> res = rest.postForEntity(
                "/events/conversion?amount=89000&orderId=o8&productId=102&token=" + forged, null, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM conversions", Integer.class)).isZero();
    }
}
