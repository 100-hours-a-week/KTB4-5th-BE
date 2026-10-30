if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then return 0 end
local count = redis.call('SCARD', KEYS[1])
if count >= tonumber(ARGV[2]) then return -1 end
redis.call('SADD', KEYS[1], ARGV[1])
if redis.call('PTTL', KEYS[1]) < 0 then
    redis.call('PEXPIRE', KEYS[1], ARGV[3])
end
return count + 1
