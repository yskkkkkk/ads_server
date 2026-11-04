package ads.serving;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class CandidateFilterTest {

    static Ad ad(long id, int bid, int budget, int spent) {
        return new Ad(id, 100 + id, "상품" + id, "b" + id, "shoes", 10000, bid, 0.02, 1.0, budget, spent);
    }

    /*
     * 재현: 예산 소진(DB), 소진 표시(Redis), 최저가 미달, 피로도 상한 초과 광고가 섞여 있습니다.
     * 증명: 네 가지 모두 경매 전에 빠지고, 빠진 사유가 남습니다.
     */
    @Test
    void 나갈_자격이_없는_광고는_경매_전에_사유와_함께_빠진다() {
        Ad ok = ad(1, 500, 1000, 0);
        Ad spentAll = ad(2, 500, 1000, 1000);
        Ad markedExhausted = ad(3, 500, 1000, 0);
        Ad cheap = ad(4, 80, 1000, 0);
        Ad seenTooOften = ad(5, 500, 1000, 0);

        CandidateFilter.Result result = CandidateFilter.apply(
                List.of(ok, spentAll, markedExhausted, cheap, seenTooOften),
                Set.of(3L), Map.of(5L, 3), 100, 3);

        assertThat(result.passed()).containsExactly(ok);
        assertThat(result.rejected()).extracting(r -> r.ad().id() + ":" + r.reason()).containsExactly(
                "2:BUDGET_EXHAUSTED", "3:BUDGET_EXHAUSTED", "4:BELOW_RESERVE", "5:FREQUENCY_CAP");
    }
}
