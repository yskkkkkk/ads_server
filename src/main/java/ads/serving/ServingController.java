package ads.serving;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 서빙 API.
 *
 *   GET  /ads?slot=category-list&category=shoes&userId=u1[&personalize=false][&debugDelayMs=80]
 *        광고가 섞인 상품 목록 + 경매표(auction). debugDelayMs는 느린 저장소를 흉내 내는 장애 주입 손잡이입니다.
 *   POST /signals/viewed-brand?userId=u1&brand=runway
 *        "사용자가 이 브랜드 상품을 봤다"는 퍼스트파티 신호를 남깁니다. 개인화 pCTR 보정에 쓰입니다.
 *   GET  /serving/stats
 *        채움률(fill rate), 결과별 건수, 서킷 브레이커 상태.
 */
@RestController
public class ServingController {

    private static final String ID_PATTERN = "[A-Za-z0-9_-]{1,40}";

    private final ServingService serving;
    private final UserSignals userSignals;

    public ServingController(ServingService serving, UserSignals userSignals) {
        this.serving = serving;
        this.userSignals = userSignals;
    }

    @GetMapping("/ads")
    public ResponseEntity<?> ads(@RequestParam(defaultValue = "category-list") String slot,
                                 @RequestParam String category,
                                 @RequestParam String userId,
                                 @RequestParam(defaultValue = "true") boolean personalize,
                                 @RequestParam(defaultValue = "0") int debugDelayMs) {
        if (!userId.matches(ID_PATTERN) || !category.matches(ID_PATTERN)) {
            return ResponseEntity.badRequest().body("userId, category: " + ID_PATTERN);
        }
        return ResponseEntity.ok(serving.serve(slot, category, userId, personalize, debugDelayMs));
    }

    @PostMapping("/signals/viewed-brand")
    public ResponseEntity<?> viewedBrand(@RequestParam String userId, @RequestParam String brand) {
        if (!userId.matches(ID_PATTERN) || !brand.matches(ID_PATTERN)) {
            return ResponseEntity.badRequest().body("userId, brand: " + ID_PATTERN);
        }
        userSignals.recordViewedBrand(userId, brand);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/serving/stats")
    public ServingService.Stats stats() {
        return serving.stats();
    }
}
