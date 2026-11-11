package ads.reconcile;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5단계 리포트 API. 화면은 /demo/report.html.
 *
 *   GET /reports/reconciliation   광고별 대사(OK / LAGGING / MISMATCH)와 수집 단계 GIVT 건수
 *   GET /reports/performance      광고별 노출, CTR 두 기준, 광고비, 전환, ROAS(실제, 추정)
 */
@RestController
public class ReportController {

    private final Reports reports;

    public ReportController(Reports reports) {
        this.reports = reports;
    }

    @GetMapping("/reports/reconciliation")
    public Map<String, Object> reconciliation() {
        return reports.reconciliation();
    }

    @GetMapping("/reports/performance")
    public List<Map<String, Object>> performance() {
        return reports.performance();
    }
}
