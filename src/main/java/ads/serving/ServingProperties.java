package ads.serving;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 서빙 설정값. 값과 의미는 application.yml 의 ads.serving 에 있습니다. */
@ConfigurationProperties("ads.serving")
public record ServingProperties(
        int timeoutMs,
        List<Integer> adPositions,
        int listSize,
        int reserveCpc,
        int minIncrement,
        double personalizationBoost,
        int frequencyCapPerHour,
        int circuitFailureThreshold,
        long circuitOpenMs,
        int maxDebugDelayMs) {
}
