# 미니 광고 서버

커머스 상품 광고(CPC)를 서빙하고, 노출과 클릭과 구매를 수집하고, 클릭마다 과금하고, 그 결과가 서로 맞는지 대사하는 학습용 광고 서버입니다.
목표는 광고 한 건이 나가서 돈이 되기까지의 흐름을 처음부터 끝까지 직접 만져 보는 것입니다.
배경 지식과 작업 원칙은 [CLAUDE.md](./CLAUDE.md), 용어는 [docs/keywords.md](./docs/keywords.md), 결정 기록은 [docs/tradeoffs.md](./docs/tradeoffs.md)에 있습니다.

## 전체 흐름

광고 한 건은 두 갈래 경로를 지납니다. **서빙 경로는 빨라야 하고, 측정 경로는 정확해야 합니다.**

```
[서빙 경로: 동기, 50ms 예산]                                        단계, 패키지
  데모 페이지 ── GET /ads?slot&category&userId ──▶ 광고 서버          1  serving/
     ① 후보 추리기  카테고리 일치, 예산 남음, 소진 표시 없음, 피로도 상한 미만
     ② 예측        pCTR = 기본값 × 사용자 신호 보정(최근 본 브랜드)
     ③ 랭킹        점수 = eCPM(입찰가 × pCTR × 1000) × 플랫폼 가중치(마진, 프로모션)
     ④ 옥션        2등가: 낙찰 CPC = 2등 점수 ÷ (내 pCTR × 1000 × 내 가중치) + 10원, 최저가 이상
     ⑤ 블렌딩      일반 상품 목록 2, 6번째 칸에 광고, 같은 상품 중복 제거
     ⑥ 응답        광고마다 노출 토큰(서명된 고유 ID, 광고 ID, 낙찰 CPC, 발급 시각)
     50ms를 넘기면 일반 상품만 응답(채움률 하락), 계속 넘기면 서킷 브레이커가 광고 조회를 건너뜀

[측정 경로: 비동기, 늦어도 되지만 잃거나 두 번 세면 안 됨]
  ⑦ DOM에 그려짐       ── POST /events/impression {token, RENDERED} ─┐  2  event/
     화면에 50% 1초 보임 ── POST /events/impression {token, VIEWABLE} ─┤
  ⑧ 클릭(sendBeacon)   ── GET /events/click?token ──────────────────┤  토큰 검증, 봇(GIVT) 거름
        └─▶ 상품 페이지로 리다이렉트, URL에 토큰을 이어 붙임            │
        클릭은 사용자별 클릭 기록(ads:clicks:{사용자})에도 남음                ▼
                       Redis Streams: hash(adId) % 4 개 스트림(파티션 흉내)
                                                                     │
  ⑩ 과금 컨슈머(파티션마다 하나)                                       ▼  4  billing/
       재전송? → 부정 클릭(10초)? → 예산 부족? → 과금
       장부 INSERT(click_id 유니크) + 예산 UPDATE = 한 트랜잭션, 커밋 후 ACK
       소진되면 Redis에 소진 표시 ─▶ 서빙 ①이 읽음
                                                                     │
  ⑨ 구매 ── POST /events/conversion {주문, 상품, 금액, token?, userId?}       3  conversion/
       구매자 찾기: URL 토큰의 사용자(TOKEN) 또는 로그인 사용자(USER_HISTORY, 클로즈드 루프)
       그 사용자 클릭 기록에서 같은 상품, 구매 전 7일 안의 마지막 클릭에 귀속 → conversions 테이블
       (주문 ID 기본 키로 같은 주문은 한 번만)
                                                                     │
     대사와 성과 리포트                                                ▼  5  reconcile/
       클릭 수 = 과금 + 무과금(사유별), 장부 합계 = 예산 차감액
       광고별 노출(렌더링, viewable), CTR 두 기준, 전환, 광고비, ROAS
```

## 개념이 어디에 구현되나

CLAUDE.md 3장의 개념을 전부 올립니다. 구현하지 않는 개념도 왜 하지 않는지 적어, "그건 어떻게 하나요?"라는 질문에 답할 근거로 씁니다.

### 랭킹과 옥션 (1단계)

