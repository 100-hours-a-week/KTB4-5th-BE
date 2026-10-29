local incoming = cjson.decode(ARGV[1])
local function terminal(job)
    return job.view ~= nil and job.view ~= cjson.null
        and (job.view.status == 'COMPLETED' or job.view.status == 'FAILED')
end
local previous = redis.call('GET', KEYS[1])
if previous then
    local current = cjson.decode(previous)
    if current.acceptance.jobId ~= incoming.acceptance.jobId or current.acceptance.requestId ~= incoming.acceptance.requestId then
        return redis.error_reply('Recommendation job identity mismatch')
    end
    if terminal(current) or incoming.view == cjson.null then return previous end
    if current.view ~= cjson.null and current.view.status == 'PROCESSING' and incoming.view.status == 'QUEUED' then
        return previous
    end
end
redis.call('SET', KEYS[1], ARGV[1])
if terminal(incoming) then redis.call('PEXPIRE', KEYS[1], ARGV[2]) end
return ARGV[1]
