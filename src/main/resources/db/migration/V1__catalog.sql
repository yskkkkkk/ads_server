-- 1단계 서빙이 읽는 두 테이블입니다.
-- 광고 상품도 결국 일반 상품 중 하나라서, ads는 products를 가리킵니다.

-- 일반 상품(오가닉 피드). 광고가 없어도 페이지는 이 목록으로 채워집니다.
CREATE TABLE products (
    id         BIGINT PRIMARY KEY,
    name       TEXT    NOT NULL,
    brand      TEXT    NOT NULL,
    category   TEXT    NOT NULL,
    price      INT     NOT NULL,
    popularity INT     NOT NULL  -- 일반 목록 정렬 기준(인기순)
);

-- 광고: 광고주가 "이 상품을 클릭당 최대 bid원에 띄워 달라"고 건 것.
CREATE TABLE ads (
    id              BIGINT PRIMARY KEY,
    product_id      BIGINT       NOT NULL REFERENCES products (id),
    bid             INT          NOT NULL CHECK (bid > 0),          -- CPC 입찰가(원). 클릭 한 번에 낼 수 있는 최대 금액
    base_pctr       NUMERIC(6,4) NOT NULL CHECK (base_pctr > 0),    -- 예측 클릭률 기본값. 실제 서비스에서는 ML 모델이 요청마다 계산
    platform_weight NUMERIC(4,2) NOT NULL DEFAULT 1.00,             -- 플랫폼 가중치: 마진, 프로모션을 반영한 배수. 1.00이면 순수 eCPM 랭킹
    budget          INT          NOT NULL CHECK (budget >= 0),      -- 총 예산(원)
    spent           INT          NOT NULL DEFAULT 0 CHECK (spent >= 0) -- 지금까지 과금액. 4단계 과금 컨슈머만 바꿉니다
);