| 개념 | 어떻게 만져 보나 | 상태 |
|---|---|---|
| 입찰가 1등 ≠ 광고 1등, eCPM (3-3, 3-12) | 입찰가 1000원 runway가 500원 streetfit에게 짐 (`Auction`) | 완료 |
| 2등가 옥션, 최저가 (3-4) | streetfit은 입찰가 500원인데 410원을 냄, 입찰가 80원 daily는 탈락 (`Auction`, `CandidateFilter`) | 완료 |
| 퍼스트파티 데이터 반영 (3-12) | runway를 본 사용자에게는 runway가 1등 (`Prediction`, `UserSignals`) | 완료 |
| 다목적 랭킹: 마진, 프로모션 (3-12) | eCPM 8,000인 mono가 가중치 1.5로 runway(10,000)를 이김 (`Auction`) | 완료 |
| 블렌딩, 중복 제거 (3-12) | 광고 상품 102, 103이 일반 목록에서 빠짐 (`Blender`) | 완료 |
| 광고 피로도, frequency cap (3-12) | 상한을 넘은 광고는 그 사용자에게 안 나감 (`CandidateFilter`). 횟수 올리기는 2단계 | 완료 |
| 후보 스냅샷(메모리 카탈로그) | 서빙은 DB를 부르지 않고 5초마다 새로 읽은 스냅샷을 씀 (`Catalog`) | 완료 |
| 노출 토큰: 모든 이벤트는 서빙 결정에 묶인다 (3-2) | 광고마다 HMAC 서명 토큰 발급, 토큰 안의 CPC = 낙찰 CPC (`ImpressionTokens`). 검증은 2·3단계 | 완료 |
| 서빙 시간 예산, 채움률, 서킷 브레이커 (3-2) | `debugDelayMs=80`이면 일반 상품만 응답, 3번 연속이면 서킷 열림 (`ServingService`, `CircuitBreaker`) | 완료 |
| 원격 저장소 왕복 시간 | 기동 로그 `[latency]`(`common/LatencyProbe`) | 완료 |

### 측정 (2단계)

| 개념 | 어떻게 만져 보나 | 상태 |
|---|---|---|
| 렌더링 노출 vs viewable 노출, 50% 1초 (3-6, 3-13) | 데모 페이지가 두 이벤트를 따로 보냄. 화면 아래 광고는 스크롤해서 1초 머물러야 VIEWABLE (`demo.js`) | 완료 |
| 화면 전환 경쟁 상태, sendBeacon (3-13) | 데모에서 fetch 모드 클릭은 서버에 0건 도착, sendBeacon과 클릭 URL은 전부 도착 (`demo.js`) | 완료 |
| 클라이언트 집계와 서버 집계 대사 (3-6) | 데모의 "브라우저 보냄 vs 서버 도착" 표 | 완료 |
| 파라미터 체이닝 (3-13) | 클릭 URL이 상품 페이지로 리다이렉트하며 토큰을 이어 붙임, 끄면 상품 페이지에 "광고 유입 정보 없음" (`EventController`) | 완료 |
| 최소 한 번 전달, 고유 ID로 중복 제거 (3-6, 3-10) | 같은 노출 두 번 → 한 번만 셈. 클릭은 과금 단계에 맡김 (`EventCollector`, `collect_event.lua`) | 완료 |
| 측정 → 서빙 되먹임(피로도) (3-12) | 렌더링 노출 3번이면 그 광고는 그 사용자에게 더 안 나감 | 완료 |
| GIVT (3-8) | 봇 User-Agent는 스트림에 넣지 않고 따로 셈. 헤드리스 브라우저도 걸림 (`BotFilter`) | 완료 |
| 파티션 키 (3-10) | `hash(adId) % 4` 스트림 `ads:events:0~3` (`Partitions`) | 완료 |
| 광고 차단, 1st-party 서브도메인 (3-13) | 배포 구조가 자기 서브도메인 + Nginx. 데모에서 광고 차단 확장을 켜고 무엇이 막히는지 확인(수동) | 수동 시나리오 |
| 쿠키 제약, iOS ITP (3-13) | 쿠키를 쓰지 않고 URL 토큰으로 유입을 넘김. 그 대가가 파라미터 체이닝 단절 | 설명만 |

### 전환 (3단계)

