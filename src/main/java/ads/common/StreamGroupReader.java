package ads.common;

import java.util.List;

import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 컨슈머 그룹으로 스트림 하나를 읽는 공통 부분. 과금(billing)과 집계(stats) 컨슈머가 같이 씁니다.
 *
 * 지키는 규칙
 * - 그룹이 없으면 스트림 처음(0)부터 읽는 그룹을 만듭니다. 이미 있으면(BUSYGROUP) 그대로 씁니다.
 * - 시작(재시작) 직후에는 ACK하지 못한 채 남은 내 항목(PEL)부터 읽고, 다 읽으면 같은 호출에서 새 항목으로 넘어갑니다.
 * - ACK는 이 클래스가 알아서 하지 않습니다. 부르는 쪽이 자기 커밋을 끝낸 뒤에 ack()를 부릅니다.
 *
 * 왜 이 방식인지
 * - 컨슈머 그룹마다 "어디까지 읽었나"를 따로 기억하므로, 과금과 집계가 같은 스트림을 서로 방해하지 않고 각자 읽습니다.
 * - BLOCK 없이 읽습니다. 쉬는 간격은 부르는 쪽이 정합니다(docs/tradeoffs.md 17).
 *
 * 이 규칙이 깨지면
 * - 재시작 때 PEL을 건너뛰면, 처리 중에 죽은 항목이 영영 처리되지 않습니다(ACK가 안 됐으니 새 항목으로도 다시 오지 않음).
 */
public final class StreamGroupReader {

    private final StringRedisTemplate redis;
    private final String stream;
    private final String group;
    private final String consumer;
    private final int batchSize;
    private boolean recovering = true;

    public StreamGroupReader(StringRedisTemplate redis, String stream, String group, String consumer, int batchSize) {
        this.redis = redis;
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
        this.batchSize = batchSize;
    }

    public String stream() {
        return stream;
    }

    /** 그룹을 만들고(있으면 그대로), 다음 읽기를 PEL부터 하게 합니다. */
    public void start() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), group);
        } catch (RedisSystemException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
        recovering = true;
    }

    /** PEL에 남은 항목이 있으면 그것을, 없으면 새 항목을 최대 batchSize개 읽습니다. */
    public List<MapRecord<String, Object, Object>> next() {
        List<MapRecord<String, Object, Object>> records = List.of();
        if (recovering) {
            records = read(ReadOffset.from("0"));
            if (records.isEmpty()) {
                recovering = false; // 밀린 항목을 다 처리했으면 이제 새 항목을 읽는다
            }
        }
        if (!recovering) {
            records = read(ReadOffset.lastConsumed());
        }
        return records;
    }

    public void ack(RecordId id) {
        redis.opsForStream().acknowledge(stream, group, id);
    }

    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(group, consumer),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(stream, offset));
        return records == null ? List.of() : records;
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }
}
