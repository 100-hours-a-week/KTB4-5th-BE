-- KEYS[1]: 업로드 발급 내역 키, ARGV[1]: 조회 시 확인한 만료 내역 JSON.
-- 반환값: 1은 삭제, 0은 값 불일치 또는 키 없음.
local key = KEYS[1]
local expectedJson = ARGV[1]

if redis.call('GET', key) == expectedJson then
    return redis.call('DEL', key)
end
return 0
