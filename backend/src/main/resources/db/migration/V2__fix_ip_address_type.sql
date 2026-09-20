-- Change inet columns to VARCHAR(45) to allow standard JDBC String binding
-- 45 chars holds a full IPv6 address with IPv4 mapped tail
ALTER TABLE viewer_sessions ALTER COLUMN ip_address TYPE VARCHAR(45);
ALTER TABLE viewer_access_logs ALTER COLUMN ip_address TYPE VARCHAR(45);
