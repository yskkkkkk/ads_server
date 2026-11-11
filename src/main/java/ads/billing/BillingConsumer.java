package ads.billing;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import ads.common.Partitions;

/**
 * 과금 컨슈머 하나. 파티션 스트림 하나(ads:events:{파티션})를 읽어 클릭마다 과금 여부를 확정합니다.
 *
 * 지키는 규칙
 * - 한 번 처리의 순서: ① 장부에서 이 clickId 확인 → ② ClickBilling.decide → ③ 한 트랜잭션으로 장부 INSERT
 *   (+ 과금이면 ads.spent UPDATE) → ④ 커밋 후 메모리 상태 반영(ClickBilling.apply) → ⑤ 예산이 바닥나면 Redis 소진 표시
 *   → ⑥ 마지막으로 스트림에 처리 완료(XACK).
 * - 어느 순간에 죽어도 반쪽 상태가 없습니다.
 *   커밋 전에 죽음: 장부에 아무것도 없고 ACK도 안 했으니, 재시작 후 같은 항목을 다시 받아 처음부터 처리합니다.
 *   커밋 후 ACK 전에 죽음: 재시작 후 같은 항목이 다시 오지만, 장부에 "같은 스트림 항목"이 이미 있으므로 재배달로 보고 ACK만 합니다.
 * - 재배달과 재전송을 구분합니다. 같은 clickId가 장부에 있을 때, 그 줄을 만든 스트림 항목과 지금 항목이 같으면 재배달(내가 죽어서
 *   다시 온 것, 아무것도 기록하지 않음), 다르면 재전송(클라이언트가 같은 클릭을 또 보낸 것, DUPLICATE로 기록).
 * - 시작할 때 메모리 상태(광고별 잔액, 사용자 × 광고별 마지막 과금 시각)를 장부 합계로 다시 만들고,
 *   ACK하지 못한 채 남은 항목(PEL)부터 처리한 뒤 새 항목을 읽습니다.
 * - 클릭이 아닌 이벤트(노출)는 과금과 상관없어 바로 ACK합니다. 5단계 집계는 다른 컨슈머 그룹으로 같은 스트림을 따로 읽습니다.
 *
 * 왜 이 순서와 방식인지
 * - ACK를 커밋 뒤에 하는 이유: 먼저 ACK하고 커밋 전에 죽으면 그 클릭은 영영 다시 오지 않아 과금이 사라집니다.
 *   PAM에서 "커밋 전에 락을 풀어" 갱신 손실이 났던 것과 같은 종류의 사고입니다(docs/pam-infra.md 3-3).
 * - 그래서 전달은 "최소 한 번"이 되고, 두 번 오는 것은 장부의 click_id 기본 키(유니크)가 막습니다. 최소 한 번 + 멱등 처리 = 정확히 한 번 효과.
 * - 메모리 상태를 쓰는 이유: 클릭마다 DB에서 잔액과 마지막 과금 시각을 읽지 않아도 됩니다. 이 메모리는 이 파티션 컨슈머 하나만
 *   쓰므로 락이 필요 없고, 언제든 장부에서 다시 만들 수 있는 사본입니다.
 * - 스트림은 BLOCK 없이 짧게 읽고 쉬기를 반복합니다(poll-ms). BLOCK 읽기는 연결을 붙잡아 다른 Redis 명령과 섞일 수 없고,
 *   쉬는 간격으로 원격 Redis(Upstash) 명령 수를 직접 조절할 수 있습니다. 측정 경로는 조금 늦어도 됩니다.
 *
 * 이 규칙이 깨지면
 * - 커밋과 ACK 순서가 바뀌면 과금 유실, 장부 INSERT와 예산 UPDATE가 다른 트랜잭션이면 "과금은 했는데 예산은 그대로" 같은 반쪽 상태.
 * - 같은 파티션을 컨슈머 여럿이 나눠 읽으면 각자의 메모리 잔액으로 판정해 초과 집행이 생깁니다(대조 실험).
 */
public class BillingConsumer {

    private static final Logger log = LoggerFactory.getLogger(BillingConsumer.class);
    public static final String GROUP = "billing";
    static final String EXHAUSTED_KEY = "ads:exhausted";

    private final int partition;
    private final String consumerName;
    private final int batchSize;
    private final int reserveCpc;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private final ClickBilling.State state = new ClickBilling.State();
    private boolean recovering = true;

