-- Returns an order line's claimed tokens to the pool, at most once.
-- KEYS[1] token counter for the SKU, KEYS[2] claim marker for (SKU, order)
-- Returns 1 when tokens were returned, 0 when there was no claim to refund.
local claimed = redis.call('GET', KEYS[2])
if not claimed then
  return 0
end
redis.call('DEL', KEYS[2])
if redis.call('EXISTS', KEYS[1]) == 1 then
  redis.call('INCRBY', KEYS[1], claimed)
end
return 1
