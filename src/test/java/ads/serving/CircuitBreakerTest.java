package ads.serving;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CircuitBreakerTest {

    /*
     * 재현: 광고 조회가 3번 연속 실패하고, 10초 뒤 저장소가 회복됩니다.
     * 증명: 3번째 실패에 서킷이 열려 10초 동안 조회를 건너뛰고(CLOSED → OPEN),
     *       10초가 지나면 한 번 시험해 성공 시 다시 정상으로 돌아옵니다(HALF_OPEN → CLOSED).
     */
    @Test
    void 연속_실패하면_열리고_시간이_지나면_한_번_시험한_뒤_닫힌다() {
        CircuitBreaker breaker = new CircuitBreaker(3, 10_000);

        breaker.onFailure(0);
        breaker.onFailure(1);
        assertThat(breaker.allowRequest(2)).isTrue();
        breaker.onFailure(2);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest(5_000)).isFalse();

        assertThat(breaker.allowRequest(10_002)).isTrue();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        breaker.onSuccess();
        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    /*
     * 재현: 시험(HALF_OPEN) 요청도 실패합니다.
     * 증명: 한 번만 실패해도 바로 다시 열립니다. 아직 회복되지 않은 저장소에 요청을 쏟지 않습니다.
     */
    @Test
    void 시험_요청이_실패하면_바로_다시_열린다() {
        CircuitBreaker breaker = new CircuitBreaker(3, 10_000);
        breaker.onFailure(0);
        breaker.onFailure(0);
        breaker.onFailure(0);

        breaker.allowRequest(10_000);
        breaker.onFailure(10_000);

        assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest(15_000)).isFalse();
    }
}
