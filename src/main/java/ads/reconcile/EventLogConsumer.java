package ads.reconcile;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ads.common.Partitions;
import ads.common.StreamGroupReader;

/**
 * 집계 컨슈머: 이벤트 스트림 네 개를 컨슈머 그룹 stats로 읽어 원본 그대로 event_log에 쌓습니다.
 *
 * 지키는 규칙
 * - 과금 컨슈머(그룹 billing)와 같은 스트림을 읽지만 그룹이 달라 서로의 읽은 위치, ACK에 영향을 주지 않습니다.
 * - 항목마다 event_log에 INSERT(entry_id 기본 키, 이미 있으면 무시)하고, 한 번에 읽은 묶음을 커밋한 뒤에 ACK합니다.
 * - 컨슈머 하나가 파티션 네 개를 차례로 돕니다.
 *
 * 왜 이 방식인지
 * - 과금은 같은 광고를 한 곳에서 순서대로 처리해야 해서 파티션마다 컨슈머가 하나씩 있지만, 집계는 건수를 세는 일이라 순서와 무관합니다.
 *   그래서 컨슈머 하나로 충분하고, 원격 Redis 명령 수도 그만큼 적습니다. "무엇을 지켜야 하나"가 컨슈머 수를 정합니다.
 * - 원본을 그대로 쌓고 숫자는 조회할 때 SQL로 셉니다. 무엇을 셀지(재전송을 뺄지, viewable만 셀지)가 바뀌어도 다시 쌓을 필요가 없습니다.
 *
 * 이 규칙이 깨지면
 * - ACK를 먼저 하면 커밋 전에 죽은 묶음이 영영 집계에서 빠져, 대사에서 "수집한 클릭 < 장부"로 어긋납니다.
 */
@Component
public class EventLogConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventLogConsumer.class);
    public static final String GROUP = "stats";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final boolean enabled;
    private final List<StreamGroupReader> readers = new ArrayList<>();
    private boolean started = false;

    public EventLogConsumer(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx,
                            @Value("${ads.event-log.enabled}") boolean enabled,
                            @Value("${ads.event-log.batch-size}") int batchSize) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.enabled = enabled;
        for (int p = 0; p < Partitions.COUNT; p++) {
            readers.add(new StreamGroupReader(redis, Partitions.streamKey(p), GROUP, "stats-1", batchSize));
        }
    }

    @Scheduled(fixedDelayString = "${ads.event-log.poll-ms}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            while (pollOnce() > 0) {
                // 밀린 항목이 있으면 쉬지 않고 이어서
            }
        } catch (RuntimeException e) {
            log.warn("[stats] poll failed, will retry: {}", e.toString());
        }
    }

    /** 네 파티션을 한 번씩 읽어 쌓습니다. 쌓은 항목 수를 돌려줍니다. */
    public synchronized int pollOnce() {
        if (!started) {
            readers.forEach(StreamGroupReader::start);
            started = true;
        }
        int total = 0;
        for (int p = 0; p < readers.size(); p++) {
            StreamGroupReader reader = readers.get(p);
            List<MapRecord<String, Object, Object>> records = reader.next();
            if (records.isEmpty()) {
                continue;
            }
            int partition = p;
            tx.executeWithoutResult(status -> records.forEach(r -> insert(partition, r)));
            records.forEach(r -> reader.ack(r.getId())); // 커밋이 끝난 뒤에만
            total += records.size();
        }
        return total;
    }

    /** 테스트에서 "재시작"을 흉내 냅니다. 다음 읽기를 PEL부터 합니다. */
    public synchronized void restart() {
        started = false;
    }

    private void insert(int partition, MapRecord<String, Object, Object> record) {
        Map<Object, Object> f = record.getValue();
        jdbc.update("""
                INSERT INTO event_log (entry_id, type, event_id, impression_id, ad_id, user_id, cpc, event_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (entry_id) DO NOTHING
                """,
                partition + ":" + record.getId().getValue(), f.get("type"), f.get("eventId"), f.get("impressionId"),
                Long.parseLong((String) f.get("adId")), f.get("userId"), Integer.parseInt((String) f.get("cpc")),
                new Timestamp(Long.parseLong((String) f.get("eventTime"))));
    }
}
