package ads.reconcile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import ads.event.EventCollector;

/**
 * 5단계 리포트 두 가지.
 *
 * 대사(reconciliation): 광고별로 서로 다른 출처의 숫자를 맞춰 봅니다.
 *   원본 이벤트(event_log, 집계 컨슈머) ↔ 장부(click_ledger, duplicate_clicks, 과금 컨슈머) ↔ 예산(ads.spent)
 *   판정은 ReconcileCheck(OK / LAGGING / MISMATCH). 수집 단계에서 걸러진 GIVT 건수도 함께 보입니다.
 *
 * 성과(performance): 광고주가 보는 숫자입니다.
 *   렌더링 노출, viewable 노출, 클릭(재전송 제외), CTR 두 기준, 과금된 클릭, 실제 광고비(장부), 전환, 매출,
 *   실제 ROAS(장부 광고비 기준)와 3단계 추정 ROAS(과금 규칙 적용 전 광고비 기준).
 *
 * 왜 이 방식인지
 * - 숫자는 쌓을 때가 아니라 볼 때 SQL로 셉니다. 원본(event_log)과 장부가 있으면 언제든 다시 셀 수 있고, 세는 규칙을 바꿔도 됩니다.
 * - CTR을 두 기준으로 나란히 보여주는 이유: 같은 클릭 수라도 분모(노출)를 무엇으로 세느냐에 따라 CTR이 달라지고,
 *   그 CTR로 pCTR을 정하면 랭킹이 바뀝니다(CLAUDE.md 3-3 노출 유실의 영향).
 * - 추정 ROAS와 실제 ROAS를 나란히 두는 이유: 둘의 차이가 곧 "과금하지 않은 클릭"(부정 클릭, 예산 소진)의 크기입니다.
 */
@Service
public class Reports {

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;

    public Reports(JdbcTemplate jdbc, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.redis = redis;
    }

    public Map<String, Object> reconciliation() {
        List<Map<String, Object>> rows = new ArrayList<>();
        ReconcileCheck.Status overall = ReconcileCheck.Status.OK;
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id AS ad_id, a.budget, a.spent,
                       (SELECT COUNT(*) FROM event_log e WHERE e.ad_id = a.id AND e.type = 'CLICK')                AS click_events,
                       (SELECT COUNT(*) FROM click_ledger l WHERE l.ad_id = a.id)                                   AS ledger_rows,
                       (SELECT COUNT(*) FROM click_ledger l WHERE l.ad_id = a.id AND l.result = 'CHARGED')          AS charged,
                       (SELECT COUNT(*) FROM click_ledger l WHERE l.ad_id = a.id AND l.result = 'INVALID_CLICK')    AS invalid_click,
                       (SELECT COUNT(*) FROM click_ledger l WHERE l.ad_id = a.id AND l.result = 'BUDGET_EXHAUSTED') AS budget_exhausted,
                       (SELECT COUNT(*) FROM duplicate_clicks d WHERE d.ad_id = a.id)                               AS duplicate,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM click_ledger l WHERE l.ad_id = a.id)                 AS ledger_charged,
                       (SELECT COALESCE(SUM(l.cpc), 0) FROM click_ledger l WHERE l.ad_id = a.id AND l.result <> 'CHARGED') AS unbilled
                  FROM ads a ORDER BY a.id
                """)) {
            long clickEvents = num(r, "click_events");
            long ledgerRows = num(r, "ledger_rows");
            long duplicates = num(r, "duplicate");
            ReconcileCheck.Status status = ReconcileCheck.status(clickEvents, ledgerRows, duplicates,
                    num(r, "ledger_charged"), num(r, "spent"));
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("pending", clickEvents - ledgerRows - duplicates);
            row.put("status", status);
            rows.add(row);
            if (status.ordinal() > overall.ordinal()) {
                overall = status;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", overall);
        result.put("givtClicksFilteredAtCollection", counter("ads:count:click:givt"));
        result.put("ads", rows);
        return result;
    }

    public List<Map<String, Object>> performance() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT a.id AS ad_id, p.brand,
                       (SELECT COUNT(*) FROM event_log e WHERE e.ad_id = a.id AND e.type = 'RENDERED')                  AS rendered,
                       (SELECT COUNT(*) FROM event_log e WHERE e.ad_id = a.id AND e.type = 'VIEWABLE')                  AS viewable,
                       (SELECT COUNT(DISTINCT e.event_id) FROM event_log e WHERE e.ad_id = a.id AND e.type = 'CLICK')   AS clicks,
                       (SELECT COUNT(*) FROM click_ledger l WHERE l.ad_id = a.id AND l.result = 'CHARGED')              AS billed_clicks,
                       (SELECT COALESCE(SUM(l.amount), 0) FROM click_ledger l WHERE l.ad_id = a.id)                     AS spend,
                       (SELECT COUNT(*) FROM conversions c WHERE c.ad_id = a.id)                                        AS conversions,
                       (SELECT COALESCE(SUM(c.amount), 0) FROM conversions c WHERE c.ad_id = a.id)                      AS revenue
                  FROM ads a JOIN products p ON p.id = a.product_id ORDER BY a.id
                """)) {
            long rendered = num(r, "rendered");
            long viewable = num(r, "viewable");
            long clicks = num(r, "clicks");
            long spend = num(r, "spend");
            long revenue = num(r, "revenue");
            long estimatedSpend = counter(EventCollector.estimatedSpendKey(num(r, "ad_id")));
            Map<String, Object> row = new LinkedHashMap<>(r);
            row.put("ctrRendered", ratio(clicks, rendered));
            row.put("ctrViewable", ratio(clicks, viewable));
            row.put("cvr", ratio(num(r, "conversions"), clicks));
            row.put("roas", ratio(revenue, spend));
            row.put("estimatedSpend", estimatedSpend);
            row.put("estimatedRoas", ratio(revenue, estimatedSpend));
            rows.add(row);
        }
        return rows;
    }

    private long counter(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Long.parseLong(v);
    }

    private static long num(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static Double ratio(long numerator, long denominator) {
        return denominator == 0 ? null : (double) numerator / denominator;
    }
}
