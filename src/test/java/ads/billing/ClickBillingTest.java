package ads.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ads.billing.ClickBilling.Click;
import ads.billing.ClickBilling.Result;

/** 3-11 과금 규칙을 DB 없이 숫자로 확인합니다. 광고 A = 1, B = 2. 시각은 초 단위로 적고 ms로 바꿉니다. */
class ClickBillingTest {

    static Click click(String id, long ad, String user, int cpc, long seconds) {
        return new Click(id, ad, user, cpc, seconds * 1000);
    }

    static ClickBilling.State budgets(long a, long b) {
        ClickBilling.State s = new ClickBilling.State();
        s.setBalance(1, a);
        s.setBalance(2, b);
        return s;
    }

    static List<Result> run(ClickBilling.State s, Click... clicks) {
        List<Result> results = new ArrayList<>();
        for (Click c : clicks) {
            Result r = ClickBilling.decide(c, false, s);
            ClickBilling.apply(c, r, s);
            results.add(r);
        }
        return results;
    }

    /*
     * 재현: CLAUDE.md 4장 기본 예시 그대로. 예산 A=1000, B=300 / cpc A=200, B=150.
     * 증명: 재전송(c1 두 번째), 10초 미만(c2), 잔액 = cpc(c6), 예산 부족(c7)이 표대로 판정되고 결과는 A=400, B=0.
     *       c2가 무시될 때 u1의 마지막 과금 시각(0초)이 그대로라 c4(12초)는 과금됩니다.
     */
    @Test
    void 기본_예시_c1부터_c7까지_표대로_판정한다() {
        ClickBilling.State s = budgets(1000, 300);

        List<Result> results = run(s,
                click("c1", 1, "u1", 200, 0),
                click("c1", 1, "u1", 200, 1),
                click("c2", 1, "u1", 200, 5),
                click("c3", 1, "u2", 200, 6),
                click("c4", 1, "u1", 200, 12),
                click("c5", 2, "u3", 150, 20),
                click("c6", 2, "u4", 150, 21),
                click("c7", 2, "u5", 150, 22));

        assertThat(results).containsExactly(
                Result.CHARGED, Result.DUPLICATE, Result.INVALID_CLICK, Result.CHARGED,
                Result.CHARGED, Result.CHARGED, Result.CHARGED, Result.BUDGET_EXHAUSTED);
        assertThat(s.balance(1)).isEqualTo(400);
        assertThat(s.balance(2)).isEqualTo(0);
    }

    /*
     * 재현: 같은 클릭(clickId 같음)이 네트워크 재시도로 3번 옵니다.
     * 증명: 과금은 1번, 나머지는 DUPLICATE. 잔액은 한 번만 줄어듭니다.
     */
    @Test
    void 같은_클릭이_3번_재전송돼도_과금은_1번() {
        ClickBilling.State s = budgets(1000, 300);

        List<Result> results = run(s, click("c1", 1, "u1", 200, 0), click("c1", 1, "u1", 200, 1), click("c1", 1, "u1", 200, 2));

        assertThat(results).containsExactly(Result.CHARGED, Result.DUPLICATE, Result.DUPLICATE);
        assertThat(s.balance(1)).isEqualTo(800);
    }

    /*
     * 재현: 같은 사용자가 같은 광고를 5초 뒤, 그리고 (처음부터) 정확히 10초 뒤에 다시 누릅니다.
     * 증명: 5초 뒤는 INVALID_CLICK, 10초 뒤는 과금(경계값 포함). 5초 뒤 클릭이 마지막 과금 시각을 바꾸지 않았기 때문에
     *       10초 뒤 클릭이 과금됩니다. 만약 무시된 클릭으로 시각을 갱신했다면 10초 뒤 클릭도 5초 차이로 무시됐을 것입니다.
     */
    @Test
    void 재클릭이_5초_뒤면_무과금_정확히_10초_뒤면_과금() {
        ClickBilling.State s = budgets(1000, 300);

        List<Result> results = run(s, click("a", 1, "u1", 200, 0), click("b", 1, "u1", 200, 5), click("c", 1, "u1", 200, 10));

        assertThat(results).containsExactly(Result.CHARGED, Result.INVALID_CLICK, Result.CHARGED);
    }

    /*
     * 재현: 잔액이 cpc와 같을 때, 그다음 cpc보다 적을 때.
     * 증명: 같으면 과금(잔액 0), 적으면 BUDGET_EXHAUSTED.
     */
    @Test
    void 잔액과_cpc가_같으면_과금_적으면_무과금() {
        ClickBilling.State s = budgets(1000, 150);

        List<Result> results = run(s, click("x", 2, "u1", 150, 0), click("y", 2, "u2", 150, 1));

        assertThat(results).containsExactly(Result.CHARGED, Result.BUDGET_EXHAUSTED);
        assertThat(s.balance(2)).isZero();
    }
}