| 개념 | 어떻게 만져 보나 | 상태 |
|---|---|---|
| 어트리뷰션, 마지막 클릭, 어트리뷰션 윈도우 (3-7) | 같은 상품 광고 클릭 중 구매 전 7일 안의 마지막 것. 정확히 7일은 귀속, 1초 넘으면 OUTSIDE_WINDOW (`Attribution`) | 완료 |
| 체이닝 단절의 결과 (3-13) | 토큰이 빠지고 로그인도 없으면 NONE, "광고와 무관한 매출"로 남아 ROAS가 낮게 잡힘 | 완료 |
| 클로즈드 루프 (3-1) | 토큰이 빠져도 로그인 사용자면 클릭 기록으로 다시 이어 귀속(USER_HISTORY) (`ConversionService`) | 완료 |
| 전환 멱등성 | 같은 주문 ID 두 번 → 매출 한 번 (`conversions.order_id` 기본 키) | 완료 |
| 할로 효과(같은 브랜드 다른 상품 구매) (3-7) | 같은 상품만 귀속. 다른 상품 구매는 NO_CLICK | 설명만 |
| view-through 전환 (3-7) | 클릭 없는 노출 후 구매는 귀속하지 않음. 귀속 규칙을 공개해야 한다는 가이드라인과 함께 설명 | 설명만 |

### 과금, 대사, 성과 (4·5단계)

| 개념 | 어떻게 만져 보나 | 상태 |
|---|---|---|
| CPC 과금, 멱등성, 부정 클릭(SIVT 흉내) (3-8, 3-11) | 기본 예시 c1~c7을 순수 규칙과 실제 스트림 둘 다로 재현. 결과 A=400, B=0 (`ClickBilling`, `BillingConsumer`) | 완료 |
| 커밋 후 ACK, 재배달 vs 재전송 (3-10, 3-11) | 커밋 직후 ACK 전에 죽고 재시작해도 과금 1번. 같은 스트림 항목이면 재배달(ACK만), 같은 clickId의 새 항목이면 재전송(DUPLICATE) | 완료 |
| 메모리 상태는 장부의 사본 (3-11) | 재시작하면 잔액과 10초 창을 장부에서 다시 만듦 | 완료 |
| 예산 소진, 소진 표시 지연 (3-5) | 소진 표시 후에도 스냅샷이 바뀌기 전에 나간 광고의 클릭은 BUDGET_EXHAUSTED(미청구) | 완료 |
| 이벤트 시간 vs 처리 시간 (3-10) | 늦게 도착한 정상 클릭이 무과금되는 한계를 테스트로 고정 | 완료(한계 기록) |
| 파티션으로 단일 처리 (3-10, 3-11) | 한 파티션을 컨슈머 둘이 나눠 읽으면 예산 300에 600 과금, 하나면 300 | 완료 |
| 대사, 원장 (3-9) | 수집 클릭 = 장부 + 재전송, 장부 합계 = 쓴 돈. OK / LAGGING(처리 대기) / MISMATCH(돈 불일치) (`ReconcileCheck`, `/reports/reconciliation`) | 완료 |
| 컨슈머 그룹 여러 개 (3-10) | 과금(billing)과 집계(stats)가 같은 스트림을 각자 읽음. 집계는 순서가 상관없어 컨슈머 하나 (`EventLogConsumer`) | 완료 |
| CTR, 노출 정의가 CTR을 바꿈 (3-3) | 같은 클릭 2 ÷ 렌더링 8 = 25%, ÷ viewable 4 = 50% (`/reports/performance`) | 완료 |
| ROAS (3-3) | 실제(장부 광고비) 약 217배 vs 추정(과금 규칙 전) 약 108배. 차이 = 미청구 금액 (`/reports/performance`) | 완료 |

### 하지 않는 것

| 개념 | 이유 |
|---|---|
| pCTR 머신러닝 모델 (3-12) | 모델이 아니라 "예측값이 랭킹에 어떻게 쓰이나"가 목표. 기본값 × 규칙 보정으로 대신함 |
| DSP, SSP, OpenRTB (3-1) | 자기 지면에 자기 광고를 내는 리테일 미디어 구조라 외부 경매가 없음. 50ms 서빙 예산이 OpenRTB 타임아웃(tmax)과 같은 역할 |
| 예산 집행 속도 조절(pacing) (3-5) | 기한 안에 흐름 전체를 먼저 통과하기 위해 뺌. 피로도 상한, 소진 표시와 같은 "후보 필터" 자리에 들어감 |
| 핫 파티션 해소 (3-10) | 키를 쪼개면 예산도 나눠 맞춰야 해서 단순함을 잃음. 설명만 |
| Affiliate (3-7) | 광고 서버가 아닌 별도 정산 흐름 |
| 장기 정산 워크플로(Temporal) (3-9) | 한 DB 안에서 끝나는 클릭 과금에는 불필요 |

## 도식

