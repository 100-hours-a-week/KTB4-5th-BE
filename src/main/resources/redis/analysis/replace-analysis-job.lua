-- KEYS[1]: 비교·교체할 분석 작업 키
-- KEYS[2](선택): AI 분석 ID 색인, ARGV[3](선택): 해당 묶음 작업 ID
-- ARGV[1]: 기존 작업 JSON, ARGV[2]: 등록 또는 결과를 반영한 작업 JSON
-- 반환값: 성공 1, 동시 변경·만료 0, 다른 작업에 등록된 AI ID -1
local jobKey = KEYS[1]
local expectedJson = ARGV[1]
local replacementJson = ARGV[2]

if redis.call('GET', jobKey) ~= expectedJson then
    return 0
end

local ttl = redis.call('PTTL', jobKey)
if ttl <= 0 then
    return 0
end
if KEYS[2] then
    local registeredJobId = redis.call('GET', KEYS[2])
    if registeredJobId and registeredJobId ~= ARGV[3] then
        return -1
    end
    redis.call('SET', KEYS[2], ARGV[3], 'PX', ttl)
end

redis.call('SET', jobKey, replacementJson, 'KEEPTTL')
return 1
