#!/usr/bin/env bash
# W3A (D34, D40): danh tính service account và chống dò mật khẩu trên realm `finora` ĐANG CHẠY.
# Keycloak bỏ qua realm import khi realm đã tồn tại, nên thay đổi trong keycloak/template/realm-finora.json không tự áp
# cho máy đã có realm. Script làm cùng việc đó:
#   - client finora-loan-client (service account, client role ai:credit:score): Loan chấm điểm qua AI trong worker;
#   - client finora-ai-client (service account, gán user:admin:read_all của finora-user-client): AI đọc hồ sơ User,
#     thay cho việc AI giữ secret của finora-user-client (client này có quyền quản trị user Keycloak);
#   - client role ai:ekyc:ocr, notification:internal:send của finora-user-client, gán cho service account của nó;
#   - bật brute-force detection: 5 lần sai, chờ tăng 60 giây, tối đa 15 phút, không khóa vĩnh viễn.
#
# Chạy lại nhiều lần an toàn. Secret: dùng giá trị đã có trong docker/.env (hoặc finora-loan/.env), chưa có thì sinh
# mới; đặt secret đó cho client trên Keycloak rồi ghi thẳng vào các file .env git-ignore. Script KHÔNG in secret.
#
# Cách dùng (từ thư mục finora-platform):
#   bash docker/keycloak/add-service-clients.sh [--skip-brute-force] [tên-container-keycloak]
# --skip-brute-force: không đổi cấu hình chống dò mật khẩu (realm dùng chung với máy khác cần thống nhất trước).
# Tài khoản admin master lấy từ KEYCLOAK_ADMIN/KEYCLOAK_ADMIN_PASSWORD (biến môi trường, không thì docker/.env).
# Sau đó: docker compose up -d ai (nhận secret AI mới) và khởi động lại Loan.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLATFORM_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
DOCKER_ENV="$PLATFORM_DIR/docker/.env"
LOAN_ENV="$PLATFORM_DIR/finora-loan/.env"
AI_ENV="$PLATFORM_DIR/finora-ai/.env"
REALM=finora
SKIP_BRUTE_FORCE=false
if [ "${1:-}" = "--skip-brute-force" ]; then
  SKIP_BRUTE_FORCE=true
  shift
fi

read_env() {
  local file="$1" key="$2"
  [ -f "$file" ] || return 0
  grep -E "^${key}=" "$file" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true
}

# Thay (hoặc thêm) đúng một dòng KEY=VALUE; các dòng khác giữ nguyên. File không tồn tại thì bỏ qua.
set_env() {
  local file="$1" key="$2" value="$3" tmp
  [ -f "$file" ] || return 0
  tmp="$(mktemp)"
  grep -vE "^${key}=" "$file" > "$tmp" || true
  [ -z "$(tail -c 1 "$tmp")" ] || printf '\n' >> "$tmp"
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  cat "$tmp" > "$file"
  rm -f "$tmp"
}

new_secret() {
  python -c 'import secrets; print(secrets.token_urlsafe(32))' 2>/dev/null \
    || openssl rand -base64 32 | tr -dc 'A-Za-z0-9' | head -c 43
}

for key in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD; do
  if [ -z "${!key:-}" ]; then
    value="$(read_env "$DOCKER_ENV" "$key")"
    [ -n "$value" ] && export "$key=$value"
  fi
done
: "${KEYCLOAK_ADMIN:?Thiếu KEYCLOAK_ADMIN (docker/.env)}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Thiếu KEYCLOAK_ADMIN_PASSWORD (docker/.env)}"

CONTAINER="${1:-$(docker ps --format '{{.Names}}' | grep -i keycloak | grep -vi init | head -n 1)}"
if [ -z "$CONTAINER" ]; then
  echo "Không tìm thấy container Keycloak đang chạy" >&2
  exit 1
fi

