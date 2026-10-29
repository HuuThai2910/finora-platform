#!/usr/bin/env bash
# Bật phân quyền nhân viên trên realm `finora` ĐANG CHẠY:
#   - client `finora-ops` (không đăng nhập được) giữ 28 client role ops:<chức năng>:view|action;
#   - realm role ROLE_STAFF (composite user:profile:read của finora-user-client);
#   - vai trò có sẵn STAFF_VAN_HANH "Nhân viên vận hành" (Xem mọi chức năng, Thao tác việc vận hành hằng ngày);
#   - ROLE_ADMIN gom thêm toàn bộ quyền ops:*;
#   - service account finora-user-client thêm manage-realm (tạo/sửa/xoá realm role STAFF_*) và manage-clients
#     (Keycloak chỉ cho gắn client role finora-ops vào composite khi người gọi quản lý được client đó).
# Cần vì Keycloak bỏ qua realm import khi realm đã tồn tại, nên phần mới trong
# keycloak/template/realm-finora.json không tự xuất hiện.
#
# Chạy lại nhiều lần an toàn: client/role đã có thì bỏ qua; quyền của STAFF_VAN_HANH chỉ nạp khi role chưa có
# composite nào, để không ghi đè cấu hình quản trị viên đã sửa trên web.
#
# Danh mục quyền phải khớp com.finora.common.security.ops.OpsFunction (finora-common).
#
# Cách dùng (từ thư mục finora-platform):
#   bash docker/keycloak/add-staff-rbac.sh [tên-container-keycloak]
# Mặc định tự tìm container có tên chứa "keycloak". Tài khoản admin lấy từ
# KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD (đọc từ docker/.env nếu chưa export).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/../.env"
if [ -f "$ENV_FILE" ]; then
  # Chỉ nạp các biến cần, không source cả file (.env có thể chứa ký tự shell đặc biệt).
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
OPS_CLIENT_ID=finora-ops
USER_CLIENT_ID=finora-user-client
STAFF_BASE_ROLE=ROLE_STAFF
BUILT_IN_ROLE=STAFF_VAN_HANH

# mã|tên chức năng|có quyền Thao tác|vai trò vận hành có Thao tác — khớp OpsFunction.
FUNCTIONS=(
  "dashboard|Tổng quan & thống kê|0|0"
  "loan-application|Hồ sơ vay|1|1"
  "collection|Thu hồi nợ|1|1"
  "reschedule|Cơ cấu nợ|1|1"
  "withdrawal|Rút tiền|1|1"
  "reconciliation|Đối soát & sự cố|1|0"
  "funding|Gọi vốn & Notes|1|1"
  "funding-settings|Cấu hình gọi vốn|1|0"
  "secondary-market|Chợ thứ cấp|1|1"
  "loan-product|Sản phẩm vay|1|0"
  "customer|Khách hàng|1|0"
  "ekyc|Duyệt eKYC|1|1"
  "blockchain|Bằng chứng blockchain|1|0"
  "ai-policy|Chính sách AI|1|0"
  "ai-scoring|Chấm thử AI|0|0"
)

