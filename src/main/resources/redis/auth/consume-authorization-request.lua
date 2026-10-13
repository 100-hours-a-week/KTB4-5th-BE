-- KEYS[1]: 소비할 OAuth 인증 요청 키
-- ARGV[1]: state·제공자·경로·만료 검증을 통과한 인증 요청 JSON
-- 반환값: 같은 요청을 삭제했으면 1, 요청이 없거나 값이 다르면 0
local authorizationRequestKey = KEYS[1]
local expectedJson = ARGV[1]

if redis.call('GET', authorizationRequestKey) == expectedJson then
    return redis.call('DEL', authorizationRequestKey)
end
return 0
