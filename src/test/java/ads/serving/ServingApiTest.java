package ads.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;

import ads.common.ImpressionTokens;

/**
 * GET /ads 를 실제 Postgres(시드 데이터), Redis에 붙여 호출합니다.
 * 시드 숫자와 기대값은 V2__seed.sql 머리 주석의 표와 같습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServingApiTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    Catalog catalog;
    @Autowired
    ImpressionTokens tokens;

    @BeforeEach
    void setUp() {
        redis.delete(redis.keys("ads:*"));
        catalog.refresh();
    }

    ServingService.ServeResult get(String query) {
        return rest.getForObject("/ads?category=shoes&" + query, ServingService.ServeResult.class);
    }

    static List<String> layout(ServingService.ServeResult r) {
        return r.items().stream().map(i -> ("AD".equals(i.type()) ? "AD" : "") + i.productId()).toList();
    }

    /*
     * 재현: 처음 온 사용자가 신발 목록을 엽니다.
     * 증명: 서빙 ①~⑥이 한 번에 보입니다.
     *   - 후보: daily(입찰가 80원 < 최저가 100원), urban(예산 소진)은 빠짐
     *   - 랭킹, 옥션: streetfit 410원, mono 344원 낙찰 / 입찰가 1등 runway는 짐
     *   - 블렌딩: 광고는 2, 6번째 자리, 광고 상품(102, 103)은 일반 목록에서 빠짐
     *   - 광고마다 서명된 노출 토큰, 토큰 안의 CPC = 낙찰 CPC
     */
    @Test
    void 처음_온_사용자에게_eCPM_순으로_두_광고가_섞여_나간다() {
        ServingService.ServeResult r = get("userId=first-visitor");

        assertThat(r.outcome()).isEqualTo(ServingService.Outcome.FILLED);
        assertThat(layout(r)).containsExactly("106", "AD102", "107", "108", "101", "AD103", "109", "104");
        assertThat(r.auction()).extracting(a -> a.adId() + ":" + a.result() + ":" + a.price()).containsExactly(
                "2:WON:410", "3:WON:344", "1:LOST:null",
                "4:REJECTED:BELOW_RESERVE:null", "5:REJECTED:BUDGET_EXHAUSTED:null");

        ServingService.ServedItem firstAd = r.items().get(1);
        ImpressionTokens.Claims claims = tokens.verify(firstAd.token()).orElseThrow();
        assertThat(claims.adId()).isEqualTo(2);
        assertThat(claims.cpc()).isEqualTo(410);
        assertThat(claims.userId()).isEqualTo("first-visitor");
    }

    /*
     * 재현: 사용자가 runway 상품을 본 뒤(퍼스트파티 신호) 같은 목록을 엽니다. 개인화를 켜고 끕니다.
     * 증명: 개인화를 켜면 runway가 1등(760원), 끄면 처음 온 사용자와 같은 결과.
     */
    @Test
    void 최근_본_브랜드_신호를_서빙에_바로_반영하면_1등이_바뀐다() {
        rest.postForEntity("/signals/viewed-brand?userId=fan&brand=runway", null, Void.class);

        ServingService.ServeResult on = get("userId=fan");
        ServingService.ServeResult off = get("userId=fan&personalize=false");

        assertThat(on.recentBrand()).isEqualTo("runway");
        assertThat(on.auction().get(0).adId()).isEqualTo(1);
        assertThat(on.auction().get(0).price()).isEqualTo(760);
        assertThat(off.auction().get(0).adId()).isEqualTo(2);
    }

    /*
     * 재현: 같은 사용자가 지난 1시간에 streetfit 광고를 이미 3번 봤습니다(상한 3).
     * 증명: streetfit은 이 사용자에게 더 나가지 않고, 그 자리를 다음 광고들이 채웁니다.
     *       mono가 1등, runway가 2등이 되며 runway는 아래 순위가 없어 최저가 100원만 냅니다.
     */
    @Test
    void 피로도_상한을_넘은_광고는_같은_사용자에게_다시_나가지_않는다() {
        redis.opsForValue().set("ads:freq:tired:2", "3");

        ServingService.ServeResult r = get("userId=tired");

        assertThat(r.auction()).extracting(a -> a.adId() + ":" + a.result() + ":" + a.price()).contains(
                "3:WON:344", "1:WON:100", "2:REJECTED:FREQUENCY_CAP:null");
    }

    /*
     * 재현: 과금 컨슈머가 streetfit 예산 소진을 Redis에 표시했고, 스냅샷이 새로 고쳐졌습니다.
     * 증명: 소진 표시된 광고는 후보에서 빠집니다. (스냅샷 주기만큼은 늦게 반영된다는 점이 4단계 초과 집행 시나리오의 씨앗)
     */
    @Test
    void 소진_표시된_광고는_스냅샷이_새로_고쳐지면_빠진다() {
        redis.opsForSet().add(Catalog.EXHAUSTED_KEY, "2");
        assertThat(get("userId=u1").auction().get(0).adId()).isEqualTo(2); // 새로 고치기 전: 아직 나감

        catalog.refresh();

        assertThat(get("userId=u1").auction()).extracting(a -> a.adId() + ":" + a.result())
                .contains("2:REJECTED:BUDGET_EXHAUSTED");
    }

    /*
     * 재현: 광고 저장소가 80ms 느려졌습니다(장애 주입). 시간 예산은 50ms.
     * 증명: 페이지는 기다리지 않고 일반 상품 8개로 응답하고, 그 요청의 광고 자리는 못 채운 것으로 셉니다.
     */
    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.BEFORE_METHOD) // 채움률 카운터를 0부터 세기 위해 새 컨텍스트
    void 광고_조회가_50ms를_넘으면_일반_상품만으로_응답하고_채움률이_떨어진다() {
        get("userId=u2");
        ServingService.ServeResult slow = get("userId=u2&debugDelayMs=80");

        assertThat(slow.outcome()).isEqualTo(ServingService.Outcome.TIMEOUT);
        assertThat(slow.items()).hasSize(8).allMatch(i -> "ORGANIC".equals(i.type()));
        assertThat(slow.elapsedMs()).isLessThan(80);

        ServingService.Stats stats = rest.getForObject("/serving/stats", ServingService.Stats.class);
        assertThat(stats.requestedSlots()).isEqualTo(4);
        assertThat(stats.filledSlots()).isEqualTo(2);
        assertThat(stats.fillRate()).isEqualTo(0.5);
    }

    /*
     * 재현: 저장소가 계속 느립니다. 3번 연속 시간 초과.
     * 증명: 4번째 요청부터는 서킷이 열려 광고 조회 자체를 하지 않고(CIRCUIT_OPEN) 바로 응답합니다.
     *       느린 저장소를 기다리느라 매 페이지가 50ms씩 늦어지는 것을 막습니다.
     */
    @Test
    @DirtiesContext // 열린 서킷이 다음 테스트에 남지 않게
    void 연속으로_시간을_넘기면_서킷이_열려_광고_조회를_건너뛴다() {
        for (int i = 0; i < 3; i++) {
            assertThat(get("userId=u3&debugDelayMs=80").outcome()).isEqualTo(ServingService.Outcome.TIMEOUT);
        }

        ServingService.ServeResult skipped = get("userId=u3");

        assertThat(skipped.outcome()).isEqualTo(ServingService.Outcome.CIRCUIT_OPEN);
        assertThat(skipped.elapsedMs()).isLessThan(20);
    }
}