kc() {
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

client_uuid() {
  kc get clients -r "$REALM" -q "clientId=$1" --fields id --format csv --noquotes | tr -d '\r' | head -n 1
}

# ensure_service_client CLIENT_ID TÊN SECRET
ensure_service_client() {
  local client_id="$1" name="$2" secret="$3" uuid
  uuid="$(client_uuid "$client_id")"
  if [ -z "$uuid" ]; then
    kc create clients -r "$REALM" \
      -s "clientId=$client_id" -s "name=$name" \
      -s enabled=true -s publicClient=false -s bearerOnly=false \
      -s clientAuthenticatorType=client-secret -s "secret=$secret" \
      -s standardFlowEnabled=false -s implicitFlowEnabled=false \
      -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true >/dev/null
    echo "Đã tạo client $client_id"
  else
    kc update "clients/$uuid" -r "$REALM" -s "secret=$secret" >/dev/null
    echo "Client $client_id đã có — đặt secret theo .env"
  fi
}

# ensure_client_role CLIENT_ID ROLE MÔ_TẢ
ensure_client_role() {
  local uuid
  uuid="$(client_uuid "$1")"
  if [ -z "$uuid" ]; then
    echo "Thiếu client $1 trên realm $REALM" >&2
    exit 1
  fi
  if ! kc get "clients/$uuid/roles/$2" -r "$REALM" >/dev/null 2>&1; then
    kc create "clients/$uuid/roles" -r "$REALM" -s "name=$2" -s "description=$3" >/dev/null
    echo "Đã tạo client role $2 của $1"
  fi
}

# grant SERVICE_CLIENT_ID ROLE_CLIENT_ID ROLE
grant() {
  kc add-roles -r "$REALM" --uusername "service-account-$1" --cclientid "$2" --rolename "$3" >/dev/null
  echo "Đã gán $3 ($2) cho service-account-$1"
}

echo "Keycloak container: $CONTAINER"
kc config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

LOAN_SECRET="$(read_env "$DOCKER_ENV" KEYCLOAK_LOAN_CLIENT_SECRET)"
[ -n "$LOAN_SECRET" ] || LOAN_SECRET="$(read_env "$LOAN_ENV" KEYCLOAK_LOAN_CLIENT_SECRET)"
[ -n "$LOAN_SECRET" ] || LOAN_SECRET="$(new_secret)"
AI_SECRET="$(read_env "$DOCKER_ENV" KEYCLOAK_AI_CLIENT_SECRET)"
[ -n "$AI_SECRET" ] || AI_SECRET="$(new_secret)"

ensure_service_client finora-loan-client "FINORA Loan Service" "$LOAN_SECRET"
ensure_service_client finora-ai-client "FINORA AI Service" "$AI_SECRET"

ensure_client_role finora-loan-client "ai:credit:score" "Worker cham diem cua Loan goi AI /credit/explain (D34)"
ensure_client_role finora-user-client "ai:ekyc:ocr" "Service account finora-user goi AI OCR CCCD (D34)"
ensure_client_role finora-user-client "notification:internal:send" "Service account finora-user goi endpoint noi bo cua Notification (D36)"

grant finora-loan-client finora-loan-client "ai:credit:score"
grant finora-user-client finora-user-client "ai:ekyc:ocr"
grant finora-user-client finora-user-client "notification:internal:send"
grant finora-ai-client finora-user-client "user:admin:read_all"

if [ "$SKIP_BRUTE_FORCE" = true ]; then
  echo "Bỏ qua brute-force detection theo --skip-brute-force"
else
  kc update "realms/$REALM" -s bruteForceProtected=true -s permanentLockout=false -s failureFactor=5 \
    -s waitIncrementSeconds=60 -s maxFailureWaitSeconds=900 -s maxDeltaTimeSeconds=43200 \
    -s quickLoginCheckMilliSeconds=1000 -s minimumQuickLoginWaitSeconds=60 >/dev/null
  echo "Đã bật brute-force detection cho realm $REALM (5 lần sai, tối đa chờ 15 phút)"
fi

set_env "$DOCKER_ENV" KEYCLOAK_LOAN_CLIENT_SECRET "$LOAN_SECRET"
set_env "$DOCKER_ENV" KEYCLOAK_AI_CLIENT_SECRET "$AI_SECRET"
set_env "$LOAN_ENV" KEYCLOAK_LOAN_CLIENT_SECRET "$LOAN_SECRET"
if [ -f "$AI_ENV" ]; then
  set_env "$AI_ENV" KEYCLOAK_CLIENT_ID finora-ai-client
  set_env "$AI_ENV" KEYCLOAK_CLIENT_SECRET "$AI_SECRET"
fi
echo "Đã ghi secret (không in ra) vào các file .env có sẵn: docker/.env, finora-loan/.env, finora-ai/.env"
echo "Tiếp theo: docker compose -f docker/docker-compose.yml up -d ai; khởi động lại Loan."