    public BillingConsumer(int partition, String consumerName, int batchSize, int reserveCpc,
                           StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx) {
        this.partition = partition;
        this.consumerName = consumerName;
        this.batchSize = batchSize;
        this.reserveCpc = reserveCpc;
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** 시작(또는 재시작): 컨슈머 그룹을 만들고, 메모리 상태를 장부에서 다시 만들고, 밀린 항목(PEL)부터 읽게 합니다. */
    public void start() {
        String stream = Partitions.streamKey(partition);
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.from("0"), GROUP);
        } catch (RedisSystemException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
            // 이미 그룹이 있다(재시작, 다른 컨슈머가 먼저 만듦). 그대로 쓴다
        }
        restoreFromLedger();
        recovering = true;
    }

    /** 한 번 읽고 처리합니다. 처리한 항목 수를 돌려줍니다. ack=false는 "커밋 직후 ACK 전에 죽음"을 흉내 냅니다. */
    public int pollOnce(boolean ack) {
        String stream = Partitions.streamKey(partition);
        List<MapRecord<String, Object, Object>> records = List.of();
        if (recovering) {
            records = read(stream, ReadOffset.from("0")); // ACK하지 못한 채 남은 내 항목(PEL)
            if (records.isEmpty()) {
                recovering = false; // 밀린 항목을 다 처리했으면 이제 새 항목을 읽는다
            }
        }
        if (!recovering) {
            records = read(stream, ReadOffset.lastConsumed());
        }
        if (records.isEmpty()) {
            return 0;
        }
        for (MapRecord<String, Object, Object> record : records) {
            handle(record);
            if (ack) {
                redis.opsForStream().acknowledge(stream, GROUP, record.getId());
            }
        }
        return records.size();
    }

    private List<MapRecord<String, Object, Object>> read(String stream, ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(batchSize),
                StreamOffset.create(stream, offset));
        return records == null ? List.of() : records;
    }

    private void handle(MapRecord<String, Object, Object> record) {
        Map<Object, Object> f = record.getValue();
        if (!"CLICK".equals(f.get("type"))) {
            return; // 노출은 과금과 무관
        }
        ClickBilling.Click click = new ClickBilling.Click(
                (String) f.get("eventId"),
                Long.parseLong((String) f.get("adId")),
                (String) f.get("userId"),
                Integer.parseInt((String) f.get("cpc")),
                Long.parseLong((String) f.get("eventTime")));
        String entryId = partition + ":" + record.getId().getValue();
        ensureAd(click.adId());

        ClickBilling.Result result = tx.execute(status -> {
            List<String> existing = jdbc.queryForList(
                    "SELECT entry_id FROM click_ledger WHERE click_id = ?", String.class, click.clickId());
            if (!existing.isEmpty() && existing.get(0).equals(entryId)) {
                return null; // 재배달: 이 항목은 이미 커밋까지 끝났다(ACK 전에 죽었던 것). 아무것도 하지 않는다
            }
            ClickBilling.Result r = ClickBilling.decide(click, !existing.isEmpty(), state);
            if (r == ClickBilling.Result.DUPLICATE) {
                jdbc.update("INSERT INTO duplicate_clicks (entry_id, click_id, ad_id) VALUES (?, ?, ?) "
                        + "ON CONFLICT (entry_id) DO NOTHING", entryId, click.clickId(), click.adId());
                return r;
            }
            int amount = r == ClickBilling.Result.CHARGED ? click.cpc() : 0;
            jdbc.update("""
                    INSERT INTO click_ledger (click_id, entry_id, ad_id, user_id, cpc, amount, result, event_time)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    click.clickId(), entryId, click.adId(), click.userId(), click.cpc(), amount, r.name(),
                    new Timestamp(click.eventTime()));
            if (r == ClickBilling.Result.CHARGED) {
                jdbc.update("UPDATE ads SET spent = spent + ? WHERE id = ?", click.cpc(), click.adId());
            }
            return r;
        });

        if (result == null) {
            log.info("[billing] redelivered entry {} already settled, ack only", entryId);
            return;
        }
        ClickBilling.apply(click, result, state); // 커밋이 끝난 뒤에만 메모리를 바꾼다
        Long balance = state.balance(click.adId());
        if (result == ClickBilling.Result.BUDGET_EXHAUSTED
                || (result == ClickBilling.Result.CHARGED && balance != null && balance < reserveCpc)) {
            redis.opsForSet().add(EXHAUSTED_KEY, Long.toString(click.adId())); // 서빙이 다음 스냅샷부터 이 광고를 뺀다
        }
        log.info("[billing] p{} {} click={} ad={} user={} cpc={} -> {} (balance {})",
                partition, consumerName, click.clickId(), click.adId(), click.userId(), click.cpc(), result, balance);
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    /** 메모리 상태를 장부에서 다시 만듭니다. 잔액 = 예산 − 장부의 과금 합계, 마지막 과금 시각 = 장부의 최대 이벤트 시각. */
    void restoreFromLedger() {
        jdbc.query("""
                SELECT a.id, a.budget - COALESCE(SUM(l.amount), 0) AS balance
                  FROM ads a LEFT JOIN click_ledger l ON l.ad_id = a.id
                 GROUP BY a.id, a.budget
                """,
                rs -> {
                    long adId = rs.getLong("id");
                    if (Partitions.of(adId) == partition) {
                        state.setBalance(adId, rs.getLong("balance"));
                    }
                });
        jdbc.query("""
                SELECT ad_id, user_id, MAX(event_time) AS last_charged
                  FROM click_ledger WHERE result = 'CHARGED' GROUP BY ad_id, user_id
                """,
                rs -> {
                    long adId = rs.getLong("ad_id");
                    if (Partitions.of(adId) == partition) {
                        state.setLastChargedAt(rs.getString("user_id"), adId, rs.getTimestamp("last_charged").getTime());
                    }
                });
    }

    /** 시작 뒤에 새로 생긴 광고의 잔액을 장부에서 읽어 옵니다. */
    private void ensureAd(long adId) {
        if (state.knowsAd(adId)) {
            return;
        }
        Long balance = jdbc.queryForObject("""
                SELECT a.budget - COALESCE((SELECT SUM(amount) FROM click_ledger WHERE ad_id = a.id), 0)
                  FROM ads a WHERE a.id = ?
                """, Long.class, adId);
        state.setBalance(adId, balance == null ? 0 : balance);
    }

    public Long balance(long adId) {
        return state.balance(adId);
    }
}
