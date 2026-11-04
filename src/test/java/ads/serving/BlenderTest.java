package ads.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class BlenderTest {

    /*
     * 재현: 인기순 일반 목록 1~10번 상품, 낙찰 광고는 3번과 7번 상품(일반 목록에도 있음).
     * 증명: 광고는 2번째, 6번째 자리에 들어가고, 같은 상품은 일반 쪽에서 빠져 한 번만 보입니다.
     */
    @Test
    void 광고는_정해진_자리에_들어가고_같은_상품은_한_번만_보인다() {
        List<Product> organic = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> new Product(id, "p" + id, "b", "shoes", 1000)).toList();
        List<Auction.Entry> winners = List.of(entry(3), entry(7));

        List<Blender.Slot> slots = Blender.blend(organic, winners, List.of(2, 6), 8);

        assertThat(slots).extracting(s -> (s.isAd() ? "AD" : "") + s.product().id())
                .containsExactly("1", "AD3", "2", "4", "5", "AD7", "6", "8");
    }

    /*
     * 재현: 광고 고르기가 실패해 낙찰 광고가 없습니다.
     * 증명: 광고 자리도 일반 상품으로 채워져 페이지는 그대로 완성됩니다.
     */
    @Test
    void 광고가_없으면_일반_상품으로_모두_채운다() {
        List<Product> organic = LongStream.rangeClosed(1, 10)
                .mapToObj(id -> new Product(id, "p" + id, "b", "shoes", 1000)).toList();

        List<Blender.Slot> slots = Blender.blend(organic, List.of(), List.of(2, 6), 8);

        assertThat(slots).hasSize(8).noneMatch(Blender.Slot::isAd);
    }

    private static Auction.Entry entry(long productId) {
        Ad ad = new Ad(productId * 10, productId, "p" + productId, "b", "shoes", 1000, 500, 0.02, 1.0, 1000, 0);
        return new Auction.Entry(ad, 0.02, 10000, 10000, 1, 300);
    }
}
