-- KEYS[1]: 알림 대기 목록 ZSET (member: 분석 작업 ID, score: 다음 확인 시각 epoch milliseconds)
-- ARGV[1]: 현재 시각, ARGV[2]: 선점 만료 시각, ARGV[3]: 최대 개수
-- 반환값: 이번에 선점한 작업 ID 목록. 선점한 항목은 점수를 만료 시각으로 미뤄 다른 서버가 같은 작업을 동시에 처리하지 않게 한다.
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[3]))
for _, id in ipairs(due) do
    redis.call('ZADD', KEYS[1], 'XX', ARGV[2], id)
end
return due
