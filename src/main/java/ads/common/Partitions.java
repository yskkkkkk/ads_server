package ads.common;

/**
 * 이벤트 스트림 파티션 번호 계산. Kafka 파티션을 Redis Streams 4개로 흉내 냅니다.
 *
 * 지키는 규칙
 * - 파티션 번호 = hash(adId) % 4. 같은 광고의 이벤트는 언제나 같은 스트림(ads:events:{번호})에 들어갑니다.
 *
 * 왜 이 방식인지
 * - 4단계 과금 컨슈머는 파티션 하나를 컨슈머 하나만 읽습니다. 같은 광고가 늘 같은 파티션이면
 *   "한 광고는 한 곳에서만 처리"되어 락 없이 예산 차감 순서와 단일 처리를 얻습니다(CLAUDE.md 3-10).
 * - 파티션 키를 adId로 고른 이유: 과금에서 지켜야 할 상태(예산)가 광고 단위이기 때문입니다.
 *
 * 이 규칙이 깨지면
 * - 같은 광고가 여러 파티션에 흩어지면 컨슈머 여러 개가 같은 예산을 동시에 깎아 초과 집행, 이중 과금이 생깁니다.
 * - 파티션 수(4)를 나중에 바꾸면 같은 광고가 다른 파티션으로 가서 순서가 깨집니다. 그래서 상수로 고정합니다.
 */
public final class Partitions {

    public static final int COUNT = 4;

    private Partitions() {
    }

    public static int of(long adId) {
        return Math.floorMod(Long.hashCode(adId), COUNT);
    }

    /** 광고 ID로 스트림 키를 고릅니다. (파티션 번호로 고르는 streamKey(int)와 헷갈리지 않게 이름을 나눴습니다.) */
    public static String streamKeyOfAd(long adId) {
        return streamKey(of(adId));
    }

    public static String streamKey(int partition) {
        return "ads:events:" + partition;
    }
}
