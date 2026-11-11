# 기존 pick-a-meme 인프라 활용 안내

CLAUDE.md가 "무엇을 어떻게 만들 것인가"라면, 이 문서는 "이미 있는 것을 어떻게 가져다 쓸 것인가"입니다.
pick-a-meme(이하 PAM)은 같은 소유자가 운영하던 서비스이고, 내릴 예정입니다. 그 인프라와 코드에서 검증된 부분을 이 저장소에 옮겨 씁니다.

- 개발과 장애 시나리오 테스트는 **로컬 `docker compose`** 에서 합니다. 배포는 CLAUDE.md 4장 범위의 0단계(뼈대와 첫 배포)에서 시작하므로, 이 문서의 5장도 처음부터 봅니다.
- 3장(코드 패턴)은 처음부터 참고합니다. PAM 코드는 Kotlin이고 이 저장소는 Java라서, 복사하지 말고 **규칙과 순서만** 가져옵니다.
- [확인됨]은 PAM 저장소의 코드, 설정, 운영 문서로 확인한 내용, [확인 필요]는 아직 근거가 없는 내용입니다.

---

## 1. PAM 인프라 현황 [확인됨]

| 구성 | 내용 |
|---|---|
| 서버 | OCI VM.Standard.A1.Flex (ARM, aarch64), Ubuntu 24.04, 1 OCPU, **RAM 1GB + 스왑 4GB**, 춘천 리전 |
| 서버 위 구성 | Docker 컨테이너 하나(`pam-backend`, 8080) + 앞단 Nginx 리버스 프록시 |
| 인증서 | Let's Encrypt(certbot `--nginx`), 서브도메인마다 따로 발급 |
| 앞단 | Cloudflare 프록시. SSL 모드 Full (Strict), 최소 TLS 1.2 |
| 방화벽 | OCI Security List에서 80/443은 Cloudflare IPv4 대역(15개)만 허용 |
| DB | Neon PostgreSQL 17 (싱가포르) |
| Redis | Upstash Redis (싱가포르) |
| 파일 | Cloudflare R2 (이미지 전용 서브도메인) |
| 빌드, 배포 | GitHub Actions에서 이미지 빌드 → GHCR push → SSH로 서버 접속 → `docker pull` + `docker run` |
| 프론트엔드 | Vercel (Next.js). PAM 개발 블로그도 여기서 서빙 중 |

---

## 2. 로컬과 서버의 역할 나누기

- 로컬에서는 compose의 Postgres, Redis만 씁니다. Neon, Upstash에 붙지 않습니다. 장애 시나리오 테스트에서 컨테이너를 죽이고 살려야 하는데, 외부 서비스로는 그게 안 됩니다.
- 서버 배포본만 Neon, Upstash를 씁니다. 로컬 테스트에서 증명한 규칙이 실제 원격 저장소의 지연과 한도 아래에서도 동작하는지 서버에서 확인합니다.
- 운영 값(연결 문자열, 토큰)은 이 저장소에 쓰지 않습니다. PAM에서는 서버의 env 파일로 주입했습니다(5장).

---

## 3. 가져올 코드 패턴

PAM에서 실제로 문제를 겪고 고친 패턴입니다. 경로는 PAM 저장소의 `pam-backend/` 기준입니다.

### 3-1. Redis 원자 연산은 Lua 스크립트로 [확인됨]

- PAM의 요청 제한(`pam-api/.../ratelimit/RateLimitFilter.kt`)은 `INCR`과 "처음일 때만 `EXPIRE`"를 Lua 스크립트 하나로 실행합니다.
- 처음에는 두 명령을 따로 보냈습니다. 그 사이에 앱이 죽거나 요청이 겹치면 `EXPIRE`가 빠지고, 만료 없는 키가 남습니다. 그래서 묶었습니다(PAM `docs/known-issues.md`의 Lua 도입 항목).
- 이 저장소에서 쓸 곳: 서빙의 소진 표시, "읽고 비교하고 쓰기"가 필요한 모든 Redis 연산. 명령 두 개 이상이 한 덩어리여야 하면 Lua로 묶습니다.

