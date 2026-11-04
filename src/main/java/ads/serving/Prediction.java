package ads.serving;

/**
 * 서빙 ③ 예측: 이 사용자가 이 광고를 클릭할 확률(pCTR)을 정합니다.
 *
 * 지키는 규칙
 * - pCTR = 광고별 기본값 × 사용자 신호 보정. 사용자가 최근 본 브랜드와 광고 상품의 브랜드가 같으면 boost배.
 * - 개인화를 끄면(personalize=false) 기본값 그대로입니다. 같은 요청을 켜고 끄며 랭킹이 어떻게 바뀌는지 비교합니다.
 *
 * 왜 이 방식인지
 * - 실제 서비스에서는 머신러닝 모델이 수백 개 피처로 계산합니다. 이 저장소의 목표는 모델이 아니라
 *   "예측값이 랭킹과 가격을 어떻게 바꾸나"와 "퍼스트파티 데이터(최근 본 브랜드)를 지연 없이 반영하면 무엇이 달라지나"입니다.
 *   그래서 규칙 하나로 대신합니다(CLAUDE.md 3-12).
 * - 신호를 카테고리가 아니라 브랜드로 잡은 이유: 카테고리는 이미 페이지 맥락(어떤 목록을 보고 있나)으로 후보를 거를 때 써서,
 *   같은 카테고리 안의 광고끼리는 차이를 만들지 못합니다(docs/tradeoffs.md 7).
 *
 * 이 규칙이 깨지면
 * - pCTR이 실제보다 높게 잡힌 광고는 eCPM이 부풀려져 좋은 자리를 차지하지만 클릭이 안 나와, 플랫폼 매출(클릭 수 × CPC)이 줄어듭니다.
 */
public final class Prediction {

    private Prediction() {
    }

    public static double pctr(Ad ad, String recentBrand, boolean personalize, double boost) {
        if (personalize && ad.brand().equals(recentBrand)) {
            return ad.basePctr() * boost;
        }
        return ad.basePctr();
    }
}
