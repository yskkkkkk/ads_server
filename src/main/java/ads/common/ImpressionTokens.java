package ads.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 노출 토큰: 서빙 결정(어떤 광고를 누구에게 얼마에 냈나)을 담아 서명한 문자열.
 *
 * 지키는 규칙
 * - 서빙 응답의 광고마다 하나씩 발급합니다. 담는 것: 고유 ID, 광고 ID, 사용자 ID, 낙찰 CPC, 발급 시각.
 * - 노출, 클릭, 구매 이벤트는 모두 이 토큰을 달고 돌아와야 하고, 서버는 서명을 확인한 뒤에만 받습니다.
 *
 * 왜 이 방식인지
 * - "모든 이벤트는 처음의 서빙 결정에 묶인다"(CLAUDE.md 3-2). 클릭이 들어왔을 때 얼마를 과금할지는
 *   클라이언트가 보낸 값이 아니라 서빙 때 옥션이 정한 값(토큰 안의 CPC)이어야 합니다.
 * - 서버 메모리나 DB에 저장하지 않고 서명(HMAC)으로 위조를 막습니다. 서버가 여러 대여도 비밀키만 같으면 검증됩니다.
 *
 * 이 규칙이 깨지면
 * - 서명이 없으면 누구나 CPC를 1원으로 바꾼 클릭, 존재하지 않는 광고의 클릭을 만들어 보낼 수 있습니다.
 * - 고유 ID가 없으면 같은 클릭의 재전송과 서로 다른 두 클릭을 구분할 수 없어 중복 과금을 막지 못합니다.
 */
@Component
public class ImpressionTokens {

    public record Claims(String impressionId, long adId, String userId, int cpc, long issuedAtMillis) {
    }

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public ImpressionTokens(@Value("${ads.token-secret}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String issue(long adId, String userId, int cpc, long issuedAtMillis) {
        String payload = String.join("|", UUID.randomUUID().toString(), Long.toString(adId), userId,
                Integer.toString(cpc), Long.toString(issuedAtMillis));
        String encoded = ENCODER.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        String encoded = token.substring(0, dot);
        String signature = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(encoded).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            String[] parts = new String(DECODER.decode(encoded), StandardCharsets.UTF_8).split("\\|");
            return Optional.of(new Claims(parts[0], Long.parseLong(parts[1]), parts[2],
                    Integer.parseInt(parts[3]), Long.parseLong(parts[4])));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
