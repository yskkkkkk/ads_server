package ads.conversion;

import java.util.Map;
import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ads.common.ImpressionTokens;
import ads.serving.Catalog;

/**
 * 전환(⑨) API.
 *
 *   POST /events/conversion?orderId&productId&amount[&token][&userId][&orderedAt]
 *        구매 한 건. token은 상품 페이지 URL로 이어받은 노출 토큰, userId는 로그인 사용자.
 *        orderedAt(ms)은 시나리오에서 시간을 흉내 낼 때만 씁니다. 없으면 서버 시각.
 *   GET  /conversions/stats
 *        광고별 귀속 매출, 추정 광고비, 추정 ROAS, 귀속되지 않은 매출, 귀속 경로별 건수.
 *
 * 응답 규칙
 * - 서명이 틀린 토큰은 400. 남의 광고에 매출을 붙이는 위조를 막습니다.
 * - 귀속하지 못한 구매도 200으로 받고 기록합니다. "광고와 무관한 매출"도 대사와 성과 비교에 필요한 숫자입니다.
 */
@RestController
public class ConversionController {

    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,64}";

    private final ConversionService conversions;
    private final ImpressionTokens tokens;
    private final Catalog catalog;

    public ConversionController(ConversionService conversions, ImpressionTokens tokens, Catalog catalog) {
        this.conversions = conversions;
        this.tokens = tokens;
        this.catalog = catalog;
    }

    @PostMapping("/events/conversion")
    public ResponseEntity<?> conversion(@RequestParam String orderId,
                                        @RequestParam long productId,
                                        @RequestParam int amount,
                                        @RequestParam(required = false) String token,
                                        @RequestParam(required = false) String userId,
                                        @RequestParam(required = false) Long orderedAt) {
        if (!orderId.matches(ID_PATTERN) || (userId != null && !userId.matches(ID_PATTERN)) || amount <= 0) {
            return ResponseEntity.badRequest().body("orderId, userId: " + ID_PATTERN + ", amount > 0");
        }
        if (!catalog.snapshot().productsById().containsKey(productId)) {
            return ResponseEntity.badRequest().body("unknown productId");
        }
        ImpressionTokens.Claims claims = null;
        if (token != null && !token.isBlank()) {
            Optional<ImpressionTokens.Claims> verified = tokens.verify(token);
            if (verified.isEmpty()) {
                return ResponseEntity.badRequest().body("invalid token");
            }
            claims = verified.get();
        }
        long time = orderedAt == null ? System.currentTimeMillis() : orderedAt;
        return ResponseEntity.ok(conversions.record(orderId, productId, amount, claims, userId, time));
    }

    @GetMapping("/conversions/stats")
    public Map<String, Object> stats() {
        return conversions.stats();
    }
}
