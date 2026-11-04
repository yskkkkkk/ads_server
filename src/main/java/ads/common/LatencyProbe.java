package ads.common;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜰 때 Postgres와 Redis까지의 왕복 시간을 재서 로그로 남깁니다.
 *
 * 지키는 규칙
 * - 서빙 경로는 50ms 안에 끝나야 합니다(CLAUDE.md 4장). 이 예산 안에 원격 저장소를 몇 번 부를 수 있는지는
 *   추측하지 않고 실제 서버에서 잰 숫자로 정합니다.
 *
 * 왜 이 방식인지
 * - 서버(춘천)와 Neon, Upstash(싱가포르)는 리전이 다릅니다. 로컬 compose에서는 1ms 안쪽이라 문제가 보이지 않습니다.
 * - 처음 몇 번은 커넥션 생성, TLS 협상 시간이 섞이므로 버리고(WARM_UP) 나머지로 통계를 냅니다.
 * - HTTP 엔드포인트로 열지 않고 기동 로그로만 남깁니다. 밖에서 반복 호출되면 Upstash 명령 수와 Neon 사용량이 늘기 때문입니다.
 *   배포 워크플로가 기동 직후 이 로그를 출력하므로 서버에 접속하지 않고 GitHub Actions 로그에서 볼 수 있습니다.
 *
 * 이 규칙이 깨지면
 * - 측정 없이 서빙에서 매 요청 원격 Redis, DB를 부르면, 서버에서는 광고 조회가 늘 50ms를 넘겨
 *   모든 요청이 "광고 없이 응답"으로 끝납니다. 채움률(fill rate) 0%, 즉 매출 0입니다.
 * - 측정 실패는 기동을 막지 않습니다. 저장소 연결 이상은 /actuator/health가 따로 알려줍니다.
 */
@Component
public class LatencyProbe {

    private static final Logger log = LoggerFactory.getLogger(LatencyProbe.class);
    private static final int WARM_UP = 3;
    private static final int SAMPLES = 20;

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;

    public LatencyProbe(JdbcTemplate jdbcTemplate, StringRedisTemplate redisTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void measure() {
        report("postgres", () -> jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        report("redis", () -> redisTemplate.execute(connection -> connection.ping(), true));
    }

    private void report(String target, Runnable call) {
        try {
            List<Double> millis = new ArrayList<>();
            for (int i = 0; i < WARM_UP + SAMPLES; i++) {
                long start = System.nanoTime();
                call.run();
                double elapsed = (System.nanoTime() - start) / 1_000_000.0;
                if (i >= WARM_UP) {
                    millis.add(elapsed);
                }
            }
            Collections.sort(millis);
            log.info("[latency] {} round-trip ms: min={} p50={} p95={} max={} (n={})",
                    target,
                    format(millis.get(0)),
                    format(percentile(millis, 50)),
                    format(percentile(millis, 95)),
                    format(millis.get(millis.size() - 1)),
                    millis.size());
        } catch (RuntimeException e) {
            log.warn("[latency] {} measurement failed: {}", target, e.toString());
        }
    }

    /** 정렬된 목록에서 nearest-rank 방식 백분위수. */
    private static double percentile(List<Double> sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(rank - 1, 0));
    }

    private static String format(double ms) {
        return String.format("%.1f", ms);
    }
}
