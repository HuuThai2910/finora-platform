#!/usr/bin/env bash
# Ghi trạng thái các container FINORA ra /opt/finora/state/public/_status.json mỗi 10 giây.
# Caddy phục vụ file này ở /_status.json; hàm /api/wake trên Vercel đọc nó để báo cho trang web
# biết hệ thống đã sẵn sàng chưa. Chạy bằng finora-status.service.
set -uo pipefail

OUT_DIR=/opt/finora/state/public
OUT_FILE="$OUT_DIR/_status.json"
# Hệ thống chỉ "sẵn sàng" khi mọi service dưới đây đang chạy và healthy (nếu có healthcheck).
SERVICES=(caddy kafka keycloak redis fineract ai user notification payment investment loan blockchain-db blockchain gateway
          fabric-orderer fabric-peer0-org1 fabric-peer0-org2 fabric-ccaas-org1 fabric-ccaas-org2)

mkdir -p "$OUT_DIR"

service_state() {
  local lines=$1 svc=$2 line state status
  line=$(grep -m1 "^${svc}|" <<<"$lines")
  if [[ -z "$line" ]]; then
    echo missing
    return
  fi
  state=$(cut -d'|' -f2 <<<"$line")
  status=$(cut -d'|' -f3 <<<"$line")
  if [[ "$state" != running ]]; then
    echo "$state"
  elif [[ "$status" == *"(healthy)"* ]]; then
    echo healthy
  elif [[ "$status" == *"(health: starting)"* ]]; then
    echo starting
  elif [[ "$status" == *"(unhealthy)"* ]]; then
    echo unhealthy
  else
    echo running
  fi
}

write_status() {
  local lines services='{}' ready=true svc value
  lines=$(docker ps -a --filter label=com.docker.compose.project=finora \
    --format '{{.Label "com.docker.compose.service"}}|{{.State}}|{{.Status}}' 2>/dev/null)
  for svc in "${SERVICES[@]}"; do
    value=$(service_state "$lines" "$svc")
    [[ "$value" == healthy || "$value" == running ]] || ready=false
    services=$(jq -c --arg k "$svc" --arg v "$value" '. + {($k): $v}' <<<"$services")
  done
  jq -n --argjson services "$services" --argjson ready "$ready" \
    '{updatedAt: (now | todate), ready: $ready, services: $services}' >"$OUT_FILE.tmp" &&
    mv "$OUT_FILE.tmp" "$OUT_FILE"
}

while true; do
  write_status
  sleep 10
done
