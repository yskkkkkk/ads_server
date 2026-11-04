package ads.serving;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 서빙 ⑥ 응답 직전, 블렌딩: 일반 상품 목록 사이에 광고를 섞어 한 목록으로 만듭니다.
 *
 * 지키는 규칙
 * - 광고는 정해진 자리(예: 2번째, 6번째)에만 들어갑니다. 1등 광고가 앞자리, 2등 광고가 뒷자리입니다.
 * - 광고 상품과 같은 상품이 일반 목록에도 있으면 일반 쪽을 뺍니다. 한 화면에 같은 상품이 두 번 나오지 않습니다.
 * - 광고가 없으면 일반 상품만으로 채웁니다. 페이지는 광고가 없어도 완성되어야 합니다.
 *
 * 왜 이 방식인지
 * - Sponsored Products는 일반 상품과 같은 모양으로 섞여 나오는 광고입니다. 섞는 규칙(자리, 중복 제거)은
 *   외부 광고 솔루션이 정하기 어렵고, 자체 서빙으로 가는 이유 중 하나입니다(CLAUDE.md 3-12).
 * - 중복 제거는 광고 쪽이 아니라 일반 쪽을 뺍니다. 광고 자리는 이미 경매로 팔린 자리라서 비울 수 없습니다.
 *
 * 이 규칙이 깨지면
 * - 같은 상품이 두 번 보이면 화면 한 칸을 낭비하고, 사용자에게는 광고가 반복되는 느낌(광고 피로도)을 줍니다.
 */
public final class Blender {

    public record Slot(int position, Product product, Auction.Entry ad) {
        public boolean isAd() {
            return ad != null;
        }
    }

    private Blender() {
    }

    /**
     * @param organic     인기순 일반 상품(충분히 길게)
     * @param winners     낙찰 광고(순위 순)
     * @param adPositions 광고 자리(1부터 셈, 오름차순)
     * @param size        최종 목록 길이
     */
    public static List<Slot> blend(List<Product> organic, List<Auction.Entry> winners, List<Integer> adPositions, int size) {
        Set<Long> adProductIds = new HashSet<>();
        winners.forEach(w -> adProductIds.add(w.ad().productId()));

        List<Product> organicLeft = new ArrayList<>(organic.stream()
                .filter(p -> !adProductIds.contains(p.id()))
                .toList());

        List<Slot> slots = new ArrayList<>();
        int adIndex = 0;
        for (int position = 1; position <= size; position++) {
            boolean adSlot = adPositions.contains(position) && adIndex < winners.size();
            if (adSlot) {
                Auction.Entry ad = winners.get(adIndex++);
                Product product = new Product(ad.ad().productId(), ad.ad().productName(), ad.ad().brand(),
                        ad.ad().category(), ad.ad().price());
                slots.add(new Slot(position, product, ad));
            } else if (!organicLeft.isEmpty()) {
                slots.add(new Slot(position, organicLeft.remove(0), null));
            }
        }
        return slots;
    }
}
