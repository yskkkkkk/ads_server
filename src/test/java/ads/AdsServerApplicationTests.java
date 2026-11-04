package ads;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * 무엇을 재현하나: 앱을 실제 Postgres, Redis(로컬은 docker compose, CI는 서비스 컨테이너)에 붙여 띄웁니다.
 * 무엇을 증명하나: 배포 워크플로가 서버에서 확인하는 것과 같은 /actuator/health 가 UP 입니다.
 *                  여기서 실패하면 서버에 올려도 실패하므로, 배포 전에 걸러냅니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdsServerApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @Test
    void 앱이_Postgres와_Redis에_붙어서_뜨고_헬스체크가_UP이다() {
        String body = rest.getForObject("/actuator/health", String.class);

        assertThat(body).contains("\"status\":\"UP\"");
    }
}