개념을 설명하는 도식은 `docs/diagrams/`에 HTML로 둡니다(브라우저로 열면 확대, 경로 강조, 테마 전환이 됩니다).
에이전트는 [Archify](https://github.com/tt-a1i/archify) 스킬을 **설치하지 않고** 세션마다 `v3.2.0`을 임시 폴더에 받아 이것으로 도식을 만듭니다. 방법과 그릴 목록은 [CLAUDE.md](./CLAUDE.md) 4장 "도식"에 있습니다.

| 도식 | 단계 | 원본 |
|---|---|---|
| [광고 서빙 한 번: GET /ads](docs/diagrams/serving.html) (시퀀스) | 1 | `docs/diagrams/src/serving.sequence.json` |
| [측정 경로: 노출과 클릭이 스트림에 쌓이기까지](docs/diagrams/measurement.html) (데이터 흐름) | 2 | `docs/diagrams/src/measurement.dataflow.json` |
| [구매는 어느 광고 덕분인가: 클릭에서 귀속까지](docs/diagrams/attribution.html) (시퀀스) | 3 | `docs/diagrams/src/attribution.sequence.json` |
| [클릭 한 건의 과금 판정](docs/diagrams/billing.html) (상태 전이) | 4 | `docs/diagrams/src/billing.lifecycle.json` |
| [테이블과 키](docs/diagrams/schema.html) (ERD) | 4 | `docs/diagrams/src/schema.erd.json` |

GitHub 화면에서는 HTML이 그림으로 보이지 않습니다. 파일을 내려받거나 저장소를 받은 뒤 브라우저로 여세요. 노드를 누르면 근거가 된 코드 위치가 나옵니다.

## 실행

필요한 것: JDK 21, Docker.

```bash
# 저장소(Postgres, Redis)만 띄우고 앱은 Gradle로
docker compose up -d postgres redis
./gradlew bootRun

# 테스트 (compose의 Postgres, Redis가 떠 있어야 함)
./gradlew test

# 앱까지 전부 컨테이너로
./gradlew bootJar && docker compose up --build
```

확인: `curl localhost:8080/actuator/health` → `{"status":"UP"}`

## 배포

main에 머지하면 GitHub Actions가 테스트 → 이미지 push(GHCR) → 서버 배포 → 헬스체크까지 합니다(`.github/workflows/deploy.yml`).
배포 로그 마지막에 서버 ↔ DB, Redis 왕복 시간(`[latency]`), 컨테이너 메모리, 서버 CPU 아키텍처가 찍힙니다.
서버 준비와 구성은 [docs/pam-infra.md](./docs/pam-infra.md) 5장에 있습니다.

## 해 보기

### 1단계: 서빙

앱을 띄운 뒤(`docker compose up -d postgres redis && ./gradlew bootRun`) 차례로 호출해 봅니다.
응답의 `items`는 사용자가 보는 목록, `auction`은 그 뒤에서 일어난 경매표입니다. 시드 숫자는 `src/main/resources/db/migration/V2__seed.sql` 머리 주석에 있습니다.

```bash
# 1) 처음 온 사용자. 입찰가 1등 runway(1000원)가 지고 streetfit(410원), mono(344원)가 낙찰. 2, 6번째 칸이 광고
curl -s 'localhost:8080/ads?category=shoes&userId=u1' | python3 -m json.tool
```

```bash
# 2) u1이 runway 상품을 봤다는 퍼스트파티 신호를 남기고 다시 요청. runway가 1등(760원)
curl -s -X POST 'localhost:8080/signals/viewed-brand?userId=u1&brand=runway'
curl -s 'localhost:8080/ads?category=shoes&userId=u1' | python3 -m json.tool
```

```bash
# 3) 같은 요청, 개인화만 끔. 다시 streetfit이 1등
curl -s 'localhost:8080/ads?category=shoes&userId=u1&personalize=false' | python3 -m json.tool
```

```bash
# 4) 저장소가 80ms 느려졌다고 가정(장애 주입). 시간 예산 50ms를 넘겨 광고 없이 일반 상품만 응답(outcome: TIMEOUT)
curl -s 'localhost:8080/ads?category=shoes&userId=u1&debugDelayMs=80' | python3 -m json.tool
```

```bash
# 5) 4)를 두 번 더 하면 서킷이 열림(CIRCUIT_OPEN). 10초 동안은 지연 없이도 광고를 조회하지 않음
curl -s 'localhost:8080/ads?category=shoes&userId=u1' | python3 -m json.tool
```

```bash
# 6) 채움률: 요청한 광고 자리 중 채운 비율, 결과별 건수, 서킷 상태
curl -s 'localhost:8080/serving/stats' | python3 -m json.tool
```

자동 테스트로 같은 내용을 확인하려면 `./gradlew test --tests 'ads.serving.*'`를 실행합니다. 테스트 이름이 곧 시나리오 이름입니다.

### 2단계: 측정 (데모 페이지)

브라우저로 `http://localhost:8080/demo/index.html` 을 엽니다. 서버에 배포된 뒤에는 API 서브도메인의 같은 경로입니다.
먼저 **측정 시작**을 눌러 숫자를 0부터 세게 합니다.

1. **렌더링 vs viewable**: 목록이 뜨면 광고 카드 두 개가 바로 RENDERED를 보냅니다. 화면 아래쪽 광고는 "아직 viewable 아님"으로 남아 있다가,
   스크롤해서 카드의 절반 이상을 1초 동안 보이게 해야 VIEWABLE을 보냅니다. 1초 전에 스크롤을 치우면 처음부터 다시 셉니다.
   → 노출 수는 같아도 "본 노출"은 적습니다. 5단계에서 두 기준의 CTR이 달라지는 이유입니다.
2. **화면 전환 경쟁 상태**: 클릭 방법을 "fetch 후 바로 이동"으로 바꾸고 광고를 몇 번 눌렀다가 목록으로 돌아와 **서버 숫자 다시 읽기**를 누릅니다.
   CLICK의 "유실" 칸이 누른 횟수만큼 빨갛게 늘어납니다. "sendBeacon"이나 "클릭 URL"로 바꾸면 유실이 0입니다.
   (실측: fetch 3번 → 서버 0건, sendBeacon 3번 → 3건, 클릭 URL 1번 → 1건)
3. **파라미터 체이닝**: 광고를 누르면 상품 페이지 URL에 `adToken`이 붙어 오고, 페이지가 토큰 내용(광고, 낙찰가, 사용자)을 보여줍니다.
   "토큰 이어 붙이기"를 끄고 누르면 "광고 유입 정보가 없습니다"가 나옵니다. 이 상태의 구매가 어떻게 되는지는 3단계에서 봅니다.
4. **측정이 서빙으로 돌아오는 고리**: 같은 사용자로 "목록 새로 받기"를 세 번 누르면, 네 번째부터 같은 광고가 사라지고 "제외 #2 FREQUENCY_CAP"이 보입니다(렌더링 노출 3번 = 피로도 상한).
   상품 페이지를 한 번 보고 오면 그 브랜드 광고가 개인화로 1등이 됩니다.
5. **GIVT**: 봇처럼 보내면 걸러집니다. 토큰은 `GET /ads` 응답에서 복사합니다.
   ```bash
   curl -s -X POST -A 'Googlebot/2.1' "localhost:8080/events/click?token=<토큰>"
   curl -s localhost:8080/events/stats | python3 -m json.tool   # CLICK.givt 가 1 늘어남, 스트림 길이는 그대로
   ```
   헤드리스 브라우저(User-Agent에 HeadlessChrome)로 데모를 열어도 전부 GIVT로 걸립니다. 자동화 도구로 확인하다 실제로 겪은 일입니다.
6. **광고 차단(수동)**: 광고 차단 확장을 켜고 데모를 열어, 개발자 도구 Network 탭에서 `/ads`, `/events/...` 요청이 막히는지 봅니다.

자동 테스트: `./gradlew test --tests 'ads.event.*'`

### 3단계: 전환과 ROAS

데모에서 광고를 누르고 상품 페이지의 **구매하기**를 누릅니다. 결과에 "어느 광고에, 어떤 경로로 귀속됐는지"가 나옵니다.
목록 화면의 "토큰 이어 붙이기"와 상품 페이지의 "로그인 상태로 구매"를 바꿔 가며 세 경우를 비교합니다.

| 토큰 이어 붙이기 | 로그인 | 결과 | 보이는 개념 |
|---|---|---|---|
| 켬 | 끔 | 광고 #2에 귀속, TOKEN 경로 | 파라미터 체이닝으로 이어진 클릭 |
| 끔 | 켬 | 광고 #2에 귀속, USER_HISTORY 경로 | 클로즈드 루프: 토큰이 끊겨도 로그인 구매자의 클릭 기록으로 다시 이음 |
| 끔 | 끔 | 귀속 안 됨(NO_IDENTITY) | 누구의 구매인지 몰라 "광고와 무관한 매출"이 됨 |

세 번 사고 `http://localhost:8080/conversions/stats`를 열면 광고 #2의 귀속 매출 178,000원, 추정 광고비 1,230원(클릭 3번 × 410원),
추정 ROAS 약 145배가 보이고, 귀속되지 않은 매출 89,000원이 따로 보입니다. 셋 다 귀속됐다면 ROAS는 약 217배였습니다.
**체이닝이 한 번 끊긴 것만으로 이 광고의 성과가 3분의 1 낮게 측정**됩니다.

시간 경계(7일)는 브라우저로 기다릴 수 없어 자동 테스트로 확인합니다: `./gradlew test --tests 'ads.conversion.*'`

### 4단계: 과금 컨슈머

앱을 띄우면 파티션마다 과금 컨슈머가 하나씩(4개) 돌며 1초마다 스트림을 읽습니다(`ads.billing.poll-ms`). 로그의 `[billing]` 줄에 클릭마다 판정이 찍힙니다.

```bash
# 1) 광고 토큰 하나를 받는다(첫 광고 #2, 낙찰 CPC 410원)
TOKEN=$(curl -s 'localhost:8080/ads?category=shoes&userId=w1' | python3 -c "import sys,json; print([i for i in json.load(sys.stdin)['items'] if i['type']=='AD'][0]['token'])")
```

```bash
# 2) 같은 클릭 k1을 두 번(재전송), 곧바로 다른 클릭 k2(10초 안)를 보낸다
for c in k1 k1 k2; do curl -s -X POST -A 'Mozilla/5.0' "localhost:8080/events/click?token=$TOKEN&clickId=$c&eventTime=$(date +%s%3N)"; done
```

```bash
# 3) 1~2초 뒤 과금 결과: 광고 #2 charged 1, duplicate 1, invalid_click 1, spent = ledger_charged = 410
curl -s localhost:8080/billing/stats | python3 -m json.tool
```

장애 상황(커밋 후 ACK 전에 죽음, 컨슈머 둘이 한 파티션을 나눠 읽음, 늦게 도착한 클릭, 소진 표시 지연)은 브라우저로 만들기 어려워 자동 테스트로 재현합니다.
테스트 이름이 곧 시나리오입니다: `./gradlew test --tests 'ads.billing.*'`

참고: 테스트는 로컬 DB의 장부와 전환 기록을 지우고 시작합니다. 데모로 쌓은 숫자를 보존하려면 테스트 전에 확인하세요.

### 5단계: 대사와 성과 리포트

데모 쇼핑몰에서 광고를 그리고(스크롤해서 1초 머물면 viewable), 누르고, 사고 나서 `http://localhost:8080/demo/report.html`을 엽니다.
과금 컨슈머(그룹 billing)와 집계 컨슈머(그룹 stats)가 같은 스트림을 각자 1초마다 읽으므로, 몇 초 뒤 **새로 읽기**를 누르면 숫자가 맞춰집니다.

- **대사 표**: 서로 다른 세 출처를 맞춰 봅니다.
  - 수집 클릭(원본 이벤트) = 장부 줄(과금 + 무과금 사유별) + 재전송
  - 장부 합계 = 쓴 돈(예산 차감)
  - 판정: OK = 모두 맞음 / LAGGING = 한쪽 컨슈머가 아직 처리 중(처리 대기 칸) / MISMATCH = 돈이 안 맞음(있으면 안 됨)
- **성과 표**: 광고주가 보는 숫자입니다. CTR은 렌더링 기준과 viewable 기준을 나란히, ROAS는 장부의 실제 광고비 기준과 3단계 추정 광고비 기준을 나란히 보여줍니다.

실측(데모에서 사용자 8명 노출, 2명 클릭 후 구매, 1명은 곧바로 재클릭):
- 대사: 광고 #2 수집 클릭 3 = 장부 3(과금 2, 10초 규칙 1) + 재전송 0, 장부 합계 820원 = 쓴 돈 820원 → **OK**. 미청구 210원
- 성과: 매출 178,000원 ÷ 실제 광고비 820원 = **217배**, 추정 광고비 1,030원 기준 = 173배
- 빠르게 누른 광고 #2는 1초를 채우지 못해 viewable이 0이었습니다. "봤다"는 기준을 지키면 클릭은 있는데 본 노출은 없는 광고가 생깁니다

LAGGING, MISMATCH는 컨슈머를 멈추거나 DB를 직접 고쳐야 나오므로 자동 테스트로 재현합니다: `./gradlew test --tests 'ads.reconcile.*'`