kc() {
  MSYS_NO_PATHCONV=1 docker exec "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

# Gửi JSON qua stdin (-f -): chữ có dấu không phụ thuộc cách Git Bash/Windows chuyển tham số dòng lệnh.
kc_json() {
  MSYS_NO_PATHCONV=1 docker exec -i "$CONTAINER" /opt/keycloak/bin/kcadm.sh "$@" -f -
}

client_uuid() {
  kc get clients -r "$REALM" -q "clientId=$1" --fields id --format csv --noquotes | tr -d '\r' | head -n 1
}

echo "Keycloak container: $CONTAINER"
kc config credentials --server http://localhost:8080 --realm master \
  --user "$KEYCLOAK_ADMIN" --password "$KEYCLOAK_ADMIN_PASSWORD" >/dev/null

# ── 1. Client finora-ops ─────────────────────────────────────────
OPS_UUID="$(client_uuid "$OPS_CLIENT_ID")"
if [ -z "$OPS_UUID" ]; then
  kc create clients -r "$REALM" \
    -s "clientId=$OPS_CLIENT_ID" \
    -s "name=FINORA Ops Permissions" \
    -s "description=Chi giu client role ops:* de gom vao vai tro nhan vien STAFF_*; khong dang nhap duoc" \
    -s enabled=true -s publicClient=false -s bearerOnly=false \
    -s standardFlowEnabled=false -s implicitFlowEnabled=false \
    -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
    -s fullScopeAllowed=false >/dev/null
  OPS_UUID="$(client_uuid "$OPS_CLIENT_ID")"
  echo "Đã tạo client $OPS_CLIENT_ID"
else
  echo "Client $OPS_CLIENT_ID đã có"
fi

# ── 2. Client role ops:* ─────────────────────────────────────────
ALL_PERMISSIONS=()
DEFAULT_PERMISSIONS=()
ensure_ops_role() {
  local name="$1" description="$2"
  if ! kc get "clients/$OPS_UUID/roles/$name" -r "$REALM" >/dev/null 2>&1; then
    printf '{"name":"%s","description":"%s"}' "$name" "$description" \
      | kc_json create "clients/$OPS_UUID/roles" -r "$REALM" >/dev/null
    echo "Đã tạo quyền $name"
  fi
}
for entry in "${FUNCTIONS[@]}"; do
  IFS='|' read -r code label has_action default_action <<<"$entry"
  ensure_ops_role "ops:$code:view" "$label — Xem"
  ALL_PERMISSIONS+=("ops:$code:view")
  DEFAULT_PERMISSIONS+=("ops:$code:view")
  if [ "$has_action" = "1" ]; then
    ensure_ops_role "ops:$code:action" "$label — Thao tác"
    ALL_PERMISSIONS+=("ops:$code:action")
    if [ "$default_action" = "1" ]; then
      DEFAULT_PERMISSIONS+=("ops:$code:action")
    fi
  fi
done
echo "Client $OPS_CLIENT_ID có ${#ALL_PERMISSIONS[@]} quyền"

rolename_args() {
  local args=()
  for permission in "$@"; do
    args+=(--rolename "$permission")
  done
  printf '%s\n' "${args[@]}"
}

# ── 3. ROLE_STAFF ────────────────────────────────────────────────
if ! kc get "roles/$STAFF_BASE_ROLE" -r "$REALM" >/dev/null 2>&1; then
  kc create roles -r "$REALM" -s "name=$STAFF_BASE_ROLE" \
    -s "description=Nhan vien van hanh - quyen cu the theo vai tro STAFF_* do quan tri vien cau hinh" >/dev/null
  echo "Đã tạo realm role $STAFF_BASE_ROLE"
fi
kc add-roles -r "$REALM" --rname "$STAFF_BASE_ROLE" \
  --cclientid "$USER_CLIENT_ID" --rolename user:profile:read >/dev/null
echo "$STAFF_BASE_ROLE gom user:profile:read ($USER_CLIENT_ID)"

# ── 4. Vai trò có sẵn STAFF_VAN_HANH ────────────────────────────
if ! kc get "roles/$BUILT_IN_ROLE" -r "$REALM" >/dev/null 2>&1; then
  printf '{"name":"%s","description":"%s","attributes":{"finoraStaffRole":["true"],"displayName":["%s"]}}' \
    "$BUILT_IN_ROLE" "Xem mọi chức năng, thao tác các việc vận hành hằng ngày" "Nhân viên vận hành" \
    | kc_json create roles -r "$REALM" >/dev/null
  echo "Đã tạo vai trò $BUILT_IN_ROLE"
fi
if ! kc get "roles/$BUILT_IN_ROLE" -r "$REALM" --fields composite | tr -d '\r' | grep -Eq '"composite" *: *true'; then
  mapfile -t ARGS < <(rolename_args "${DEFAULT_PERMISSIONS[@]}")
  kc add-roles -r "$REALM" --rname "$BUILT_IN_ROLE" --cclientid "$OPS_CLIENT_ID" "${ARGS[@]}" >/dev/null
  echo "Đã nạp ${#DEFAULT_PERMISSIONS[@]} quyền mặc định cho $BUILT_IN_ROLE"
else
  echo "$BUILT_IN_ROLE đã có quyền — giữ nguyên cấu hình hiện tại"
fi

# ── 5. ROLE_ADMIN gom mọi quyền ops:* ───────────────────────────
mapfile -t ARGS < <(rolename_args "${ALL_PERMISSIONS[@]}")
kc add-roles -r "$REALM" --rname ROLE_ADMIN --cclientid "$OPS_CLIENT_ID" "${ARGS[@]}" >/dev/null
echo "ROLE_ADMIN gom ${#ALL_PERMISSIONS[@]} quyền $OPS_CLIENT_ID"

# ── 6. Quyền quản trị realm cho service account của finora-user ──
kc add-roles -r "$REALM" --uusername "service-account-$USER_CLIENT_ID" \
  --cclientid realm-management --rolename manage-realm --rolename manage-clients >/dev/null
echo "Đã gán manage-realm, manage-clients (realm-management) cho service-account-$USER_CLIENT_ID"

echo
echo "Xong. Người dùng đang đăng nhập (kể cả admin) phải đăng nhập lại để token có quyền ops:*."
