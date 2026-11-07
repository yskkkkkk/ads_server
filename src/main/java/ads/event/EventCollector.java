package ads.event;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import ads.common.ImpressionTokens;
import ads.common.Partitions;

/**
 * 측정 경로의 입구: 검증을 통과한 이벤트를 Redis Streams 파티션에 넣습니다.
 *
 * 지키는 규칙
 * - 노출(RENDERED, VIEWABLE)은 "같은 토큰 × 같은 종류"를 한 번만 받습니다. 브라우저가 재전송해도 한 번만 셉니다.
 * - 렌더링 노출을 받을 때만 피로도 횟수(ads:freq:{사용자}:{광고})를 올립니다. 1단계 서빙이 이 숫자로 거릅니다.
 * - 클릭은 여기서 중복 제거하지 않습니다. clickId 기준 중복 제거와 과금 판정은 4단계 과금 컨슈머의 일입니다.
 * - 스트림은 광고 ID로 나눈 파티션(ads:events:0~3)입니다(Partitions).
 * - 위 일들(중복 확인, 피로도, 스트림 적재, 건수)은 Lua 스크립트 하나로 원자적으로 처리합니다(scripts/collect_event.lua).
 *
 * 왜 이 순서와 방식인지
 * - 수집은 "최소 한 번 전달"을 받아들입니다. 네트워크가 끊겨 클라이언트가 다시 보내면 같은 이벤트가 또 옵니다.
 *   노출은 수가 많고 과금 근거가 아니라서 입구에서 바로 중복을 걸러 저장 공간을 아낍니다.
 *   클릭은 돈이 걸린 이벤트라 "받았다"와 "과금했다"를 한 곳(과금 컨슈머의 장부 트랜잭션)에서 판정해야 정확합니다.
 * - 피로도를 서빙 시점이 아니라 렌더링 노출 시점에 세는 이유: 응답만 받고 화면에 안 그린 광고는 사용자가 본 적이 없습니다.
 * - 원자적으로 묶는 이유: 명령 사이에 앱이 죽으면 "중복 표시만 되고 스트림에는 없는" 이벤트가 생겨 영원히 유실됩니다.
 *
 * 이 규칙이 깨지면
 * - 노출 중복을 안 거르면 노출이 부풀어 CTR이 실제보다 낮게 나오고, 피로도 상한에 너무 일찍 걸립니다.
 * - 클릭을 여기서 중복 제거하면 "수집은 됐는데 과금 판정 전에 죽은" 클릭을 다시 처리할 길이 사라집니다.
 */
@Component
public class EventCollector {

    public enum Result { ACCEPTED, DUPLICATE, GIVT }

    private static final long DEDUPE_TTL_SECONDS = 24 * 60 * 60;
    private static final long FREQUENCY_WINDOW_SECONDS = 60 * 60;
    private static final long STREAM_MAX_LEN = 10_000;

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> script;

    public EventCollector(StringRedisTemplate redis) {
        this.redis = redis;
        this.script = new DefaultRedisScript<>();
        this.script.setScriptSource(new ResourceScriptSource(new ClassPathResource("scripts/collect_event.lua")));
        this.script.setResultType(Long.class);
    }

    /**
     * @param eventId 노출은 impressionId:종류, 클릭은 clickId
     */
    public Result collect(EventType type, String eventId, ImpressionTokens.Claims token, long eventTime, String userAgent) {
        if (BotFilter.isGivt(userAgent)) {
            redis.opsForValue().increment(counterKey(type, "givt"));
            return Result.GIVT;
        }

        boolean dedupe = type != EventType.CLICK;
        boolean countFrequency = type == EventType.RENDERED;

        List<String> keys = List.of(
                "ads:dedupe:" + eventId,
                "ads:freq:" + token.userId() + ":" + token.adId(),
                Partitions.streamKeyOfAd(token.adId()),
                counterKey(type, "accepted"),
                counterKey(type, "duplicate"));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("type", type.name());
        fields.put("eventId", eventId);
        fields.put("impressionId", token.impressionId());
        fields.put("adId", Long.toString(token.adId()));
        fields.put("userId", token.userId());
        fields.put("cpc", Integer.toString(token.cpc()));
        fields.put("eventTime", Long.toString(eventTime));
        fields.put("receivedAt", Long.toString(System.currentTimeMillis()));

        List<String> args = new ArrayList<>();
        args.add(Long.toString(dedupe ? DEDUPE_TTL_SECONDS : 0));
        args.add(Long.toString(countFrequency ? FREQUENCY_WINDOW_SECONDS : 0));
        args.add(Long.toString(STREAM_MAX_LEN));
        fields.forEach((k, v) -> {
            args.add(k);
            args.add(v);
        });

        Long added = redis.execute(script, keys, args.toArray());
        return added != null && added == 1 ? Result.ACCEPTED : Result.DUPLICATE;
    }

    public void countInvalidToken(EventType type) {
        redis.opsForValue().increment(counterKey(type, "invalid-token"));
    }

    /** 종류별 건수와 파티션별 스트림 길이. 데모 페이지가 "브라우저가 보낸 수"와 비교하는 서버 쪽 숫자입니다. */
    public Map<String, Object> stats() {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (EventType type : EventType.values()) {
            Map<String, Long> byResult = new LinkedHashMap<>();
            for (String result : List.of("accepted", "duplicate", "givt", "invalid-token")) {
                String v = redis.opsForValue().get(counterKey(type, result));
                byResult.put(result, v == null ? 0 : Long.parseLong(v));
            }
            counts.put(type.name(), byResult);
        }
        Map<String, Long> streams = new LinkedHashMap<>();
        for (int p = 0; p < Partitions.COUNT; p++) {
            Long len = redis.opsForStream().size(Partitions.streamKey(p));
            streams.put(Partitions.streamKey(p), len == null ? 0 : len);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("events", counts);
        result.put("streams", streams);
        return result;
    }

    static String counterKey(EventType type, String result) {
        return "ads:count:" + type.name().toLowerCase() + ":" + result;
    }
}
