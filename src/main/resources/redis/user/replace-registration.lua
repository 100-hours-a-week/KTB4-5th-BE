-- KEYS[1]: 가입 대기 토큰 키
-- ARGV[1]: 비교할 기존 JSON, ARGV[2]: 교체할 JSON
-- 교체할 JSON이 빈 문자열이면 삭제하고, 값이 있으면 최초 TTL을 유지해 교체한다.
-- 반환값: 기존 JSON이 일치해 변경하면 1, 일치하지 않으면 0
local registrationKey = KEYS[1]
local expectedJson = ARGV[1]
local replacementJson = ARGV[2]

if redis.call('GET', registrationKey) ~= expectedJson then
    return 0
end

if replacementJson == '' then
    redis.call('DEL', registrationKey)
else
    redis.call('SET', registrationKey, replacementJson, 'KEEPTTL')
end
return 1
