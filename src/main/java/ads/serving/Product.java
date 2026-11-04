package ads.serving;

/** 일반 상품(오가닉 피드의 한 칸). */
public record Product(long id, String name, String brand, String category, int price) {
}
