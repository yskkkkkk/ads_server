package ads.billing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.PreDestroy;

import ads.common.Partitions;
import ads.serving.ServingProperties;

/**
 * 파티션마다 과금 컨슈머를 하나씩 띄웁니다(파티션 4개 → 컨슈머 4개, 각자 스레드 하나).
 *
 * 지키는 규칙
 * - 파티션 하나는 컨슈머 하나만 읽습니다. 그래서 같은 광고의 클릭은 늘 한 스레드에서 차례로 처리됩니다.
 * - 컨슈머 하나가 실패해도(DB, Redis 장애) 그 스레드는 다음 주기에 다시 시도합니다. ACK하지 않은 항목은 스트림에 남아 있습니다.
 * - ads.billing.enabled=false면 띄우지 않습니다. 테스트는 컨슈머를 직접 만들어 한 번씩 돌립니다.
 *
 * 왜 이 방식인지
 * - 서버가 한 대라 한 프로세스 안에서 파티션별 스레드로 "컨슈머 여럿"을 흉내 냅니다. 서버가 여러 대가 되면 컨슈머 이름만 서버별로
 *   나누고 파티션을 서버들에 나눠 주면 같은 구조가 됩니다(Kafka 컨슈머 그룹의 파티션 할당과 같은 일).
 *
 * 이 규칙이 깨지면
 * - 한 파티션에 컨슈머 둘을 붙이면 Redis가 항목을 둘에게 나눠 주고, 각자의 메모리 잔액으로 판정해 초과 집행이 생깁니다.
 */
@Component
public class BillingWorkers {

    private static final Logger log = LoggerFactory.getLogger(BillingWorkers.class);

    private final BillingProperties props;
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ServingProperties serving;
    private final List<BillingConsumer> consumers = new ArrayList<>();
    private final Set<BillingConsumer> started = ConcurrentHashMap.newKeySet();
    private ScheduledExecutorService executor;

    public BillingWorkers(BillingProperties props, StringRedisTemplate redis, JdbcTemplate jdbc,
                          TransactionTemplate tx, ServingProperties serving) {
        this.props = props;
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.serving = serving;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.enabled()) {
            log.info("[billing] consumers disabled");
            return;
        }
        executor = Executors.newScheduledThreadPool(Partitions.COUNT);
        for (int p = 0; p < Partitions.COUNT; p++) {
            BillingConsumer consumer = new BillingConsumer(p, "billing-" + p, props.batchSize(), serving.reserveCpc(),
                    redis, jdbc, tx);
            consumers.add(consumer);
            executor.scheduleWithFixedDelay(() -> run(consumer), 0, props.pollMs(), TimeUnit.MILLISECONDS);
        }
        log.info("[billing] {} consumers started, poll every {}ms", Partitions.COUNT, props.pollMs());
    }

    private void run(BillingConsumer consumer) {
        try {
            if (!started.contains(consumer)) {
                consumer.start();
                started.add(consumer);
            }
            while (consumer.pollOnce(true) > 0) {
                // 밀린 항목이 있으면 쉬지 않고 이어서 처리한다
            }
        } catch (RuntimeException e) {
            log.warn("[billing] poll failed, will retry: {}", e.toString());
        }
    }

    @PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
