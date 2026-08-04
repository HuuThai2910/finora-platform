#!/bin/sh
set -eu

# Fineract tách metadata tenant và dữ liệu tenant mặc định thành hai database.
# Script chỉ chạy lần đầu khi volume PostgreSQL còn trống.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=fineract_user="$FINERACT_DB_USERNAME" \
  --set=fineract_password="$FINERACT_DB_PASSWORD" <<-'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'fineract_user', :'fineract_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'fineract_user')\gexec

SELECT format('CREATE DATABASE fineract_tenants OWNER %I', :'fineract_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'fineract_tenants')\gexec

SELECT format('CREATE DATABASE fineract_default OWNER %I', :'fineract_user')
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'fineract_default')\gexec
SQL
