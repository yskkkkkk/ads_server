package ads.event;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ads.common.ImpressionTokens;
import ads.serving.Ad;
import ads.serving.Catalog;

/**
 * 측정 경로 API. 모든 이벤트는 1단계에서 발급한 노출 토큰을 달고 와야 합니다.
 *
 *   POST /events/impression?token&type=RENDERED|VIEWABLE        노출 (데모 페이지가 sendBeacon으로 보냄)
 *   POST /events/click?token[&clickId][&eventTime]              클릭 (데모 페이지의 fetch, sendBeacon 모드)
 *   GET  /events/click?token[&chain=false]                      클릭 URL 방식: 기록 후 상품 페이지로 302 리다이렉트
 *   GET  /events/stats                                          종류별 받은 수, 중복, GIVT, 위조 토큰, 파티션별 스트림 길이
 *
 * 응답 규칙
 * - 서명이 틀린 토큰은 400. 이벤트를 만들어 낼 수 없게 합니다.
 * - 중복, GIVT도 204로 똑같이 답합니다. 봇에게 걸러졌다는 사실을 알려주지 않습니다.
 * - 클릭 URL 방식은 리다이렉트할 때 토큰을 상품 페이지 URL에 이어 붙입니다(파라미터 체이닝).
 *   3단계에서 구매를 이 광고에 연결하는 유일한 끈입니다. chain=false 는 끈이 끊긴 상황을 재현합니다.
 */
@RestController
public class EventController {

    private final ImpressionTokens tokens;
    private final EventCollector collector;
    private final Catalog catalog;

    public EventController(ImpressionTokens tokens, EventCollector collector, Catalog catalog) {
        this.tokens = tokens;
        this.collector = collector;
        this.catalog = catalog;
    }

    @PostMapping("/events/impression")
    public ResponseEntity<?> impression(@RequestParam String token,
                                        @RequestParam EventType type,
                                        @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        if (type == EventType.CLICK) {
            return ResponseEntity.badRequest().body("type: RENDERED | VIEWABLE");
        }
        Optional<ImpressionTokens.Claims> claims = tokens.verify(token);
        if (claims.isEmpty()) {
            collector.countInvalidToken(type);
            return ResponseEntity.badRequest().body("invalid token");
        }
        String eventId = claims.get().impressionId() + ":" + type;
        collector.collect(type, eventId, claims.get(), System.currentTimeMillis(), userAgent);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/events/click")
    public ResponseEntity<?> click(@RequestParam String token,
                                   @RequestParam(required = false) String clickId,
                                   @RequestParam(required = false) Long eventTime,
                                   @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Optional<ImpressionTokens.Claims> claims = tokens.verify(token);
        if (claims.isEmpty()) {
            collector.countInvalidToken(EventType.CLICK);
            return ResponseEntity.badRequest().body("invalid token");
        }
        String id = clickId == null || clickId.isBlank() ? UUID.randomUUID().toString() : clickId;
        long time = eventTime == null ? System.currentTimeMillis() : eventTime;
        collector.collect(EventType.CLICK, id, claims.get(), time, userAgent);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/events/click")
    public ResponseEntity<?> clickRedirect(@RequestParam String token,
                                           @RequestParam(defaultValue = "true") boolean chain,
                                           @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Optional<ImpressionTokens.Claims> claims = tokens.verify(token);
        if (claims.isEmpty()) {
            collector.countInvalidToken(EventType.CLICK);
            return ResponseEntity.badRequest().body("invalid token");
        }
        Ad ad = catalog.snapshot().adsById().get(claims.get().adId());
        if (ad == null) {
            return ResponseEntity.notFound().build();
        }
        collector.collect(EventType.CLICK, UUID.randomUUID().toString(), claims.get(), System.currentTimeMillis(), userAgent);

        String landing = "/demo/product.html?productId=" + ad.productId() + (chain ? "&adToken=" + token : "");
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(landing)).build();
    }

    @GetMapping("/events/stats")
    public Map<String, Object> stats() {
        return collector.stats();
    }
}
