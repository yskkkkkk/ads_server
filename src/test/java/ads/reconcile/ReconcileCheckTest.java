package ads.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReconcileCheckTest {

    /*
     * 재현: 클릭 이벤트 3 = 장부 2 + 재전송 1, 장부 합계 = 쓴 돈 / 장부가 하나 모자람 / 쓴 돈이 장부와 다름.
     * 증명: 다 맞으면 OK, 건수만 다르면 LAGGING(아직 처리 중), 돈이 다르면 건수와 상관없이 MISMATCH.
     */
    @Test
    void 돈이_다르면_MISMATCH_건수만_다르면_LAGGING() {
        assertThat(ReconcileCheck.status(3, 2, 1, 410, 410)).isEqualTo(ReconcileCheck.Status.OK);
        assertThat(ReconcileCheck.status(3, 1, 1, 410, 410)).isEqualTo(ReconcileCheck.Status.LAGGING);
        assertThat(ReconcileCheck.status(3, 2, 1, 410, 510)).isEqualTo(ReconcileCheck.Status.MISMATCH);
        assertThat(ReconcileCheck.status(3, 1, 1, 410, 510)).isEqualTo(ReconcileCheck.Status.MISMATCH);
    }
}
