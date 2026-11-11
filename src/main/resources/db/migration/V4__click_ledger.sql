-- 4단계 과금 장부. "클릭 이벤트가 원천이고 예산 차감은 그로부터 파생된다"(CLAUDE.md 3-11).

-- 클릭 한 건의 확정 결과. 과금이든 무과금이든 결과를 확정하면 한 줄을 남긴다.
CREATE TABLE click_ledger (
    click_id   TEXT        PRIMARY KEY,   -- 멱등성: 같은 클릭은 한 줄만. 두 번째 INSERT는 유니크 위반 = "이미 처리됨"
    entry_id   TEXT        NOT NULL,      -- 이 결과를 낸 스트림 항목("파티션:ID"). 재배달(ACK 전에 죽음)과 재전송(같은 clickId의 새 항목)을 구분한다
    ad_id      BIGINT      NOT NULL REFERENCES ads (id),
    user_id    TEXT        NOT NULL,
    cpc        INT         NOT NULL,      -- 토큰 안의 낙찰 CPC(서빙 때 옥션이 정한 값)
    amount     INT         NOT NULL,      -- 실제 과금액. 무과금이면 0
    result     TEXT        NOT NULL,      -- CHARGED | INVALID_CLICK | BUDGET_EXHAUSTED
    event_time TIMESTAMPTZ NOT NULL,      -- 클릭이 일어난 시각. 10초 규칙은 이 시각으로 판단한다
    settled_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK ((result = 'CHARGED' AND amount = cpc) OR (result <> 'CHARGED' AND amount = 0))
);

-- 재전송된 클릭(이미 장부에 있는 clickId가 새 스트림 항목으로 다시 온 것). 사유 DUPLICATE의 근거.
CREATE TABLE duplicate_clicks (
    entry_id    TEXT        PRIMARY KEY,  -- 재전송 항목 하나가 재배달돼도 한 줄만
    click_id    TEXT        NOT NULL REFERENCES click_ledger (click_id),
    ad_id       BIGINT      NOT NULL REFERENCES ads (id),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 장부가 예산의 유일한 원천이 되도록 맞춘다.
-- V2 시드는 광고 5를 "예산 5000, 이미 5000 씀"으로 넣었지만 그 5000원에 해당하는 장부 줄이 없다.
-- 장부 합계로 예산을 복구하면 광고 5가 되살아나므로, 같은 뜻(남은 예산 0)을 "예산 0, 쓴 돈 0"으로 다시 적는다.
-- 적용된 V2 파일은 고치지 않고 새 버전으로 정정한다(docs/pam-infra.md 3-4).
UPDATE ads SET budget = 0, spent = 0 WHERE id = 5;
