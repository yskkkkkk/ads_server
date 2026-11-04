package ads.serving;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 사용자별 신호(퍼스트파티 데이터)를 Redis에서 읽고 씁니다.
 *
 * 지키는 규칙
 * - 서빙 한 번에 Redis는 MGET 한 번만 부릅니다. 최근 본 브랜드와 후보 광고별 노출 횟수를 한 번에 읽습니다.
 * - 키 접두사는 ads: 입니다(PAM 키와 섞이지 않게, docs/pam-infra.md 5-2).
 *     ads:user:{userId}:recent-brand   최근 본 브랜드, 1일 뒤 만료
 *     ads:freq:{userId}:{adId}         최근 1시간 렌더링 노출 횟수(2단계 노출 수집이 올림)
 *
 * 왜 이 방식인지
 * - 원격 Redis 왕복 한 번이 수십 ms일 수 있습니다. 키마다 따로 부르면 후보 수만큼 왕복이 늘어 50ms 예산을 넘깁니다.
 *
 * 이 규칙이 깨지면
 * - 서빙 시간이 후보 수에 비례해 늘고, 결국 타임아웃으로 광고 없이 응답하게 됩니다.
 */
@Component
public class UserSignals {

    public record Signals(String recentBrand, Map<Long, Integer> impressionsLastHour) {
    }

    private final StringRedisTemplate redis;

    public UserSignals(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Signals load(String userId, List<Ad> candidates) {
        List<String> keys = new ArrayList<>();
        keys.add(recentBrandKey(userId));
        candidates.forEach(ad -> keys.add(frequencyKey(userId, ad.id())));

        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return new Signals(null, Map.of());
        }
        Map<Long, Integer> impressions = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            String count = values.get(i + 1);
            if (count != null) {
                impressions.put(candidates.get(i).id(), Integer.parseInt(count));
            }
        }
        return new Signals(values.get(0), impressions);
    }

    public void recordViewedBrand(String userId, String brand) {
        redis.opsForValue().set(recentBrandKey(userId), brand, Duration.ofDays(1));
    }

    static String recentBrandKey(String userId) {
        return "ads:user:" + userId + ":recent-brand";
    }

    static String frequencyKey(String userId, long adId) {
        return "ads:freq:" + userId + ":" + adId;
    }
}