### 3-2. 장애 시 정책을 먼저 정한다 (fail-open / fail-closed) [확인됨]

- PAM은 Redis가 죽었을 때 요청 제한을 통과시킬지 막을지를 설정(`RateLimitProperties.kt`의 `failClosed`)으로 분리했습니다.
- 이 저장소는 경로마다 답이 다릅니다. CLAUDE.md 3-2의 원칙과 맞춥니다.
  - 서빙: Redis가 느리거나 죽으면 광고 없이 응답(fail-open, 페이지 우선)
  - 과금: 처리하지 못한 클릭은 기록하지 않고 재시도(결과를 확정하지 않음)
- 코드에 정책을 숨기지 말고, 클래스 맨 위 주석에 "Redis가 죽으면 이 클래스는 어떻게 하는가"를 씁니다.

### 3-3. 커밋이 먼저, 그다음에 바깥에 알린다 [확인됨]

- PAM은 하트 차감에 분산 락(Redisson, `pam-infrastructure/.../lock/RedissonLockManager.kt`)을 썼습니다. 처음에는 락이 트랜잭션보다 좁아서, 락이 풀린 뒤 커밋되기 전에 다른 요청이 차감 전 값을 읽고 하트가 덜 깎이는 문제(갱신 손실)가 있었습니다. PAM `docs/known-issues.md`의 BUG-12입니다.
- 고친 방식: 락이 트랜잭션을 감쌉니다(`withLock { transactionTemplate.execute { ... } }`, `MemeComposeService.kt`).
- 이 저장소는 락 대신 파티션을 쓰지만 같은 원칙이 적용됩니다. **DB 커밋이 끝난 뒤에 ACK** 합니다(CLAUDE.md 3-11). 커밋 전에 ACK하면 PAM의 "커밋 전에 락 해제"와 같은 종류의 사고가 납니다.

### 3-4. Flyway 규칙 [확인됨]

- PAM 설정: 기동 시 자동 마이그레이션, `validate-on-migrate: true`, `baseline-on-migrate: false`.
- 이미 적용된 마이그레이션 파일은 고치지 않습니다. 고치면 체크섬이 달라져 기동이 실패합니다. 바꿀 내용은 새 버전 파일로 추가합니다.
- 이 저장소는 원장 방식이라 테이블 정의가 곧 규칙입니다(`click_id` 유니크 등). 마이그레이션 파일에 제약의 이유를 주석으로 남깁니다.

### 3-5. 클라이언트 IP는 `CF-Connecting-IP` [확인됨]

- Cloudflare 뒤에서는 접속 IP가 전부 Cloudflare 것입니다. PAM은 Nginx가 넘긴 `CF-Connecting-IP` 헤더로 실제 IP를 읽었습니다.
- 이 헤더는 Cloudflare를 거친 요청에서만 믿을 수 있습니다. 그래서 PAM은 로컬 설정(`application-local.yml`)에서 헤더 사용을 껐습니다.
- 이 저장소는 부정 클릭을 사용자 ID로 판단하므로 지금은 필요 없습니다. IP 기준 판단을 추가할 때만 씁니다.

### 3-6. 헬스체크는 Actuator [확인됨]

- PAM은 서버 이전 때 `/actuator/health`로 전 구간(Cloudflare → Nginx → 앱) 연결을 확인했습니다. PAM 배포 스크립트에는 헬스체크가 없습니다.
- 이 저장소는 compose의 `healthcheck`와 배포 스크립트 마지막 단계에서 같은 경로를 씁니다. 헬스체크가 실패하면 배포 작업도 실패로 끝나게 합니다.

---

## 4. 이 저장소에 맞지 않는 PAM의 선택

