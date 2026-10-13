#!/usr/bin/env bash
# Thêm protocol mapper `ekyc-status` (attribute ekyc_status → claim ekyc_status) cho client
# `finora-user-client` trên realm `finora` ĐANG CHẠY. Cần vì Keycloak bỏ qua realm import khi realm
# đã tồn tại, nên mapper mới thêm vào keycloak/template/realm-finora.json không tự xuất hiện.
#
# Investment đọc claim này để chặn đặt lệnh khi người dùng chưa eKYC. finora-user ghi attribute
# khi người dùng xác nhận eKYC và ghi bù ở mỗi lần đăng nhập, nên sau khi chạy script chỉ cần
# đăng nhập lại là token có claim.
#
# Chạy lại nhiều lần an toàn: mapper đã có thì bỏ qua.
#
# Cách dùng (từ thư mục finora-platform):
#   bash docker/keycloak/add-ekyc-claim-mapper.sh [tên-container-keycloak]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [ -f "$ENV_FILE" ]; then
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
CLIENT_ID=finora-user-client
MAPPER=ekyc-status

kc() {
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

echo "Keycloak container: $CONTAINER"
kc config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

CLIENT_UUID="$(kc get clients -r "$REALM" -q "clientId=$CLIENT_ID" --fields id --format csv --noquotes | tr -d '\r' | head -n 1)"
if [ -z "$CLIENT_UUID" ]; then
  echo "Không tìm thấy client $CLIENT_ID trên realm $REALM" >&2
  exit 1
fi

if kc get "clients/$CLIENT_UUID/protocol-mappers/models" -r "$REALM" --fields name --format csv --noquotes \
    | tr -d '\r' | grep -qx "$MAPPER"; then
  echo "Mapper $MAPPER đã có — bỏ qua"
  exit 0
fi

kc create "clients/$CLIENT_UUID/protocol-mappers/models" -r "$REALM" \
  -s "name=$MAPPER" -s protocol=openid-connect \
  -s protocolMapper=oidc-usermodel-attribute-mapper -s consentRequired=false \
  -s 'config."user.attribute"=ekyc_status' -s 'config."claim.name"=ekyc_status' \
  -s 'config."jsonType.label"=String' -s 'config."access.token.claim"=true' \
  -s 'config."id.token.claim"=false' -s 'config."userinfo.token.claim"=true' \
  -s 'config."introspection.token.claim"=true' -s 'config.multivalued=false' >/dev/null
echo "Đã tạo mapper $MAPPER cho $CLIENT_ID — người dùng đăng nhập lại để token có claim ekyc_status"
