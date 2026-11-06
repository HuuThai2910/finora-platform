#!/usr/bin/env bash
# `docker compose` của cụm FINORA trên VM với đúng file compose và file env (CD-001).
# cd-deploy.sh cài file này thành /opt/finora/bin/finora-compose. Ví dụ:
#   sudo finora-compose ps
#   sudo finora-compose logs -f loan
#   sudo finora-compose up -d loan
# FINORA_RELEASE_DIR chọn thư mục deploy/ khác bản đang chạy (cd-deploy.sh dùng khi đổi bản).
set -euo pipefail

FINORA_ROOT=${FINORA_ROOT:-/opt/finora}
RELEASE_DIR=${FINORA_RELEASE_DIR:-$FINORA_ROOT/current/deploy}
export FINORA_ENV_DIR=$FINORA_ROOT/env
export FINORA_CADDYFILE=$FINORA_ROOT/config/Caddyfile

exec docker compose \
  -f "$RELEASE_DIR/docker-compose.azure.yml" \
  --project-directory "$RELEASE_DIR" \
  --env-file "$FINORA_ENV_DIR/deploy.env" \
  --env-file "$FINORA_ROOT/state/images.env" \
  "$@"