| PAM | 이 저장소 | 이유 |
|---|---|---|
| Kotlin, 멀티 모듈 클린 아키텍처 | Java 21, 단일 모듈 | CLAUDE.md 2장 "흐름이 한눈에 보이는 코드" |
| 분산 락(Redisson)으로 동시성 제어 | 파티션(`hash(adId) % 4`)으로 단일 처리 | CLAUDE.md 3-10, 3-11. 락은 대조 실험에서만 |
| Upstash Redis (요청 제한, 락만 사용) | Redis Streams를 큐로 사용 | 아래 5-4 참고 |

---

## 5. 배포 (0단계에서 시작)

PAM 서버를 그대로 씁니다. 새로 만드는 것은 컨테이너 하나와 워크플로 하나입니다.
기능을 다 만든 뒤 올리면 메모리, 아키텍처, 원격 저장소 지연 문제를 마지막 날에 한꺼번에 만나게 됩니다. 그래서 빈 뼈대를 먼저 올리고, 이후 단계는 머지할 때마다 서버에 올라가게 합니다.

### 5-1. 먼저 PAM을 내린다

1GB 서버에 JVM 두 개는 올라가지 않습니다. 순서가 중요합니다.

1. ~~PAM 저장소의 `.github/workflows/deploy-backend.yml` 자동 배포 끄기~~ **완료(PAM PR #178).** 트리거를 수동 실행(`workflow_dispatch`)으로 바꿨습니다. 수동으로 다시 돌리면 `pam-backend`가 8080을 차지하므로, 그 전에 이 저장소의 컨테이너를 먼저 내립니다.
2. 서버에서 `pam-backend` 컨테이너를 정지, 삭제합니다.
3. 그다음에 이 저장소의 컨테이너를 띄웁니다.

**결정됨: 서버만 내립니다.** PAM의 Neon, Upstash, R2, Vercel 자원은 지우지 않고 둡니다.
PAM 프론트엔드(Vercel)도 내리지 않습니다. PAM 개발 블로그(`pam-frontend/public/blog`)가 같은 Vercel 프로젝트에서 서빙되기 때문입니다.

- 블로그는 정적 HTML이라 백엔드 없이도 그대로 동작합니다. 그래서 옮기지 않고 기존 URL을 유지합니다.
- 앱 화면은 뜨지만 API 서브도메인이 이 저장소로 넘어오므로 기능은 동작하지 않습니다(PAM API 경로는 404).
- PAM 도메인은 블로그 주소이므로 계속 갱신합니다. 끊기면 블로그 링크도 같이 끊깁니다.

### 5-2. 이름은 전부 새로 짓는다

PAM 이름을 재사용하면 서로 지웁니다.

| 항목 | PAM (재사용 금지) | 이 저장소 예시 |
|---|---|---|
| 컨테이너 이름 | `pam-backend` | `ads-server` |
| GHCR 이미지 | `ghcr.io/<owner>/pam-backend` | `ghcr.io/<owner>/ads-server` |
| 서버 env 파일 | `~/pam.env` | `~/ads.env` |
| Redis 키 접두사 | `pam:rate-limit:*`, `lock:*` | `ads:*` |

- PAM 워크플로의 `cleanup-ghcr` 작업은 `pam-backend` 패키지의 오래된 버전을 지웁니다(최근 2개만 유지). 같은 패키지 이름을 쓰면 이 저장소의 이미지가 지워집니다.

### 5-3. 워크플로는 PAM 것을 본떠 만든다

PAM의 `deploy-backend.yml` 구조(빌드 → GHCR push → SSH pull/run → 오래된 이미지 정리)를 그대로 따르고, 5-2의 이름만 바꿉니다.

- 서버에서 빌드하지 않습니다. PAM이 처음에 서버에서 빌드했을 때 1GB 서버로 38분이 걸렸고, GitHub Actions 빌드로 바꾼 뒤 약 3분이 됐습니다.
- GitHub Secrets는 저장소 단위라서 이 저장소에 새로 등록해야 합니다: `OCI_HOST`, `OCI_USER`, `OCI_SSH_PRIVATE_KEY`. GHCR 로그인은 워크플로의 `GITHUB_TOKEN`으로 됩니다.
- Dockerfile은 PAM과 같은 2단계 구성(`eclipse-temurin:21-jdk-alpine`으로 빌드, `eclipse-temurin:21-jre-alpine`으로 실행)을 쓰면 Java 21과 맞습니다.
- **[확인 필요, 0단계 첫 배포 전] CPU 아키텍처.** 서버는 ARM(aarch64)인데 PAM 워크플로는 `platforms`를 지정하지 않고 GitHub의 x86 러너에서 빌드합니다. 이대로면 x86 이미지가 만들어집니다. 서버에서 `uname -m`과 `docker image inspect --format '{{.Architecture}}' <이미지>`로 실제 상태를 확인하고, 필요하면 `docker/setup-qemu-action` + `platforms: linux/arm64`를 추가합니다.

### 5-4. DB와 Redis

- 서버에 Postgres, Redis 컨테이너를 같이 띄우지 않습니다. 1GB에 JVM과 함께 올리면 스왑으로 버티게 됩니다. Neon, Upstash를 씁니다.
- Neon: PAM 데이터베이스와 **분리**합니다(새 데이터베이스나 새 프로젝트). 같은 DB를 쓰면 Flyway 이력 테이블(`flyway_schema_history`)이 겹쳐 버전 번호가 충돌합니다.
- Upstash: PAM을 완전히 내리면 같은 인스턴스를 써도 되지만, 그래도 키 접두사는 `ads:`로 둡니다.
- **[확인 필요, 첫 배포 전] Upstash와 Redis Streams.** PAM은 Redis를 요청이 올 때만 썼습니다. 이 저장소의 과금 컨슈머는 스트림 4개를 계속 읽으므로 요청이 없어도 명령이 나갑니다.
  - 4단계에서 `XREADGROUP BLOCK`은 쓰지 않기로 했습니다. 짧게 읽고 `poll-ms`만큼 쉬는 방식이라 명령 수를 직접 조절합니다(`docs/tradeoffs.md` 17).
  - 한가할 때 명령 수 = 파티션 4 × (86,400초 ÷ poll 초). 기본값 1초면 하루 약 345,600개, `BILLING_POLL_MS=10000`이면 약 34,560개입니다.
  - 5단계 집계 컨슈머(그룹 stats)도 같은 계산으로 따로 명령을 씁니다. `EVENT_LOG_POLL_MS=10000`이면 하루 약 34,560개, 둘을 합치면 약 69,120개입니다.
  - Upstash 무료 플랜의 현재 명령 수 한도는 배포 전에 요금 문서에서 확인하고 `BILLING_POLL_MS`를 정합니다. 과금은 측정 경로라 10초 늦어도 됩니다.

### 5-5. 도메인

**결정됨: PAM의 API 서브도메인을 그대로 이어 씁니다.**

- Cloudflare DNS, Nginx `server` 블록, 인증서는 손대지 않습니다. Nginx가 `localhost:8080`으로 넘기므로 앱 컨테이너를 호스트 8080 포트로 띄웁니다.
- 나중에 서브도메인을 새로 쓰게 되면 세 곳을 같이 설정합니다: Cloudflare DNS 레코드(프록시 켬), Nginx `server` 블록(`proxy_pass http://localhost:8080`, `CF-Connecting-IP` 전달), certbot 인증서 발급.

### 5-6. 서버와 원격 저장소 사이 지연 [확인 필요, 0단계에서 측정]

- 서버는 춘천, Neon과 Upstash는 싱가포르입니다. 서빙 경로는 50ms 안에 끝나야 하는데(CLAUDE.md 4장), 요청마다 Upstash에서 소진 표시를 읽고 Neon에서 광고 후보를 읽으면 왕복 시간만으로 이 예산을 넘길 수 있습니다.
- 0단계에서 서버 위의 앱이 Neon, Upstash에 간단한 명령을 보내는 왕복 시간을 재서 `docs/tradeoffs.md`에 남깁니다.
- 예산을 넘긴다면 서빙은 광고 후보와 소진 표시를 앱 메모리에 두고 주기적으로 새로 고치는 방식으로 바꿉니다. 그러면 소진이 반영되기까지 새로 고침 주기만큼 지연이 생기고, 이것이 CLAUDE.md 4장의 "소진 표시 지연 동안의 초과 집행" 시나리오와 이어집니다.

### 5-7. 첫 배포 전 준비

에이전트가 할 수 없는 일(서버 접속, 외부 서비스 콘솔, 저장소 시크릿)은 사용자가 합니다.

| 할 일 | 누가 |
|---|---|
| 이 저장소에 `OCI_HOST`, `OCI_USER`, `OCI_SSH_PRIVATE_KEY` 시크릿 등록 | 사용자 |
| Neon에 이 저장소용 새 데이터베이스 생성 | 사용자 |
| 서버에 `~/ads.env` 작성(아래 형식) | 사용자 |
| PAM 저장소의 `deploy-backend.yml` 비활성화 | 완료(PAM PR #178) |
| 서버에서 `pam-backend` 컨테이너 정지, 삭제 | 사용자 |
| 뼈대, Dockerfile, 배포 워크플로 작성 | 에이전트(0단계 PR) |

CPU 아키텍처는 따로 확인하지 않아도 됩니다. 이미지를 amd64, arm64 둘 다 만들고, 배포 로그 마지막에 `uname -m`이 찍힙니다.
배포 워크플로는 `pam-backend` 컨테이너가 떠 있으면 8080 충돌을 막기 위해 실패로 끝납니다.

`~/ads.env` 형식(값은 이 저장소 어디에도 쓰지 않습니다):

```
DB_URL=jdbc:postgresql://<Neon 호스트>/<새 데이터베이스>?sslmode=require
DB_USERNAME=<Neon 사용자>
DB_PASSWORD=<Neon 비밀번호>
REDIS_URL=rediss://default:<Upstash 비밀번호>@<Upstash 호스트>:6379
TOKEN_SECRET=<임의의 긴 문자열, 노출 토큰 서명 키>
BILLING_POLL_MS=10000
EVENT_LOG_POLL_MS=10000
```

`chmod 600 ~/ads.env`로 다른 사용자가 읽지 못하게 둡니다.

---

## 6. 알려진 보안 공백 [확인됨]

0단계 첫 배포 전에 알고 있어야 하는 것입니다.

- **오리진 IP가 공개되어 있습니다.** PAM 저장소의 환경 문서와 개발 블로그 글에 서버 IP가 적혀 있습니다. 80/443은 Cloudflare 대역만 허용하므로 직접 접속은 막히지만, 서버 위치는 알려진 상태입니다. 이 저장소의 어떤 파일에도 IP를 쓰지 않습니다.
- **SSH 22번 포트가 전체 공개(0.0.0.0/0)입니다.** 키 인증을 쓰고 있지만, PAM 문서에도 비밀번호 인증이 꺼져 있는지는 "확인 필요"로 남아 있습니다. 배포 워크플로가 GitHub Actions 러너에서 SSH로 접속하므로, 막으려면 접속 방식도 같이 바꿔야 합니다.

---

## 7. 확인 필요 목록

| 항목 | 확인 방법 |
|---|---|
| 서버 CPU 아키텍처 | 0단계 배포 로그의 `uname -m` |
| 서버 ↔ Neon, Upstash 왕복 시간 | 5-6 (0단계) |
| 1GB 서버에서 앱 메모리 사용량과 적절한 힙 상한 | 0단계 배포 후 `docker stats` |
| SSH 비밀번호 인증이 꺼져 있는지 | 서버에서 `sudo sshd -T \| grep passwordauthentication` |
| Upstash 무료 플랜에서 Redis Streams 컨슈머 4개를 상시 돌릴 수 있는지 | Upstash 요금 문서, 로컬에서 시간당 명령 수 측정 |
| 각 서비스의 현재 무료 한도(Neon 용량, R2, GHCR) | 각 서비스 대시보드 |
