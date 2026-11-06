#!/usr/bin/env bash
# Phương án dự phòng khi CD (GitHub Actions + GHCR) không dùng được: build image ngay trên VM, gắn tag `local`.
# Build lần lượt (build song song dễ hết RAM vì mỗi lượt Maven tốn 1–1,5 GB). Ghi log vào ~/build.log.
# Dùng: bash build-images.sh [service...]   (không truyền service thì build tất cả)
# Cần cây mã đầy đủ ở thư mục cha của deploy/. Build xong: sửa <SERVICE>_TAG=local trong
# /opt/finora/state/images.env rồi `finora-compose up -d <service>`.
set -uo pipefail

cd "$(dirname "$0")"
SERVICES=("$@")
if [[ ${#SERVICES[@]} -eq 0 ]]; then
  SERVICES=(fineract ai gateway user notification payment investment loan blockchain)
fi
for svc in fineract ai gateway user notification payment investment loan blockchain; do
  export "${svc^^}_TAG=local"
done
COMPOSE=(docker compose -f docker-compose.azure.yml -f docker-compose.azure.build.yml
         --env-file "${FINORA_ENV_DIR:-/opt/finora/env}/deploy.env")

echo "BUILD_START $(date -u +%FT%TZ) ${SERVICES[*]}"
for svc in "${SERVICES[@]}"; do
  echo "=== BUILD $svc $(date -u +%T)"
  "${COMPOSE[@]}" build --progress plain "$svc" || echo "BUILD_FAIL $svc"
done
echo "BUILD_DONE $(date -u +%T)"
