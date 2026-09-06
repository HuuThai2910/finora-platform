#!/bin/sh
set -eu

# Cổng 8443 có thể đã mở trong khi Jersey/Fineract API vẫn đang khởi tạo lười.
# Healthcheck vì thế phải gọi một API nghiệp vụ có xác thực, không chỉ kiểm tra TCP.
: "${FINORA_READINESS_API_USERNAME:?Thiếu FINORA_READINESS_API_USERNAME}"
: "${FINORA_READINESS_API_PASSWORD:?Thiếu FINORA_READINESS_API_PASSWORD}"
: "${FINORA_READINESS_TENANT_ID:?Thiếu FINORA_READINESS_TENANT_ID}"

status="$(curl \
  --silent \
  --show-error \
  --output /dev/null \
  --write-out '%{http_code}' \
  --connect-timeout 3 \
  --max-time 15 \
  --user "${FINORA_READINESS_API_USERNAME}:${FINORA_READINESS_API_PASSWORD}" \
  --header "Fineract-Platform-TenantId: ${FINORA_READINESS_TENANT_ID}" \
  'http://127.0.0.1:8443/fineract-provider/api/v1/offices?limit=1'
)"

test "${status}" = "200"
