-- Per-IP counters stop holding addresses.
--
-- The counters only ever need to tell two visitors apart for one Pacific day; nothing
-- reads an address back. They now store an HMAC of it instead, keyed by a server secret
-- that lives outside this database. See IpHasher for why it is keyed rather than merely
-- hashed, and forecast.privacy.ip-secret for where the key comes from.
--
-- Existing rows are dropped rather than converted: they hold addresses in plain text and
-- cannot be re-keyed. Nothing of value is lost — a row is worth less than one day, and
-- the worst case is that a handful of visitors get their daily allowance back early.
--
-- Rows are now deleted once their day is over (DailyIpLimiter prunes on the first
-- request of a new day), so the table no longer grows without bound. The primary key
-- leads with `day`, so that DELETE uses its index and needs no separate one.

DELETE FROM ip_daily_usage;

ALTER TABLE ip_daily_usage RENAME COLUMN ip TO ip_hash;
