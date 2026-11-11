package ads.billing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 클릭 과금 판정(CLAUDE.md 3-11). 클릭 한 건을 과금할지, 어떤 사유로 무과금할지 정합니다.
 *
 * 지키는 규칙
 * - 검사 순서: ① 재전송(이미 결과를 확정한 clickId) → ② 부정 클릭(같은 사용자 × 광고, 마지막 과금으로부터 10초 미만)
 *   → ③ 예산 부족(잔액 < cpc) → 과금.
 * - 경계값: 정확히 10초 차이면 과금, 잔액 = cpc면 과금.
 * - 시간 판단은 서버가 받은 시각이 아니라 클릭이 일어난 시각(이벤트 시간).
 * - 마지막 과금 시각은 과금했을 때만 갱신합니다. 무시된 클릭으로 갱신하면 10초 창이 밀려 정상 클릭이 무시됩니다.
 * - 판정(decide)과 상태 반영(apply)을 나눕니다. 컨슈머는 decide → DB 커밋 → apply 순서로 부릅니다.
 *   커밋에 실패하면 apply를 부르지 않아 메모리가 장부와 어긋나지 않습니다.
 *
 * 왜 이 순서와 방식인지
 * - 재전송을 맨 먼저 거르는 이유: 같은 클릭이 두 번 와서 두 번째가 "10초 미만"이나 "예산 부족"으로 판정되면
 *   무과금 사유가 틀리게 남습니다. 같은 클릭은 같은 결과여야 합니다.
 * - 스프링, DB, Redis를 모르는 순수 클래스라 CLAUDE.md 4장 기본 예시(c1~c7)를 그대로 단위 테스트로 돌립니다(ClickBillingTest).
 * - 이 상태(State)는 컨슈머 하나가 자기 파티션의 광고들에 대해서만 들고 있습니다. 같은 광고는 늘 같은 파티션, 같은 컨슈머라
 *   락 없이도 한 광고의 예산을 한 곳에서만 깎습니다(CLAUDE.md 3-10).
 *
 * 이 규칙이 깨지면
 * - 같은 광고를 두 컨슈머가 각자 메모리 상태로 판정하면 예산과 10초 창이 둘로 갈라져 초과 집행, 부정 클릭 통과가 생깁니다
 *   (BillingConsumerTest의 대조 실험).
 * - 무과금 클릭에도 마지막 과금 시각을 갱신하면, 연타가 이어지는 동안 정상 클릭까지 계속 무시됩니다.
 */
public final class ClickBilling {

    public enum Result { CHARGED, DUPLICATE, INVALID_CLICK, BUDGET_EXHAUSTED }

    public static final long FRAUD_WINDOW_MILLIS = 10_000;

    public record Click(String clickId, long adId, String userId, int cpc, long eventTime) {
    }

    /** 컨슈머 하나의 메모리 상태. 재시작하면 장부에서 다시 만듭니다(BillingConsumer.restoreFromLedger). */
    public static final class State {
        final Set<String> settledClickIds = new HashSet<>();
        final Map<Long, Long> balances = new HashMap<>();
        final Map<String, Long> lastChargedAt = new HashMap<>();

        public void setBalance(long adId, long balance) {
            balances.put(adId, balance);
        }

        public void setLastChargedAt(String userId, long adId, long eventTime) {
            lastChargedAt.put(key(userId, adId), eventTime);
        }

        public void markSettled(String clickId) {
            settledClickIds.add(clickId);
        }

        public Long balance(long adId) {
            return balances.get(adId);
        }

        public boolean knowsAd(long adId) {
            return balances.containsKey(adId);
        }
    }

    private ClickBilling() {
    }

    /**
     * @param alreadySettled 이 clickId의 결과가 이미 장부에 있나(컨슈머는 DB로 확인, 단위 테스트는 state로 확인)
     */
    public static Result decide(Click click, boolean alreadySettled, State state) {
        if (alreadySettled || state.settledClickIds.contains(click.clickId())) {
            return Result.DUPLICATE;
        }
        Long last = state.lastChargedAt.get(key(click.userId(), click.adId()));
        if (last != null && click.eventTime() - last < FRAUD_WINDOW_MILLIS) {
            return Result.INVALID_CLICK;
        }
        if (state.balances.getOrDefault(click.adId(), 0L) < click.cpc()) {
            return Result.BUDGET_EXHAUSTED;
        }
        return Result.CHARGED;
    }

    /** 판정 결과를 메모리 상태에 반영합니다. 컨슈머는 DB 커밋이 끝난 뒤에만 부릅니다. */
    public static void apply(Click click, Result result, State state) {
        if (result == Result.DUPLICATE) {
            return;
        }
        state.settledClickIds.add(click.clickId());
        if (result == Result.CHARGED) {
            state.balances.merge(click.adId(), (long) -click.cpc(), Long::sum);
            state.lastChargedAt.put(key(click.userId(), click.adId()), click.eventTime());
        }
    }

    private static String key(String userId, long adId) {
        return userId + "|" + adId;
    }
}
