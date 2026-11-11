-- 3단계 전환(구매). 주문 한 건이 한 줄입니다.
CREATE TABLE conversions (
    order_id      TEXT        PRIMARY KEY,           -- 같은 주문은 한 번만 귀속한다(멱등). 재전송된 구매는 INSERT가 무시된다
    user_id       TEXT,                              -- 로그인 사용자. 비로그인 구매면 비어 있다
    product_id    BIGINT      NOT NULL REFERENCES products (id),
    amount        INT         NOT NULL CHECK (amount > 0), -- 구매 금액(원). ROAS의 분자
    ad_id         BIGINT      REFERENCES ads (id),   -- 귀속된 광고. 비어 있으면 "광고 덕분인지 모르는" 매출
    click_id      TEXT,                              -- 귀속 근거가 된 클릭
    attributed_by TEXT        NOT NULL,              -- TOKEN | USER_HISTORY | NONE
    reason        TEXT        NOT NULL,              -- ATTRIBUTED | NO_IDENTITY | NO_CLICK | OUTSIDE_WINDOW
    ordered_at    TIMESTAMPTZ NOT NULL,
    clicked_at    TIMESTAMPTZ
);
