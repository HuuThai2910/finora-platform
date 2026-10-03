#!/usr/bin/env bash
# Tạo service account `finora-investment-client` (client role payment:hold:on_behalf) trên
# realm `finora` ĐANG CHẠY. Cần vì Keycloak bỏ qua realm import khi realm đã tồn tại, nên
# client mới thêm vào keycloak/template/realm-finora.json không tự xuất hiện.
#
# Chạy lại nhiều lần an toàn: client/role/gán role đã có thì bỏ qua.
#
# Cách dùng (từ thư mục finora-platform):
#   bash docker/keycloak/add-investment-client.sh [tên-container-keycloak]
# Mặc định tự tìm container có tên chứa "keycloak". Tài khoản admin lấy từ
# KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD (đọc từ docker/.env nếu chưa export).
# Secret: dùng KEYCLOAK_INVESTMENT_CLIENT_SECRET nếu đã đặt, không thì sinh mới rồi in ra —
# chép vào finora-investment/.env (và docker/.env).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [ -f "$ENV_FILE" ]; then
  # Chỉ nạp các biến cần, không source cả file (.env có thể chứa ký tự shell đặc biệt).
  for key in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_INVESTMENT_CLIENT_SECRET; do
    if [ -z "${!key:-}" ]; then
      value="$(grep -E "^${key}=" "$ENV_FILE" | tail -n 1 | cut -d= -f2- | tr -d '\r' || true)"
      [ -n "$value" ] && export "$key=$value"
    fi
  done
fi

CONTAINER="${1:-$(docker ps --format '{{.Names}}' | grep -i keycloak | grep -vi init | head -n 1)}"
if [ -z "$CONTAINER" ]; then
  echo "Không tìm thấy container Keycloak đang chạy" >&2
  exit 1
fi
: "${KEYCLOAK_ADMIN:?Thiếu KEYCLOAK_ADMIN}"
: "${KEYCLOAK_ADMIN_PASSWORD:?Thiếu KEYCLOAK_ADMIN_PASSWORD}"

REALM=finora
CLIENT_ID=finora-investment-client
ROLE=payment:hold:on_behalf
SECRET="${KEYCLOAK_INVESTMENT_CLIENT_SECRET:-$(python -c 'import secrets; print(secrets.token_urlsafe(32))' 2>/dev/null || openssl rand -base64 32 | tr -d '=+/')}"

kc() {
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

echo "Keycloak container: $CONTAINER"
kc config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

CLIENT_UUID="$(kc get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r' | head -n 1)"
if [ -z "$CLIENT_UUID" ]; then
  kc create clients -r "$REALM" \
    -s "clientId=$CLIENT_ID" \
    -s "name=FINORA Investment Service" \
    -s enabled=true -s publicClient=false -s bearerOnly=false \
    -s clientAuthenticatorType=client-secret \
    -s "secret=$SECRET" \
    -s standardFlowEnabled=false -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true >/dev/null
  CLIENT_UUID="$(kc get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r' | head -n 1)"
  echo "Đã tạo client $CLIENT_ID"
else
  kc update "clients/$CLIENT_UUID" -r "$REALM" -s "secret=$SECRET" >/dev/null
  echo "Client $CLIENT_ID đã có — cập nhật secret"
fi

if ! kc get "clients/$CLIENT_UUID/roles/$ROLE" -r "$REALM" >/dev/null 2>&1; then
  kc create "clients/$CLIENT_UUID/roles" -r "$REALM" -s "name=$ROLE" \
    -s "description=Auto-Invest: giu/nha tien thay nha dau tu" >/dev/null
  echo "Đã tạo client role $ROLE"
fi

kc add-roles -r "$REALM" --uusername "service-account-$CLIENT_ID" \
  --cclientid "$CLIENT_ID" --rolename "$ROLE" >/dev/null
echo "Đã gán $ROLE cho service-account-$CLIENT_ID"

echo
echo "Chép vào finora-investment/.env (và docker/.env):"
echo "KEYCLOAK_INVESTMENT_CLIENT_SECRET=$SECRET"
