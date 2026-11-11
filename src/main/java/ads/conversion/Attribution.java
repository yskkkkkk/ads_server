package ads.conversion;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 어트리뷰션 규칙: 이 구매를 어느 광고 덕분으로 볼지 정합니다.
 *
 * 지키는 규칙
 * - 마지막 클릭 기준(last click): 구매한 상품의 광고를 누른 기록 중, 구매 시각 이전의 가장 마지막 클릭에 귀속합니다.
 * - 어트리뷰션 윈도우 7일: 구매 시각 − 클릭 시각이 7일 이하면 인정합니다(정확히 7일도 인정). 넘으면 귀속하지 않습니다.
 * - 같은 상품만 셉니다. 광고 상품이 아닌 다른 상품을 산 것은 귀속하지 않습니다.
 * - 구매자가 누구인지 알 수 없으면(토큰도 로그인도 없음) 클릭 기록을 찾을 수 없어 귀속하지 않습니다.
 * - 귀속 경로를 남깁니다. TOKEN: URL로 이어받은 토큰의 그 클릭 / USER_HISTORY: 토큰은 없거나 다른 클릭이지만
 *   로그인 사용자의 클릭 기록에서 찾음(클로즈드 루프) / NONE: 귀속 못 함과 그 사유.
 *
 * 왜 이 방식인지
 * - 마지막 클릭은 가장 흔하고 설명하기 쉬운 규칙입니다. 여러 광고에 나눠 주는 방식(멀티 터치)은 정답이 없어 규칙을 공개해야 합니다(CLAUDE.md 3-7).
 * - 윈도우가 없으면 몇 달 전 클릭에도 매출이 붙어 광고 성과(ROAS)가 부풀려집니다.
 * - 클릭 기록을 두 경로로 찾는 이유: 일반 웹 광고는 쿠키나 URL 토큰이 끊기면 끝이지만(파라미터 체이닝 단절),
 *   리테일 미디어는 로그인한 구매자를 알아서 자기 클릭 기록으로 다시 이을 수 있습니다. 이것이 클로즈드 루프입니다(CLAUDE.md 3-1).
 * - 스프링, Redis, DB를 모르는 순수 함수라 시간 경계를 숫자로 바로 확인합니다(AttributionTest).
 *
 * 이 규칙이 깨지면
 * - 윈도우 밖 클릭에 귀속하면 광고 덕분이 아닌 구매가 광고 매출로 잡혀 ROAS가 부풀고, 광고주는 효과 없는 광고에 돈을 더 씁니다.
 * - 구매 시각 이후의 클릭에 귀속하면 원인과 결과가 뒤집힙니다.
 */
public final class Attribution {

    public enum By { TOKEN, USER_HISTORY, NONE }

    public enum Reason { ATTRIBUTED, NO_IDENTITY, NO_CLICK, OUTSIDE_WINDOW }

    /** 클릭 기록 한 건. productId는 광고가 가리키는 상품입니다. */
    public record Click(String impressionId, long adId, long productId, String clickId, long clickedAt) {
    }

    public record Result(By by, Reason reason, Click click) {
    }

    public static final long WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000;

    private Attribution() {
    }

    /**
     * @param tokenImpressionId URL로 이어받은 토큰의 노출 ID. 토큰이 없으면 null
     * @param identityKnown     구매자를 알 수 있나(토큰이 있거나 로그인 사용자)
     * @param clicks            구매자의 클릭 기록
     */
    public static Result attribute(long productId, long orderedAt, String tokenImpressionId,
                                   boolean identityKnown, List<Click> clicks) {
        if (!identityKnown) {
            return new Result(By.NONE, Reason.NO_IDENTITY, null);
        }
        List<Click> sameProductBefore = clicks.stream()
                .filter(c -> c.productId() == productId && c.clickedAt() <= orderedAt)
                .toList();
        if (sameProductBefore.isEmpty()) {
            return new Result(By.NONE, Reason.NO_CLICK, null);
        }
        Optional<Click> last = sameProductBefore.stream()
                .filter(c -> orderedAt - c.clickedAt() <= WINDOW_MILLIS)
                .max(Comparator.comparingLong(Click::clickedAt));
        if (last.isEmpty()) {
            return new Result(By.NONE, Reason.OUTSIDE_WINDOW, null);
        }
        By by = last.get().impressionId().equals(tokenImpressionId) ? By.TOKEN : By.USER_HISTORY;
        return new Result(by, Reason.ATTRIBUTED, last.get());
    }
}
