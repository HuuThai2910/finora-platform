#!/usr/bin/env bash
# Tự deallocate VM khi không ai gọi API trong IDLE_MINUTES phút, để Azure ngừng tính tiền giờ.
# Chạy mỗi 5 phút bằng finora-idle-stop.timer. Quyền deallocate lấy từ managed identity của
# VM (role "FINORA VM Power" gán trên chính VM này).
#
# Không tắt khi: còn trong BOOT_GRACE_MINUTES phút đầu sau khi bật, đang có phiên SSH, hoặc
# có file /opt/finora/state/keep-awake. DRY_RUN=1 chỉ ghi log, không tắt.
set -euo pipefail

IDLE_MINUTES=${IDLE_MINUTES:-45}
BOOT_GRACE_MINUTES=${BOOT_GRACE_MINUTES:-30}
DRY_RUN=${DRY_RUN:-0}
ACCESS_LOG=/opt/finora/state/api-access.log
KEEP_AWAKE=/opt/finora/state/keep-awake

[[ -e "$KEEP_AWAKE" ]] && exit 0
[[ -n "$(who)" ]] && exit 0

now=$(date +%s)
boot=$(( now - $(cut -d. -f1 /proc/uptime) ))
last_use=$boot
if [[ -f "$ACCESS_LOG" ]]; then
  # Không dùng thời điểm sửa file: Caddy vẫn ghi các lượt chuyển http→https (bot quét cổng 80)
  # vào log này. Chỉ tính request /api/v1 thật.
  last_api=$(tail -n 2000 "$ACCESS_LOG" |
    jq -r 'select((.request.uri // "") | startswith("/api/v1")) | .ts | floor' 2>/dev/null | tail -n 1 || true)
  [[ -n "$last_api" ]] && (( last_api > last_use )) && last_use=$last_api
fi

(( now - boot < BOOT_GRACE_MINUTES * 60 )) && exit 0
(( now - last_use < IDLE_MINUTES * 60 )) && exit 0

idle_minutes=$(( (now - last_use) / 60 ))
if [[ "$DRY_RUN" == 1 ]]; then
  logger -t finora-idle-stop "DRY_RUN: không có request API trong ${idle_minutes} phút, lẽ ra đã tắt VM"
  exit 0
fi

imds=http://169.254.169.254/metadata
meta=$(curl -sf -H Metadata:true "$imds/instance/compute?api-version=2021-02-01")
token=$(curl -sf -H Metadata:true \
  "$imds/identity/oauth2/token?api-version=2018-02-01&resource=https://management.azure.com/" | jq -r .access_token)
sub=$(jq -r .subscriptionId <<<"$meta")
rg=$(jq -r .resourceGroupName <<<"$meta")
vm=$(jq -r .name <<<"$meta")

logger -t finora-idle-stop "Không có request API trong ${idle_minutes} phút, tự tắt VM"
curl -sf -X POST -H "Authorization: Bearer $token" -H "Content-Length: 0" \
  "https://management.azure.com/subscriptions/$sub/resourceGroups/$rg/providers/Microsoft.Compute/virtualMachines/$vm/deallocate?api-version=2024-07-01"
