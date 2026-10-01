-- Atomically claims flash-sale tokens for one order line.
-- KEYS[1] token counter for the SKU, KEYS[2] claim marker for (SKU, order)
-- ARGV[1] quantity, ARGV[2] claim TTL in seconds
-- Returns {code, remaining tokens}. Codes: -1 no sale is armed for the SKU, 0 sold out,
-- 1 newly admitted, 2 this order had already claimed the line.
local tokens = redis.call('GET', KEYS[1])
if not tokens then
  return {-1, 0}
end
-- A retried request for the same order must not consume tokens twice.
if redis.call('EXISTS', KEYS[2]) == 1 then
  return {2, tonumber(tokens)}
end
local quantity = tonumber(ARGV[1])
if tonumber(tokens) < quantity then
  return {0, tonumber(tokens)}
end
local remaining = redis.call('DECRBY', KEYS[1], quantity)
redis.call('SET', KEYS[2], quantity, 'EX', tonumber(ARGV[2]))
return {1, remaining}
