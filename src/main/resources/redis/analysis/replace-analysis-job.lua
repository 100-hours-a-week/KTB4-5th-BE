-- KEYS[1]: 비교·교체할 분석 작업 키
-- KEYS[2..n](선택): 묶음에 등록된 AI 분석 ID 색인(작업 ID 집합), ARGV[3]: 묶음 작업 ID
-- ARGV[1]: 기존 작업 JSON, ARGV[2]: 등록 또는 결과를 반영한 작업 JSON
-- ARGV[4]: 결과 만료 시각(epoch milliseconds), 미종료 묶음이면 빈 문자열
-- 반환값: 성공 1, 동시 변경·만료 0
-- 같은 사진의 요청은 같은 requestId를 써서 AI 분석 하나를 여러 작업이 함께 쓰므로, 색인은 작업 ID 집합이다.
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

redis.call('SET', jobKey, replacementJson)
for i = 2, #KEYS do
    -- 집합 색인 이전에 저장된 문자열 색인은 같은 작업 ID를 가진 집합으로 옮긴다.
    if redis.call('TYPE', KEYS[i]).ok == 'string' then
        local previousJobId = redis.call('GET', KEYS[i])
        local previousTtl = redis.call('PTTL', KEYS[i])
        redis.call('DEL', KEYS[i])
        redis.call('SADD', KEYS[i], previousJobId)
        if previousTtl > 0 then
            redis.call('PEXPIRE', KEYS[i], previousTtl)
        end
    end
    redis.call('SADD', KEYS[i], ARGV[3])
end
if ARGV[4] and ARGV[4] ~= '' then
    local deadline = tonumber(ARGV[4])
    redis.call('PEXPIREAT', jobKey, deadline)
    local time = redis.call('TIME')
    local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
    for i = 2, #KEYS do
        -- 다른 작업이 더 늦게 끝났으면 그 작업의 보관 기한까지 색인을 남긴다.
        local remaining = redis.call('PTTL', KEYS[i])
        if remaining < 0 or now + remaining < deadline then
            redis.call('PEXPIREAT', KEYS[i], deadline)
        end
    end
end
return 1
