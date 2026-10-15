-- KEYS[1]: 비교·교체할 분석 작업 키
-- KEYS[2..n](선택): 묶음에 등록된 AI 분석 ID 색인, ARGV[3]: 묶음 작업 ID
-- ARGV[1]: 기존 작업 JSON, ARGV[2]: 등록 또는 결과를 반영한 작업 JSON
-- ARGV[4]: 결과 만료 시각(epoch milliseconds), 미종료 묶음이면 빈 문자열
-- 반환값: 성공 1, 동시 변경·만료 0, 다른 작업에 등록된 AI ID -1
local jobKey = KEYS[1]
local expectedJson = ARGV[1]
local replacementJson = ARGV[2]

if redis.call('GET', jobKey) ~= expectedJson then
    return 0
end

local ttl = redis.call('PTTL', jobKey)
if ttl == 0 then
    return 0
end
for i = 2, #KEYS do
    local registeredJobId = redis.call('GET', KEYS[i])
    if registeredJobId and registeredJobId ~= ARGV[3] then
        return -1
    end
end

redis.call('SET', jobKey, replacementJson)
for i = 2, #KEYS do
    redis.call('SET', KEYS[i], ARGV[3])
end
if ARGV[4] and ARGV[4] ~= '' then
    for i = 1, #KEYS do
        redis.call('PEXPIREAT', KEYS[i], ARGV[4])
    end
end
return 1
