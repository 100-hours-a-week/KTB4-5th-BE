-- KEYS[1]: 사용자 전체 Refresh 세션 JSON을 저장하는 키.
-- ARGV[1]: 예상 JSON. 빈 문자열은 키가 없는 상태를 뜻한다.
-- ARGV[2]: 교체 JSON. 빈 문자열이면 키를 삭제한다.
-- ARGV[3]: 요청한 보관 시간(ms).
-- ARGV[4]: 최대 보관 시각 연장 여부. '1'은 연장, '0'은 기존 TTL 이내로 제한한다.
-- 반환값: 예상 상태와 다르면 0, 교체 또는 삭제를 반영하면 1.
local userKey = KEYS[1]
local expectedJson = ARGV[1]
local replacementJson = ARGV[2]
local requestedTtl = tonumber(ARGV[3])
local retentionExtended = ARGV[4]

local currentJson = redis.call('GET', userKey) or ''
if currentJson ~= expectedJson then
    return 0
end

if replacementJson == '' then
    redis.call('DEL', userKey)
else
    local ttl = requestedTtl
    local remaining = redis.call('PTTL', userKey)
    if retentionExtended == '0' and remaining >= 0 then
        ttl = math.min(ttl, remaining)
    end
    if ttl <= 0 then
        redis.call('DEL', userKey)
    else
        redis.call('SET', userKey, replacementJson, 'PX', ttl)
    end
end
return 1
