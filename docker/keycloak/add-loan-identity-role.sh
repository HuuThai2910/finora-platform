#!/usr/bin/env bash
# Gán client role user:identity:read_internal (của finora-user-client) cho service account finora-loan-client trên
# realm `finora` ĐANG CHẠY: Loan đọc họ tên + CCCD đã che của người vay qua API nội bộ finora-user để in lên hợp đồng vay.
# Cần vì Keycloak bỏ qua realm import khi realm đã tồn tại, nên dòng gán role mới trong
# keycloak/template/realm-finora.json không tự xuất hiện trên máy/cụm đã có realm.
#
# Script CHỈ gán role: không tạo client, không tạo role, không đổi secret. Client finora-loan-client (tạo bởi
# add-service-clients.sh) hoặc role user:identity:read_internal (tạo bởi add-investment-client.sh) chưa có thì dừng báo lỗi.
# Chạy lại nhiều lần an toàn: gán role đã có thì Keycloak bỏ qua.
#
# Cách dùng (từ thư mục finora-platform):
#   bash docker/keycloak/add-loan-identity-role.sh [tên-container-keycloak]
# Mặc định tự tìm container có tên chứa "keycloak". Tài khoản admin lấy từ
# KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD (đọc từ docker/.env nếu chưa export).
# Sau đó không cần khởi động lại Loan: token service account mới (hết hạn sau vài phút) mang role này; muốn áp ngay thì
# khởi động lại Loan để bỏ token đang cache.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [ -f "$ENV_FILE" ]; then
  # Chỉ nạp hai biến admin, không source cả file (.env có thể chứa ký tự shell đặc biệt).
  for key in KEYCLOAK_ADMIN KEYCLOAK_ADMIN_PASSWORD; do
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
LOAN_CLIENT_ID=finora-loan-client
USER_CLIENT_ID=finora-user-client
IDENTITY_ROLE=user:identity:read_internal

kc() {
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

client_uuid() {
  kc get clients -r "$REALM" -q "clientId=$1" --fields id --format csv --noquotes | tr -d '\r' | head -n 1
}

echo "Keycloak container: $CONTAINER"
kc config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

if [ -z "$(client_uuid "$LOAN_CLIENT_ID")" ]; then
  echo "Không tìm thấy client $LOAN_CLIENT_ID trên realm $REALM (chạy add-service-clients.sh trước)" >&2
  exit 1
fi
USER_CLIENT_UUID="$(client_uuid "$USER_CLIENT_ID")"
if [ -z "$USER_CLIENT_UUID" ]; then
  echo "Không tìm thấy client $USER_CLIENT_ID trên realm $REALM" >&2
  exit 1
fi
if ! kc get "clients/$USER_CLIENT_UUID/roles/$IDENTITY_ROLE" -r "$REALM" >/dev/null 2>&1; then
  echo "Chưa có client role $IDENTITY_ROLE trên $USER_CLIENT_ID (chạy add-investment-client.sh trước)" >&2
  exit 1
fi

kc add-roles -r "$REALM" --uusername "service-account-$LOAN_CLIENT_ID" \
  --cclientid "$USER_CLIENT_ID" --rolename "$IDENTITY_ROLE" >/dev/null
echo "Đã gán $IDENTITY_ROLE ($USER_CLIENT_ID) cho service-account-$LOAN_CLIENT_ID"
