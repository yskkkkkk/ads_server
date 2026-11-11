package ads.reconcile;

/**
 * 대사 판정: 광고 하나의 숫자들이 서로 맞는지 봅니다.
 *
 * 지키는 규칙
 * - 돈: 장부 과금 합계 = 광고의 쓴 돈(ads.spent). 다르면 MISMATCH. 한 트랜잭션에서 함께 바뀌므로 절대 달라서는 안 됩니다.
 * - 건수: 수집한 클릭 이벤트 수 = 장부 줄 수(과금 + 무과금 사유별) + 재전송 수. 다르면 아직 한쪽이 처리하지 못한 것(LAGGING).
 *   양수면 과금 컨슈머가 밀린 것, 음수면 집계 컨슈머가 밀린 것입니다.
 * - 둘 다 맞으면 OK.
 *
 * 왜 이 방식인지
 * - 대사는 "이벤트 수 = 과금 수 = 정산 금액"을 서로 다른 출처(원본 이벤트, 장부, 예산)로 맞춰 보는 일입니다(CLAUDE.md 3-9).
 *   같은 출처끼리 비교하면 늘 맞아서 의미가 없습니다.
 * - 건수 차이와 돈 차이를 나눕니다. 건수 차이는 비동기 처리라 잠깐 생길 수 있지만, 돈 차이는 반쪽 상태가 생겼다는 뜻이라 급이 다릅니다.
 *
 * 이 규칙이 깨지면
 * - 돈 차이를 "잠깐 밀린 것"으로 넘기면 광고주에게 장부와 다른 금액을 청구하게 됩니다.
 */
public final class ReconcileCheck {

    public enum Status { OK, LAGGING, MISMATCH }

    private ReconcileCheck() {
    }

    public static Status status(long clickEvents, long ledgerRows, long duplicates, long ledgerCharged, long spent) {
        if (ledgerCharged != spent) {
            return Status.MISMATCH;
        }
        if (clickEvents != ledgerRows + duplicates) {
            return Status.LAGGING;
        }
        return Status.OK;
    }
}
