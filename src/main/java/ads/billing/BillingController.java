package ads.billing;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 과금 결과 보기.
 *
 *   GET /billing/stats   광고별 예산, 쓴 돈(ads.spent), 장부의 과금 합계, 결과별 클릭 수, 재전송 수
 *
 * spent와 장부 합계는 늘 같아야 합니다(한 트랜잭션에서 함께 바뀜). 다르면 반쪽 상태가 생긴 것이고, 5단계 대사가 이것을 잡습니다.
 */
@RestController
public class BillingController {

    private final JdbcTemplate jdbc;

    public BillingController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/billing/stats")
    public List<Map<String, Object>> stats() {
        return jdbc.queryForList("""
                SELECT a.id AS ad_id, a.budget, a.spent,
                       COALESCE(SUM(l.amount), 0)                                    AS ledger_charged,
                       COUNT(l.click_id) FILTER (WHERE l.result = 'CHARGED')          AS charged,
                       COUNT(l.click_id) FILTER (WHERE l.result = 'INVALID_CLICK')    AS invalid_click,
                       COUNT(l.click_id) FILTER (WHERE l.result = 'BUDGET_EXHAUSTED') AS budget_exhausted,
                       (SELECT COUNT(*) FROM duplicate_clicks d WHERE d.ad_id = a.id)  AS duplicate
                  FROM ads a LEFT JOIN click_ledger l ON l.ad_id = a.id
                 GROUP BY a.id, a.budget, a.spent
                 ORDER BY a.id
                """);
    }
}
