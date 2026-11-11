package ads.billing;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 과금 컨슈머 설정. 값과 의미는 application.yml 의 ads.billing 에 있습니다. */
@ConfigurationProperties("ads.billing")
public record BillingProperties(boolean enabled, long pollMs, int batchSize) {
}
