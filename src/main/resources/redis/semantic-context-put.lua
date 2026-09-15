-- Only bounded metadata is stored here. Context text stays in the exact cache.
local now = tonumber(ARGV[2])
local expires = tonumber(ARGV[3])
local maximum = tonumber(ARGV[4])
if expires <= now then return 0 end
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
redis.call('ZADD', KEYS[1], expires, ARGV[1])
local excess = redis.call('ZCARD', KEYS[1]) - maximum
if excess > 0 then redis.call('ZREMRANGEBYRANK', KEYS[1], 0, excess - 1) end
local last = redis.call('ZREVRANGE', KEYS[1], 0, 0, 'WITHSCORES')
if #last > 0 then redis.call('PEXPIREAT', KEYS[1], last[2]) end
return 1
