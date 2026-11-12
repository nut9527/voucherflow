-- 固定窗口限流：KEYS[1] 为计数键，ARGV 分别为请求上限和窗口毫秒数。
local limit = tonumber(ARGV[1])
local windowMs = tonumber(ARGV[2])
-- 计数与有效期在同一次脚本执行中完成，保证并发请求读取一致的额度。
local count = redis.call('INCR', KEYS[1])
local ttl = redis.call('PTTL', KEYS[1])
-- 首次请求开启窗口；发现缺少有效期的计数键时重新设置有效期。
if count == 1 or ttl < 0 then
    redis.call('PEXPIRE', KEYS[1], windowMs)
    ttl = windowMs
end
-- 返回是否放行（1 或 0）及剩余毫秒数，供调用方生成重试提示。
return {count <= limit and 1 or 0, ttl}
