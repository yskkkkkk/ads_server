-- 5단계 집계용 원본 이벤트. 과금과 같은 스트림을 다른 컨슈머 그룹(stats)으로 읽어 그대로 쌓는다.
-- 광고(ad_id)에 외래 키를 걸지 않는다. 원본 사실 기록이라 광고가 지워져도 남아 있어야 한다.
CREATE TABLE event_log (
    entry_id      TEXT        PRIMARY KEY,   -- "파티션:스트림ID". 재배달돼도 한 줄(멱등)
    type          TEXT        NOT NULL,      -- RENDERED | VIEWABLE | CLICK
    event_id      TEXT        NOT NULL,      -- 노출은 impressionId:종류, 클릭은 clickId(재전송이면 같은 값이 여러 줄)
    impression_id TEXT        NOT NULL,
    ad_id         BIGINT      NOT NULL,
    user_id       TEXT        NOT NULL,
    cpc           INT         NOT NULL,
    event_time    TIMESTAMPTZ NOT NULL
);
CREATE INDEX event_log_ad_type ON event_log (ad_id, type);
