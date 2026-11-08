#!/usr/bin/env bash
# Deploy một commit của finora-platform lên VM (CD-001). Chạy bằng root, thường do workflow CD gọi qua
#   az vm run-command invoke --command-id RunShellScript --scripts @deploy/cd-deploy.sh \
#     --parameters <sha> <services> <repo>
# - sha:      commit đầy đủ 40 ký tự; image GHCR có tag sha-<7 ký tự đầu>
# - services: tên service cách nhau dấu phẩy (fineract,ai,user,...,fabric-ccaas), `all`, hoặc `-` (chỉ cập nhật cấu hình)
# - repo:     owner/name trên GitHub (repo public, tải thư mục deploy/ và cấu hình Fabric không cần token)
# Run-command không trả mã thoát của script và chỉ giữ 4 KB output cuối, nên dòng cuối luôn là
# FINORA_CD_RESULT=<ok|rolled-back|failed>; log đầy đủ ở /opt/finora/state/deploy-<sha>.log và Log Analytics
# (Syslog, ProcessName finora-cd).
#
# Lần chạy đầu trên VM còn bố cục cũ (/opt/finora/src/finora-platform, image build tại chỗ): script tự chép .env sang
# /opt/finora/env và cần services=all; hỏng thì chạy lại cụm cũ.
#
# Mạng Fabric (FAB-AZ-001): bản deploy có service fabric-tools thì script sinh crypto/genesis/package vào
# /opt/finora/fabric (một lần, không bao giờ thay), chép danh tính User1@org1 cho finora-blockchain, rồi sau khi cụm
# healthy thì join channel + cài chaincode (một lần, đánh dấu bằng build/.network-ready).
if [ -z "${BASH_VERSION:-}" ]; then exec bash "$0" "$@"; fi
set -euo pipefail

SHA=${1:-}
SERVICES_ARG=${2:--}
REPO=${3:-HuuThai2910/finora-platform}
# FINORA_ROOT chỉ đổi khi chạy thử ngoài VM.
ROOT=${FINORA_ROOT:-/opt/finora}
export FINORA_ROOT=$ROOT
STATE=$ROOT/state
IMAGES=$STATE/images.env
OLD_SRC=$ROOT/src/finora-platform
FABRIC=$ROOT/fabric
ALL_SERVICES=(fineract ai user notification payment investment loan blockchain gateway fabric-ccaas)
ENV_SERVICES=(ai user notification payment investment loan)

if [[ ! $SHA =~ ^[0-9a-f]{40}$ ]]; then
  echo "Commit phải là SHA đầy đủ 40 ký tự: '$SHA'"
  echo "FINORA_CD_RESULT=failed"
  exit 1
