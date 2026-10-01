-- Adds tokens to an armed sale; a no-op when no sale is armed for the SKU.
-- KEYS[1] token counter for the SKU, ARGV[1] quantity
if redis.call('EXISTS', KEYS[1]) == 1 then
  return redis.call('INCRBY', KEYS[1], ARGV[1])
end
return -1
