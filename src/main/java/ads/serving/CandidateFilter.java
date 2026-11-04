package ads.serving;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 서빙 ② 후보 추리기: 이번 요청에 나갈 자격이 없는 광고를 사유와 함께 뺍니다.
 *
 * 지키는 규칙
 * - 예산이 없는 광고(DB의 spent >= budget, 또는 과금 컨슈머가 Redis에 남긴 소진 표시)는 내보내지 않습니다.
 * - 입찰가가 최저가(reserve price)보다 낮으면 내보내지 않습니다. 이 아래로는 지면을 팔지 않는다는 매체의 바닥 가격입니다.
 * - 같은 사용자가 최근 1시간에 이미 상한만큼 본 광고는 내보내지 않습니다(광고 피로도, frequency cap).
 *
 * 왜 이 순서와 방식인지
 * - 거르는 일은 랭킹, 옥션보다 먼저 합니다. 나갈 수 없는 광고가 경매에 끼면 2등 가격 계산이 틀어집니다.
 * - 빠진 광고도 사유를 남겨 응답의 경매표(auction)에 보여줍니다. "왜 이 광고가 안 나갔나"를 설명할 수 있어야 합니다.
 * - 스프링, DB, Redis를 모르는 순수 함수입니다. 필요한 값(소진 표시, 노출 횟수)은 호출하는 쪽이 미리 읽어 넘깁니다.
 *
 * 이 규칙이 깨지면
 * - 소진 확인이 빠지면 예산이 없는 광고가 계속 나가 초과 집행이 생깁니다.
 * - 피로도 상한이 빠지면 같은 사용자에게 같은 광고가 반복되어 클릭률이 떨어지고 사용자 경험이 나빠집니다.
 */
public final class CandidateFilter {

    public enum Reason { BUDGET_EXHAUSTED, BELOW_RESERVE, FREQUENCY_CAP }

    public record Rejected(Ad ad, Reason reason) {
    }

    public record Result(List<Ad> passed, List<Rejected> rejected) {
    }

    private CandidateFilter() {
    }

    public static Result apply(List<Ad> ads,
                               Set<Long> exhaustedAdIds,
                               Map<Long, Integer> impressionsLastHour,
                               int reserveCpc,
                               int frequencyCapPerHour) {
        List<Ad> passed = new ArrayList<>();
        List<Rejected> rejected = new ArrayList<>();
        for (Ad ad : ads) {
            if (ad.spent() >= ad.budget() || exhaustedAdIds.contains(ad.id())) {
                rejected.add(new Rejected(ad, Reason.BUDGET_EXHAUSTED));
            } else if (ad.bid() < reserveCpc) {
                rejected.add(new Rejected(ad, Reason.BELOW_RESERVE));
            } else if (impressionsLastHour.getOrDefault(ad.id(), 0) >= frequencyCapPerHour) {
                rejected.add(new Rejected(ad, Reason.FREQUENCY_CAP));
            } else {
                passed.add(ad);
            }
        }
        return new Result(passed, rejected);
    }
}
