package ads.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 시드 데이터(V2__seed.sql)와 같은 숫자로 랭킹과 2등가 옥션을 DB 없이 확인합니다.
 * 최저가 100원, 최소 단위 10원, 광고 자리 2개.
 */
class AuctionTest {

    static Ad ad(long id, String brand, int bid, double pctr, double weight) {
        return new Ad(id, 100 + id, "상품" + id, brand, "shoes", 10000, bid, pctr, weight, 100000, 0);
    }

    static final Ad RUNWAY = ad(1, "runway", 1000, 0.010, 1.0);    // eCPM 10,000
    static final Ad STREETFIT = ad(2, "streetfit", 500, 0.030, 1.0); // eCPM 15,000
    static final Ad MONO = ad(3, "mono", 400, 0.020, 1.5);         // eCPM 8,000 × 1.5 = 12,000

    static List<Auction.Entry> run(List<Ad> ads, String recentBrand, boolean personalize) {
        List<Auction.Bidder> bidders = ads.stream()
                .map(a -> new Auction.Bidder(a, Prediction.pctr(a, recentBrand, personalize, 2.0)))
                .toList();
        return Auction.run(bidders, 2, 100, 10);
    }

    /*
     * 재현: 입찰가 1000원(1등)인 runway와 500원인 streetfit, 400원인 mono가 경쟁합니다.
     * 증명: 랭킹은 입찰가가 아니라 점수(입찰가 × pCTR × 1000 × 가중치) 순이라 입찰가 1등 runway가 집니다.
     */
    @Test
    void 입찰가가_가장_높은_광고가_pCTR이_낮아서_진다() {
        List<Auction.Entry> result = run(List.of(RUNWAY, STREETFIT, MONO), null, false);

        assertThat(result).extracting(e -> e.ad().brand()).containsExactly("streetfit", "mono", "runway");
        assertThat(result.get(2).won()).isFalse();
    }

    /*
     * 재현: 같은 경매에서 낙찰받은 광고가 실제로 내는 클릭당 가격.
     * 증명: 2등가는 "내 순위를 지킬 최소 금액 + 10원"이라 입찰가보다 낮습니다.
     *       streetfit: 다음 순위 점수 12,000 ÷ (0.03 × 1000) = 400 → 410원 (입찰가 500원)
     *       mono:      다음 순위 점수 10,000 ÷ (0.02 × 1000 × 1.5) = 333.3 → 334 + 10 = 344원 (입찰가 400원)
     */
    @Test
    void 낙찰_CPC는_다음_순위를_이길_최소_금액이라_입찰가보다_낮다() {
        List<Auction.Entry> result = run(List.of(RUNWAY, STREETFIT, MONO), null, false);

        assertThat(result.get(0).price()).isEqualTo(410);
        assertThat(result.get(1).price()).isEqualTo(344);
    }

    /*
     * 재현: mono의 플랫폼 가중치(마진, 프로모션)를 1.5에서 1.0으로 내립니다.
     * 증명: 가중치 1.0이면 mono 점수는 8,000으로 runway(10,000)보다 낮아 낙찰을 못 받습니다.
     *       다목적 랭킹에서는 플랫폼이 정한 가중치가 순위를 바꿉니다.
     */
    @Test
    void 플랫폼_가중치를_내리면_순위가_바뀐다() {
        Ad monoWithoutPromotion = ad(3, "mono", 400, 0.020, 1.0);

        List<Auction.Entry> result = run(List.of(RUNWAY, STREETFIT, monoWithoutPromotion), null, false);

        assertThat(result).extracting(e -> e.ad().brand()).containsExactly("streetfit", "runway", "mono");
    }

    /*
     * 재현: 사용자가 최근 runway 상품을 봤습니다(퍼스트파티 신호). 개인화를 켜고 끄며 비교합니다.
     * 증명: 개인화를 켜면 runway pCTR이 0.01 → 0.02가 되어 점수 20,000으로 1등.
     *       같은 사용자, 같은 페이지라도 서빙 때 반영한 데이터에 따라 1등이 바뀝니다.
     */
    @Test
    void 최근_본_브랜드로_개인화하면_1등이_바뀐다() {
        List<Auction.Entry> off = run(List.of(RUNWAY, STREETFIT, MONO), "runway", false);
        List<Auction.Entry> on = run(List.of(RUNWAY, STREETFIT, MONO), "runway", true);

        assertThat(off.get(0).ad().brand()).isEqualTo("streetfit");
        assertThat(on.get(0).ad().brand()).isEqualTo("runway");
        assertThat(on.get(0).price()).isEqualTo(760); // 15,000 ÷ (0.02 × 1000) + 10
    }

    /*
     * 재현: 경쟁자가 하나뿐이라 낙찰 광고 아래에 다음 순위가 없습니다.
     * 증명: 다음 순위가 없으면 최저가(100원)를 냅니다. 입찰가 1000원을 걸었어도 경쟁이 없으면 싸게 삽니다.
     */
    @Test
    void 다음_순위가_없으면_최저가를_낸다() {
        List<Auction.Entry> result = run(List.of(RUNWAY), null, false);

        assertThat(result.get(0).price()).isEqualTo(100);
    }
}
