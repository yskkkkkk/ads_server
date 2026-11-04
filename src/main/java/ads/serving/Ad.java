package ads.serving;

/** 광고 한 건. 서빙은 DB가 아니라 메모리의 카탈로그 스냅샷에서 이 값을 읽습니다(Catalog). */
public record Ad(
        long id,
        long productId,
        String productName,
        String brand,
        String category,
        int price,
        int bid,
        double basePctr,
        double platformWeight,
        int budget,
        int spent) {
}