fi
SHORT=${SHA:0:7}
TAG=sha-$SHORT
REGISTRY=ghcr.io/${REPO%%/*}
REGISTRY=${REGISTRY,,}
REL=$ROOT/releases/$SHA

mkdir -p "$STATE" "$ROOT/bin" "$ROOT/releases" "$ROOT/config"
LOG=$STATE/deploy-$SHORT.log
exec > >(tee -a "$LOG") 2>&1
TEE_PID=$!

log() {
  echo "[$(date -u +%T)] $*"
  logger -p local0.info -t finora-cd -- "$SHORT $*" || true
}

# Giữ VM thức trong lúc deploy (finora-idle-stop.sh bỏ qua khi có file này); chỉ gỡ nếu chính script tạo ra.
KEEP_AWAKE=$STATE/keep-awake
KEEP_AWAKE_OWNED=false
if [[ ! -e $KEEP_AWAKE ]]; then
  touch "$KEEP_AWAKE"
  KEEP_AWAKE_OWNED=true
fi
RESULT=failed
finish() {
  if [[ $KEEP_AWAKE_OWNED == true ]]; then rm -f "$KEEP_AWAKE"; fi
  log "Kết quả: $RESULT"
  echo "FINORA_CD_RESULT=$RESULT"
  exec 1>&- 2>&-
  wait "$TEE_PID" 2>/dev/null || true
}
trap finish EXIT

compose() { "$ROOT/bin/finora-compose" "$@"; }

# Tên biến tag trong images.env: fabric-ccaas → FABRIC_CCAAS_TAG.
tag_var() {
  local name=${1^^}
  echo "${name//-/_}_TAG"
}

# Một image CD có thể chạy thành nhiều service compose (chaincode của từng tổ chức).
compose_services_of() {
  case $1 in
    fabric-ccaas) echo fabric-ccaas-org1 fabric-ccaas-org2 ;;
    *) echo "$1" ;;
  esac
}

fabric_enabled() {
  compose --profile fabric-tools config --services 2>/dev/null | grep -qx fabric-tools
}

# Crypto, genesis block, package CCaaS và core.yaml của peer: sinh một lần (cryptogen là khóa demo, không phải CA).
# finora-blockchain chạy bằng uid 10001 nên được một bản riêng danh tính User1@org1, chỉ uid đó đọc được.
prepare_fabric() {
  local org1=$FABRIC/crypto-config/peerOrganizations/org1.finora.local
  local user=$org1/users/User1@org1.finora.local/msp
  install -d -m 700 "$FABRIC" "$FABRIC/crypto-config" "$FABRIC/build" || return 1
  if [[ ! -f $FABRIC/build/peercfg/core.yaml || ! -f $FABRIC/build/chaincode/package-id ||
        ! -f $FABRIC/build/channel/finora-channel.block || ! -f $user/keystore/priv_sk ]]; then
    log "Fabric: sinh crypto, genesis block, package chaincode"
    compose --profile fabric-tools run --rm -T fabric-tools generate || return 1
  fi
  install -d -m 500 -o 10001 -g 10001 "$FABRIC/client" || return 1
  install -m 444 -o 10001 -g 10001 "$user/signcerts/User1@org1.finora.local-cert.pem" "$FABRIC/client/cert.pem" || return 1
  install -m 400 -o 10001 -g 10001 "$user/keystore/priv_sk" "$FABRIC/client/key.pem" || return 1
  install -m 444 -o 10001 -g 10001 "$org1/peers/peer0.org1.finora.local/tls/ca.crt" "$FABRIC/client/tls-ca.crt" || return 1
}

# Orderer + hai peer join finora-channel, cài/approve/commit chaincode, smoke đọc qua từng peer. Chỉ chạy lần đầu:
# ledger nằm trong volume nên các lần sau không cần (xóa build/.network-ready để chạy lại, network.sh idempotent).
setup_fabric_network() {
  if [[ -f $FABRIC/build/.network-ready ]]; then return 0; fi
  log "Fabric: channel, chaincode, smoke"
  compose --profile fabric-tools run --rm -T fabric-tools all || return 1
  touch "$FABRIC/build/.network-ready"
}

# Các service truyền vào (service của project `finora`) phải running và, nếu có healthcheck, healthy.
# Dùng: wait_healthy <giây> <service...>
wait_healthy() {
  local timeout=$1 deadline lines svc line pending
  shift
  local expected=("$@")
  if (( ${#expected[@]} == 0 )); then
    log "Không đọc được danh sách service"
    return 1
  fi
  deadline=$((SECONDS + timeout))
  while :; do
    lines=$(docker ps -a --filter label=com.docker.compose.project=finora \
      --format '{{.Label "com.docker.compose.service"}}|{{.State}}|{{.Status}}')
    pending=()
    for svc in "${expected[@]}"; do
      line=$(grep -m1 "^${svc}|" <<<"$lines" || true)
      if [[ -z $line || $line != *"|running|"* || $line == *"(unhealthy)"* || $line == *"(health: starting)"* ]]; then
        pending+=("$svc")
      fi
    done
    if (( ${#pending[@]} == 0 )); then
      log "Cả cụm healthy"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      log "Hết ${timeout}s, chưa healthy: ${pending[*]}"
      return 1
    fi
    sleep 10
  done
}

log "Deploy $SHA, service: $SERVICES_ARG, registry: $REGISTRY"

for _ in $(seq 60); do
  if docker info >/dev/null 2>&1; then break; fi
  sleep 3
done
docker info >/dev/null

# --- Danh sách service ---
case $SERVICES_ARG in
  all) SERVICES=("${ALL_SERVICES[@]}") ;;
  - | "") SERVICES=() ;;
  *) IFS=, read -ra SERVICES <<<"$SERVICES_ARG" ;;
esac
for svc in "${SERVICES[@]}"; do
  if [[ " ${ALL_SERVICES[*]} " != *" $svc "* ]]; then
    log "Service lạ: $svc"
    exit 1
  fi
done

FIRST_RUN=false
if [[ ! -f $IMAGES ]]; then
  if [[ $SERVICES_ARG != all ]]; then
    log "Chưa có $IMAGES: lần deploy đầu phải dùng services=all"
    exit 1
  fi
  FIRST_RUN=true
fi

# --- Secret: chuyển một lần từ bố cục cũ, sau đó không bao giờ bị deploy thay ---
if [[ ! -f $ROOT/env/deploy.env ]]; then
  log "Chưa có $ROOT/env: chép .env từ $OLD_SRC"
  install -d -m 700 "$ROOT/env"
  install -m 600 "$OLD_SRC/deploy/.env" "$ROOT/env/deploy.env"
  for svc in "${ENV_SERVICES[@]}"; do
    install -m 600 "$OLD_SRC/finora-$svc/.env" "$ROOT/env/finora-$svc.env"
  done
fi

# --- Thư mục deploy/ của commit (repo public nên tải thẳng từ codeload) ---
if [[ ! -f $REL/deploy/docker-compose.azure.yml ]]; then
  log "Tải deploy/ của $SHORT"
  tmp=$(mktemp -d)
  ok=false
  for attempt in 1 2 3; do
    rm -rf "${tmp:?}"/*
    if curl -fsSL --max-time 120 "https://codeload.github.com/$REPO/tar.gz/$SHA" |
      tar -xz -C "$tmp" --strip-components=1 --wildcards '*/deploy/*' \
        '*/docker/fabric-network/config/*' '*/docker/fabric-network/scripts/*'; then
      ok=true
      break
    fi
    log "Tải lỗi lần $attempt"
    sleep 5
  done
  if [[ $ok != true ]]; then
    rm -rf "$tmp"
    exit 1
  fi
  rm -rf "$REL"
  mkdir -p "$REL"
  mv "$tmp/deploy" "$REL/deploy"
  mv "$tmp/docker" "$REL/docker"
  rm -rf "$tmp"
fi

# --- Công cụ trên VM ---
install -m 0755 "$REL/deploy/host/finora-compose.sh" "$ROOT/bin/finora-compose"
if [[ $ROOT == /opt/finora ]]; then ln -sfn "$ROOT/bin/finora-compose" /usr/local/bin/finora-compose; fi
if ! cmp -s "$REL/deploy/host/finora-idle-stop.sh" "$ROOT/bin/finora-idle-stop.sh"; then
  install -m 0755 "$REL/deploy/host/finora-idle-stop.sh" "$ROOT/bin/finora-idle-stop.sh"
fi
if ! cmp -s "$REL/deploy/host/finora-status.sh" "$ROOT/bin/finora-status.sh"; then
  install -m 0755 "$REL/deploy/host/finora-status.sh" "$ROOT/bin/finora-status.sh"
  systemctl restart finora-status.service || true
fi

# --- Ghi trạng thái mới, giữ bản trước để quay lại ---
PREV_CURRENT=$(readlink -f "$ROOT/current" 2>/dev/null || true)
declare -A TAGS=()
if [[ -f $IMAGES ]]; then
  cp "$IMAGES" "$IMAGES.prev"
  while IFS='=' read -r key value; do
    if [[ $key == *_TAG ]]; then TAGS[$key]=$value; fi
  done <"$IMAGES"
fi
for svc in "${SERVICES[@]}"; do
  TAGS[$(tag_var "$svc")]=$TAG
done
{
  echo "# Tag image đang chạy, do cd-deploy.sh ghi (commit $SHA, $(date -u +%FT%TZ))."
  echo "FINORA_REGISTRY=$REGISTRY"
  for svc in "${ALL_SERVICES[@]}"; do
    var=$(tag_var "$svc")
    echo "$var=${TAGS[$var]:-}"
  done
} >"$IMAGES.new"
mv "$IMAGES.new" "$IMAGES"

CADDY_CHANGED=false
if ! cmp -s "$REL/deploy/Caddyfile" "$ROOT/config/Caddyfile"; then
  if [[ -f $ROOT/config/Caddyfile ]]; then cp "$ROOT/config/Caddyfile" "$ROOT/config/Caddyfile.prev"; fi
  # Ghi đè nội dung, giữ inode: bind mount một file trỏ theo inode, thay file mới thì container vẫn thấy bản cũ.
  cat "$REL/deploy/Caddyfile" >"$ROOT/config/Caddyfile"
  CADDY_CHANGED=true
fi

ln -sfn "$REL" "$ROOT/current.new"
mv -Tf "$ROOT/current.new" "$ROOT/current"

rollback() {
  log "Quay về bản trước"
  if [[ $FIRST_RUN == true ]]; then
    rm -f "$IMAGES" "$ROOT/current"
    (cd "$OLD_SRC/deploy" && docker compose -f docker-compose.azure.yml up -d --no-build --remove-orphans)
    # shellcheck disable=SC2046
    wait_healthy 1200 $(cd "$OLD_SRC/deploy" && docker compose -f docker-compose.azure.yml config --services)
    return
  fi
  cp "$IMAGES.prev" "$IMAGES"
  if [[ -n $PREV_CURRENT ]]; then
    ln -sfn "$PREV_CURRENT" "$ROOT/current.new"
    mv -Tf "$ROOT/current.new" "$ROOT/current"
  fi
  if [[ $CADDY_CHANGED == true && -f $ROOT/config/Caddyfile.prev ]]; then
    cat "$ROOT/config/Caddyfile.prev" >"$ROOT/config/Caddyfile"
  fi
  compose up -d --no-build --remove-orphans
  if [[ $CADDY_CHANGED == true ]]; then
    compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile || true
  fi
  # shellcheck disable=SC2046
  wait_healthy 1200 $(compose config --services)
}

# Fineract vừa khởi động có thể mất tới khoảng 5 phút mới healthy (start_period 300s).
HEALTH_TIMEOUT=600
if [[ $FIRST_RUN == true || " ${SERVICES[*]} " == *" fineract "* ]]; then HEALTH_TIMEOUT=1200; fi
HEALTH_TIMEOUT=${FINORA_HEALTH_TIMEOUT:-$HEALTH_TIMEOUT}

deploy_ok=true
FABRIC_ON=false
if fabric_enabled; then
  FABRIC_ON=true
  prepare_fabric || deploy_ok=false
fi
if [[ $deploy_ok == true ]] && (( ${#SERVICES[@]} )); then
  log "Kéo image: ${SERVICES[*]} ($TAG)"
  pull=()
  for svc in "${SERVICES[@]}"; do
    read -ra targets <<<"$(compose_services_of "$svc")"
    pull+=("${targets[@]}")
  done
  compose pull --quiet "${pull[@]}" || deploy_ok=false
fi
if [[ $deploy_ok == true ]]; then
  log "docker compose up -d"
  compose up -d --no-build --remove-orphans || deploy_ok=false
fi
if [[ $deploy_ok == true && $CADDY_CHANGED == true ]]; then
  log "Caddyfile đổi: caddy reload"
  compose exec -T caddy caddy reload --config /etc/caddy/Caddyfile || deploy_ok=false
fi
if [[ $deploy_ok == true ]]; then
  # shellcheck disable=SC2046
  wait_healthy "$HEALTH_TIMEOUT" $(compose config --services) || deploy_ok=false
fi
if [[ $deploy_ok == true && $FABRIC_ON == true ]]; then
  setup_fabric_network || deploy_ok=false
fi

if [[ $deploy_ok != true ]]; then
  if rollback; then RESULT=rolled-back; fi
  exit 1
fi

echo "$SHA" >"$STATE/deployed-commit"
# Ổ 32 GB: bỏ image không còn container nào dùng (bản trước kéo lại từ GHCR khi cần) và giữ 5 bản deploy/ gần nhất.
docker image prune -af >/dev/null || true
CURRENT=$(readlink -f "$ROOT/current")
mapfile -t old_releases < <(ls -1dt "$ROOT"/releases/*/ 2>/dev/null | tail -n +6)
for dir in "${old_releases[@]}"; do
  dir=${dir%/}
  if [[ $dir != "$CURRENT" && $dir != "$PREV_CURRENT" ]]; then rm -rf "$dir"; fi
done
RESULT=ok
