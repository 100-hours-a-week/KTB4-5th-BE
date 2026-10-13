-- KEYS[1]: 비교·교체할 분석 작업 키
-- ARGV[1]: 조회한 기존 작업 JSON, ARGV[2]: 이미지 등록을 반영한 작업 JSON
-- 반환값: 기존 값이 일치하면 1, 다른 등록 또는 만료로 값이 달라졌으면 0
local jobKey = KEYS[1]
local expectedJson = ARGV[1]
local replacementJson = ARGV[2]

if redis.call('GET', jobKey) ~= expectedJson then
    return 0
end

redis.call('SET', jobKey, replacementJson, 'KEEPTTL')
return 1
