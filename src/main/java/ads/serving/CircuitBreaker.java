package ads.serving;

/**
 * 서킷 브레이커: 광고 조회가 연속으로 실패(시간 초과, 오류)하면 한동안 광고 조회 자체를 건너뜁니다.
 *
 * 지키는 규칙
 * - CLOSED(정상): 광고를 조회합니다. 연속 실패가 threshold번이 되면 OPEN.
 * - OPEN(차단): openMillis 동안 광고를 조회하지 않고 바로 일반 상품만 응답합니다.
 * - HALF_OPEN(시험): 차단 시간이 지나면 한 번만 조회해 봅니다. 성공하면 CLOSED, 실패하면 다시 OPEN.
 *
 * 왜 이 방식인지
 * - 광고 저장소가 느려진 상태에서 요청마다 50ms를 꽉 채워 기다리면, 페이지가 매번 50ms씩 늦어지고
 *   느린 저장소에 요청이 계속 쌓여 회복도 늦어집니다. 어차피 실패할 조회는 아예 하지 않는 편이 낫습니다.
 * - 현재 시각을 인자로 받아 시간을 흉내 낼 수 있게 했습니다(CircuitBreakerTest).
 *
 * 이 규칙이 깨지면
 * - 차단이 없으면 장애 동안 모든 페이지가 타임아웃만큼 느려집니다.
 * - 차단만 있고 시험(HALF_OPEN)이 없으면 저장소가 회복돼도 광고가 돌아오지 않아 채움률 0%, 매출 0이 이어집니다.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openMillis;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAt = 0;

    public CircuitBreaker(int failureThreshold, long openMillis) {
        this.failureThreshold = failureThreshold;
        this.openMillis = openMillis;
    }

    public synchronized boolean allowRequest(long nowMillis) {
        if (state == State.OPEN && nowMillis - openedAt >= openMillis) {
            state = State.HALF_OPEN;
            return true;
        }
        return state == State.CLOSED;
    }

    public synchronized void onSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void onFailure(long nowMillis) {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = nowMillis;
        }
    }

    public synchronized State state() {
        return state;
    }
}
