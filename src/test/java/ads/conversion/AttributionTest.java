package ads.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AttributionTest {

    static final long HOUR = 60 * 60 * 1000L;
    static final long ORDER_TIME = 1_000 * HOUR;

    static Attribution.Click click(String impressionId, long adId, long productId, long clickedAt) {
        return new Attribution.Click(impressionId, adId, productId, "c-" + impressionId, clickedAt);
    }

    /*
     * 재현: 같은 상품 광고를 이틀 전, 한 시간 전 두 번 눌렀습니다. URL에는 이틀 전 클릭의 토큰이 남아 있습니다.
     * 증명: 마지막 클릭 기준이라 한 시간 전 클릭에 귀속되고, 그 클릭은 URL 토큰의 것이 아니라 USER_HISTORY 경로입니다.
     */
    @Test
    void 마지막_클릭에_귀속한다() {
        List<Attribution.Click> clicks = List.of(
                click("old", 2, 102, ORDER_TIME - 48 * HOUR),
                click("recent", 2, 102, ORDER_TIME - HOUR));

        Attribution.Result r = Attribution.attribute(102, ORDER_TIME, "old", true, clicks);

        assertThat(r.click().impressionId()).isEqualTo("recent");
        assertThat(r.by()).isEqualTo(Attribution.By.USER_HISTORY);
    }

    /*
     * 재현: 클릭이 구매보다 정확히 7일 전 / 7일 + 1ms 전.
     * 증명: 윈도우 경계는 포함(7일이면 귀속), 1ms라도 넘으면 OUTSIDE_WINDOW.
     */
    @Test
    void 정확히_7일이면_귀속하고_1ms라도_넘으면_귀속하지_않는다() {
        Attribution.Result atEdge = Attribution.attribute(102, ORDER_TIME, "i1", true,
                List.of(click("i1", 2, 102, ORDER_TIME - Attribution.WINDOW_MILLIS)));
        Attribution.Result beyond = Attribution.attribute(102, ORDER_TIME, "i1", true,
                List.of(click("i1", 2, 102, ORDER_TIME - Attribution.WINDOW_MILLIS - 1)));

        assertThat(atEdge.reason()).isEqualTo(Attribution.Reason.ATTRIBUTED);
        assertThat(atEdge.by()).isEqualTo(Attribution.By.TOKEN);
        assertThat(beyond.reason()).isEqualTo(Attribution.Reason.OUTSIDE_WINDOW);
    }

    /*
     * 재현: 구매한 뒤에 같은 상품 광고를 눌렀습니다(클릭 시각이 구매보다 늦음).
     * 증명: 원인(클릭)이 결과(구매)보다 뒤일 수 없어 귀속하지 않습니다.
     */
    @Test
    void 구매_뒤의_클릭에는_귀속하지_않는다() {
        Attribution.Result r = Attribution.attribute(102, ORDER_TIME, null, true,
                List.of(click("later", 2, 102, ORDER_TIME + 1)));

        assertThat(r.reason()).isEqualTo(Attribution.Reason.NO_CLICK);
    }

    /*
     * 재현: 토큰도 로그인도 없는 구매.
     * 증명: 클릭 기록이 있어도 누구의 것인지 알 수 없어 NO_IDENTITY.
     */
    @Test
    void 구매자를_모르면_귀속하지_않는다() {
        Attribution.Result r = Attribution.attribute(102, ORDER_TIME, null, false,
                List.of(click("i1", 2, 102, ORDER_TIME - HOUR)));

        assertThat(r.by()).isEqualTo(Attribution.By.NONE);
        assertThat(r.reason()).isEqualTo(Attribution.Reason.NO_IDENTITY);
    }
}
