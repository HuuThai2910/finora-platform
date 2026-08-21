-- V2: Đổi CHAR(64) → VARCHAR(64) cho các cột hash.
-- Hibernate 6.4 PostgreSQL dialect map bpchar (CHAR) sang Types#CHAR,
-- nhưng @Column(length=64) sinh ra VARCHAR → validation fail.
-- VARCHAR(64) vẫn đúng ngữ nghĩa vì HMAC-SHA256 hex luôn 64 ký tự.

ALTER TABLE user_profiles
    ALTER COLUMN id_number_hash TYPE VARCHAR(64);

ALTER TABLE user_profiles
    ALTER COLUMN phone_hash TYPE VARCHAR(64);
