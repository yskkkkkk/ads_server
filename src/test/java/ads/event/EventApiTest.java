package ads.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import ads.common.Partitions;
import ads.serving.Catalog;
import ads.serving.ServingService;

/**
 * 측정 경로(⑦ 노출, ⑧ 클릭)를 실제 Redis에 붙여 확인합니다.
 * 토큰은 매번 1단계 GET /ads 로 실제 발급받아 씁니다. 모든 이벤트는 서빙 결정에 묶여야 하기 때문입니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventApiTest {

    static final String BROWSER = "Mozilla/5.0 (demo browser)";

    @Autowired
    TestRestTemplate rest;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    Catalog catalog;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("ads:*"));
        catalog.refresh();
    }

    /** 첫 번째 광고 칸(2번째 자리)의 노출 토큰. 시드 데이터에서는 광고 #2(streetfit, 410원). */
    ServingService.ServedItem firstAd(String userId) {
        ServingService.ServeResult r = rest.getForObject("/ads?category=shoes&userId=" + userId, ServingService.ServeResult.class);
        return r.items().stream().filter(i -> "AD".equals(i.type())).findFirst().orElseThrow();
    }

    ResponseEntity<String> post(String url, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, userAgent);
        return rest.exchange(url, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    long accepted(String type) {
        String v = redis.opsForValue().get("ads:count:" + type + ":accepted");
        return v == null ? 0 : Long.parseLong(v);
    }

    /*
     * 재현: 브라우저가 같은 렌더링 노출을 두 번 보냅니다(네트워크 재시도).
     * 증명: 서버는 한 번만 받아 스트림에 하나만 넣고, 나머지는 중복으로 셉니다. 최소 한 번 전달 + ID로 중복 제거.
     */
    @Test
    void 같은_노출이_두_번_와도_한_번만_센다() {
        ServingService.ServedItem ad = firstAd("u1");
        String url = "/events/impression?type=RENDERED&token=" + ad.token();

        post(url, BROWSER);
        post(url, BROWSER);

        assertThat(accepted("rendered")).isEqualTo(1);
        assertThat(redis.opsForValue().get("ads:count:rendered:duplicate")).isEqualTo("1");
        assertThat(redis.opsForStream().size(Partitions.streamKeyOfAd(ad.adId()))).isEqualTo(1);
    }

    /*
     * 재현: 같은 사용자가 목록을 세 번 새로 받고, 그때마다 광고 #2가 화면에 그려집니다(렌더링 노출 3번).
     * 증명: 렌더링 노출이 피로도 횟수를 올리고, 상한(3)에 닿은 뒤 네 번째 서빙부터는 광고 #2가 이 사용자에게 나가지 않습니다.
     *       2단계(측정)가 1단계(서빙)의 후보 필터로 되돌아가는 고리입니다.
     */
    @Test
    void 렌더링_노출_세_번이면_피로도_상한에_걸려_그_광고는_더_나가지_않는다() {
        for (int i = 0; i < 3; i++) {
            ServingService.ServedItem ad = firstAd("tired");
            assertThat(ad.adId()).isEqualTo(2);
            post("/events/impression?type=RENDERED&token=" + ad.token(), BROWSER);
        }

        ServingService.ServeResult fourth = rest.getForObject("/ads?category=shoes&userId=tired", ServingService.ServeResult.class);

        assertThat(fourth.auction()).extracting(a -> a.adId() + ":" + a.result()).contains("2:REJECTED:FREQUENCY_CAP");
    }

    /*
     * 재현: 같은 노출 토큰으로 VIEWABLE도 보냅니다.
     * 증명: 렌더링과 viewable은 다른 종류라 각각 한 번씩 받습니다. viewable은 피로도 횟수를 올리지 않습니다.
     */
    @Test
    void 렌더링과_viewable은_따로_세고_viewable은_피로도를_올리지_않는다() {
        ServingService.ServedItem ad = firstAd("u2");

        post("/events/impression?type=RENDERED&token=" + ad.token(), BROWSER);
        post("/events/impression?type=VIEWABLE&token=" + ad.token(), BROWSER);

        assertThat(accepted("rendered")).isEqualTo(1);
        assertThat(accepted("viewable")).isEqualTo(1);
        assertThat(redis.opsForValue().get("ads:freq:u2:2")).isEqualTo("1");
    }

    /*
     * 재현: 누군가 토큰의 서명 부분을 바꿔 보냅니다(CPC를 고치거나 없는 광고의 클릭을 만들려는 시도).
     * 증명: 서명 검증에 실패해 400으로 거절하고, 스트림에는 아무것도 들어가지 않습니다.
     */
    @Test
    void 서명이_틀린_토큰의_이벤트는_받지_않는다() {
        ServingService.ServedItem ad = firstAd("u3");
        String forged = ad.token().substring(0, ad.token().indexOf('.') + 1) + "forged-signature";

        ResponseEntity<String> res = post("/events/click?token=" + forged, BROWSER);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(redis.opsForValue().get("ads:count:click:invalid-token")).isEqualTo("1");
        assertThat(redis.opsForStream().size(Partitions.streamKeyOfAd(ad.adId()))).isZero();
    }

    /*
     * 재현: 검색 엔진 봇이 광고 링크를 따라 클릭합니다.
     * 증명: GIVT로 걸러져 스트림(과금 대상)에 들어가지 않고, 걸러진 건수만 따로 남습니다. 응답은 정상(204)과 같아 봇은 모릅니다.
     */
    @Test
    void 봇_User_Agent의_클릭은_스트림에_들어가지_않고_따로_센다() {
        ServingService.ServedItem ad = firstAd("u4");

        ResponseEntity<String> res = post("/events/click?token=" + ad.token(), "Googlebot/2.1");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(redis.opsForValue().get("ads:count:click:givt")).isEqualTo("1");
        assertThat(accepted("click")).isZero();
        assertThat(redis.opsForStream().size(Partitions.streamKeyOfAd(ad.adId()))).isZero();
    }

    /*
     * 재현: 같은 클릭(clickId 같음)이 두 번 옵니다.
     * 증명: 수집 단계는 클릭을 중복 제거하지 않고 둘 다 스트림에 넣습니다(최소 한 번 전달).
     *       어느 것을 과금할지는 4단계 과금 컨슈머가 clickId 유니크 장부로 판정합니다.
     */
    @Test
    void 클릭은_수집_단계에서_중복_제거하지_않고_과금_단계에_맡긴다() {
        ServingService.ServedItem ad = firstAd("u5");
        String url = "/events/click?token=" + ad.token() + "&clickId=c1&eventTime=1000";

        post(url, BROWSER);
        post(url, BROWSER);

        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().read(StreamOffset.fromStart(Partitions.streamKeyOfAd(ad.adId())));
        assertThat(records).hasSize(2);
        Map<Object, Object> first = records.get(0).getValue();
        assertThat(first).containsEntry("type", "CLICK").containsEntry("eventId", "c1")
                .containsEntry("cpc", "410").containsEntry("userId", "u5").containsEntry("eventTime", "1000");
    }

    /*
     * 재현: 클릭 URL 방식. 사용자가 광고를 누르면 서버의 /events/click 으로 가고, 서버가 상품 페이지로 보냅니다.
     * 증명: 리다이렉트 주소에 같은 토큰이 이어 붙습니다(파라미터 체이닝). chain=false면 토큰이 빠집니다(체이닝 단절).
     */
    @Test
    void 클릭_URL은_상품_페이지로_보내며_토큰을_이어_붙인다() {
        ServingService.ServedItem ad = firstAd("u6");
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.USER_AGENT, BROWSER);

        TestRestTemplate noFollow = rest.withRedirects(ClientHttpRequestFactorySettings.Redirects.DONT_FOLLOW);
        ResponseEntity<String> chained = noFollow.exchange("/events/click?token=" + ad.token(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        ResponseEntity<String> broken = noFollow.exchange("/events/click?chain=false&token=" + ad.token(), HttpMethod.GET,
                new HttpEntity<>(headers), String.class);

        assertThat(chained.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(chained.getHeaders().getLocation().toString())
                .isEqualTo("/demo/product.html?productId=102&adToken=" + ad.token());
        assertThat(broken.getHeaders().getLocation().toString()).isEqualTo("/demo/product.html?productId=102");
        assertThat(accepted("click")).isEqualTo(2);
    }

    /*
     * 재현: 광고 #1~#6의 이벤트를 보냅니다.
     * 증명: 이벤트는 광고 ID로 정한 파티션 스트림에만 들어갑니다. 같은 광고는 언제나 같은 파티션입니다.
     */
    @Test
    void 같은_광고의_이벤트는_언제나_같은_파티션에_들어간다() {
        assertThat(Partitions.of(2)).isEqualTo(Partitions.of(2));
        assertThat(List.of(1L, 2L, 3L, 4L, 5L, 6L)).extracting(Partitions::of).containsExactly(1, 2, 3, 0, 1, 2);
        assertThat(Partitions.streamKeyOfAd(6)).isEqualTo("ads:events:2");
    }
}
