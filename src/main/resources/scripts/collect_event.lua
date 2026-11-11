-- 이벤트 한 건을 받는 일을 한 번에(원자적으로) 처리합니다.
-- 명령을 따로 보내면 그 사이에 앱이 죽거나 같은 이벤트가 동시에 와서 "중복 표시는 됐는데 스트림에는 없음",
-- "스트림에는 두 번" 같은 반쪽 상태가 생깁니다. PAM 요청 제한에서 INCR, EXPIRE를 Lua로 묶은 것과 같은 이유입니다.
--
-- KEYS[1] 중복 제거 키(이 이벤트를 이미 받았나)    ARGV[1] 중복 제거 키 유지 시간(초), 0이면 중복 제거 안 함
-- KEYS[2] 피로도 횟수 키(사용자 × 광고)             ARGV[2] 피로도 창 길이(초), 0이면 횟수 안 올림
-- KEYS[3] 스트림 키(ads:events:{파티션})            ARGV[3] 스트림 최대 길이(대략)
-- KEYS[4] 받은 건수 카운터                          ARGV[4] 클릭 기록 유지 시간(초), 0이면 클릭 기록 안 함
-- KEYS[5] 중복 건수 카운터                          ARGV[5] 클릭 기록 점수(이벤트 시간, ms)
-- KEYS[6] 사용자 클릭 기록(ads:clicks:{사용자})      ARGV[6] 클릭 기록 항목(impressionId|adId|clickId)
-- KEYS[7] 광고별 추정 광고비(ads:est-spend:{광고})   ARGV[7] 낙찰 CPC
--                                                  ARGV[8..] 스트림에 넣을 필드, 값 쌍
-- 반환: 1 = 새로 받음, 0 = 이미 받은 이벤트(중복)

if tonumber(ARGV[1]) > 0 then
  if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
    redis.call('INCR', KEYS[5])
    return 0
  end
end

if tonumber(ARGV[2]) > 0 then
  -- 고정 창(fixed window): 첫 노출부터 1시간. 처음 올릴 때만 만료를 건다(만료 없는 키가 남지 않게).
  if redis.call('INCR', KEYS[2]) == 1 then
    redis.call('EXPIRE', KEYS[2], ARGV[2])
  end
end

if tonumber(ARGV[4]) > 0 then
  -- 클릭 기록: 3단계 어트리뷰션이 "이 사용자가 언제 어느 광고를 눌렀나"를 찾는 곳.
  -- 같은 clickId(재전송)는 같은 항목이라 ZADD가 0을 돌려주고, 그때는 추정 광고비를 올리지 않는다.
  if redis.call('ZADD', KEYS[6], ARGV[5], ARGV[6]) == 1 then
    redis.call('INCRBY', KEYS[7], ARGV[7])
  end
  redis.call('EXPIRE', KEYS[6], ARGV[4])
end

redis.call('XADD', KEYS[3], 'MAXLEN', '~', ARGV[3], '*', unpack(ARGV, 8))
redis.call('INCR', KEYS[4])
return 1
