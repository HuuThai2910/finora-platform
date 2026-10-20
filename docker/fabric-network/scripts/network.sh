#!/usr/bin/env bash
# Dựng mạng Fabric local của FINORA (W3D). Chạy bên trong container fabric-tools (profile fabric-tools) với thư mục
# docker/fabric-network mount vào /work; được gọi bởi docker/fabric-network/fabric-network.ps1.
#
#   generate  sinh crypto (cryptogen), genesis block của finora-channel, package CCaaS và core.yaml cho peer
#   channel   orderer join channel qua channel participation API, hai peer join channel
#   deploy    install package trên hai peer, hai tổ chức approve, commit định nghĩa finora-ledger
#   smoke     truy vấn chaincode qua từng peer (chỉ đọc, không ghi dữ liệu thử lên chain)
#
# Mọi bước idempotent: chạy lại chỉ làm phần còn thiếu. Script không in nội dung khóa/chứng thư.
set -euo pipefail

WORK=/work
CRYPTO="$WORK/crypto-config"
BUILD="$WORK/build"
CHANNEL=finora-channel
CC_NAME=finora-ledger
CC_VERSION=1.0
CC_SEQUENCE=1
CC_LABEL="${CC_NAME}_${CC_VERSION}"

ORDERER_HOST=orderer.finora.local
ORDERER_ADDRESS="$ORDERER_HOST:7050"
ORDERER_ADMIN_ADDRESS="$ORDERER_HOST:7053"
ORDERER_TLS_DIR="$CRYPTO/ordererOrganizations/finora.local/orderers/$ORDERER_HOST/tls"
ORDERER_CA="$ORDERER_TLS_DIR/ca.crt"

log() { printf '[fabric] %s\n' "$*"; }
fail() { printf '[fabric][LỖI] %s\n' "$*" >&2; exit 1; }

use_org() {
  case "$1" in
    org1)
      export CORE_PEER_LOCALMSPID=Org1MSP
      export CORE_PEER_ADDRESS=peer0.org1.finora.local:7051
      ;;
    org2)
      export CORE_PEER_LOCALMSPID=Org2MSP
      export CORE_PEER_ADDRESS=peer0.org2.finora.local:9051
      ;;
    *) fail "tổ chức không hợp lệ: $1" ;;
  esac
  local domain="$1.finora.local"
  export CORE_PEER_TLS_ENABLED=true
  export CORE_PEER_TLS_ROOTCERT_FILE="$CRYPTO/peerOrganizations/$domain/peers/peer0.$domain/tls/ca.crt"
  export CORE_PEER_MSPCONFIGPATH="$CRYPTO/peerOrganizations/$domain/users/Admin@$domain/msp"
}

peer_tls_ca() {
  local domain="$1.finora.local"
  printf '%s' "$CRYPTO/peerOrganizations/$domain/peers/peer0.$domain/tls/ca.crt"
}

# Thử lại lệnh vài lần vì node vừa khởi động có thể chưa nhận kết nối.
retry() {
  local attempts=$1; shift
  local delay=$1; shift
  local n=1
  until "$@"; do
    if (( n >= attempts )); then
      return 1
    fi
    log "lệnh chưa thành công, thử lại sau ${delay}s ($n/$attempts)"
    sleep "$delay"
    n=$((n + 1))
  done
}

generate() {
  local marker="$CRYPTO/peerOrganizations/org1.finora.local/users/User1@org1.finora.local/msp/signcerts/User1@org1.finora.local-cert.pem"
  if [[ -f "$marker" ]]; then
    log "crypto đã có, giữ nguyên (xóa bằng fabric-network.ps1 -Action Down -RemoveData)"
  else
    log "sinh crypto bằng cryptogen"
    cryptogen generate --config="$WORK/config/crypto-config.yaml" --output="$CRYPTO" > /dev/null
  fi

  mkdir -p "$BUILD/channel" "$BUILD/chaincode" "$BUILD/peercfg"
  if [[ -f "$BUILD/channel/$CHANNEL.block" ]]; then
    log "genesis block $CHANNEL đã có"
  else
    log "sinh genesis block $CHANNEL"
    configtxgen -configPath "$WORK/config" -profile FinoraChannel -channelID "$CHANNEL" \
      -outputBlock "$BUILD/channel/$CHANNEL.block" > /dev/null 2>&1 \
      || fail "configtxgen không tạo được genesis block"
  fi

  if [[ -f "$BUILD/chaincode/package-id" ]]; then
    log "package CCaaS đã có: $(cat "$BUILD/chaincode/package-id")"
  else
    log "đóng gói chaincode $CC_LABEL dạng CCaaS"
    local pkg="$BUILD/chaincode/pkg"
    mkdir -p "$pkg"
    # {{.peername}} do builder ccaas của từng peer thay bằng CHAINCODE_AS_A_SERVICE_BUILDER_CONFIG.
    printf '%s\n' '{"address":"{{.peername}}-finora-ledger:9999","dial_timeout":"10s","tls_required":false}' \
      > "$pkg/connection.json"
    printf '%s\n' "{\"type\":\"ccaas\",\"label\":\"$CC_LABEL\"}" > "$pkg/metadata.json"
    # tar/gzip cố định mtime/owner để package ID không đổi khi đóng gói lại.
    tar --sort=name --mtime='2026-01-01 00:00:00Z' --owner=0 --group=0 --numeric-owner \
      -C "$pkg" -cf - connection.json | gzip -n > "$pkg/code.tar.gz"
    tar --sort=name --mtime='2026-01-01 00:00:00Z' --owner=0 --group=0 --numeric-owner \
      -C "$pkg" -cf - metadata.json code.tar.gz | gzip -n > "$BUILD/chaincode/$CC_NAME.tar.gz"
    peer lifecycle chaincode calculatepackageid "$BUILD/chaincode/$CC_NAME.tar.gz" > "$BUILD/chaincode/package-id"
    log "package ID: $(cat "$BUILD/chaincode/package-id")"
  fi

  # Peer chỉ dùng builder CCaaS: bỏ vm.endpoint để peer không đăng ký health check Docker (không mount docker.sock).
  sed -e 's#^\([[:space:]]*endpoint:\)[[:space:]]*unix:///var/run/docker.sock#\1#' \
    /etc/hyperledger/fabric/core.yaml > "$BUILD/peercfg/core.yaml"
  grep -q 'ccaas_builder' "$BUILD/peercfg/core.yaml" || fail "core.yaml không có builder ccaas"
  # Chỉ xét dòng cấu hình thật; dòng comment ví dụ "# unix:///var/run/docker.sock" vẫn được giữ.
  if grep -Eq '^[[:space:]]*endpoint:[[:space:]]*unix://' "$BUILD/peercfg/core.yaml"; then
    fail "không bỏ được vm.endpoint trong core.yaml"
  fi
  log "generate xong"
}

orderer_channels() {
  osnadmin channel list -o "$ORDERER_ADMIN_ADDRESS" --ca-file "$ORDERER_CA" \
    --client-cert "$ORDERER_TLS_DIR/server.crt" --client-key "$ORDERER_TLS_DIR/server.key" 2>/dev/null
}

orderer_reachable() {
  orderer_channels > /dev/null
}

orderer_has_channel() {
  orderer_channels | grep -q "\"$CHANNEL\""
}

orderer_join() {
  osnadmin channel join --channelID "$CHANNEL" --config-block "$BUILD/channel/$CHANNEL.block" \
    -o "$ORDERER_ADMIN_ADDRESS" --ca-file "$ORDERER_CA" \
    --client-cert "$ORDERER_TLS_DIR/server.crt" --client-key "$ORDERER_TLS_DIR/server.key" > /dev/null
}

peer_reachable() {
  peer channel list > /dev/null 2>&1
}

peer_has_channel() {
  peer channel list 2>/dev/null | grep -qx "$CHANNEL"
}

channel() {
  [[ -f "$BUILD/channel/$CHANNEL.block" ]] || fail "thiếu genesis block, chạy generate trước"
  retry 20 3 orderer_reachable || fail "không kết nối được admin API của orderer"
  if orderer_has_channel; then
    log "orderer đã ở trong $CHANNEL"
  else
    log "orderer join $CHANNEL"
    retry 10 3 orderer_join || fail "orderer không join được channel"
  fi
  for org in org1 org2; do
    use_org "$org"
    retry 20 3 peer_reachable || fail "không kết nối được peer0.$org"
    if peer_has_channel; then
      log "peer0.$org đã join $CHANNEL"
    else
      log "peer0.$org join $CHANNEL"
      retry 10 3 peer channel join -b "$BUILD/channel/$CHANNEL.block" > /dev/null 2>&1 \
        || fail "peer0.$org không join được channel"
    fi
  done
  log "channel xong"
}

package_id() {
  [[ -f "$BUILD/chaincode/package-id" ]] || fail "thiếu package-id, chạy generate trước"
  tr -d '[:space:]' < "$BUILD/chaincode/package-id"
}

committed() {
  peer lifecycle chaincode querycommitted --channelID "$CHANNEL" --name "$CC_NAME" --output json 2>/dev/null \
    | jq -e ".sequence >= $CC_SEQUENCE" > /dev/null
}

approved_by() {
  local msp=$1
  peer lifecycle chaincode checkcommitreadiness --channelID "$CHANNEL" --name "$CC_NAME" \
    --version "$CC_VERSION" --sequence "$CC_SEQUENCE" --output json 2>/dev/null \
    | jq -e ".approvals.$msp == true" > /dev/null
}

deploy() {
  local pkg_id
  pkg_id=$(package_id)
  for org in org1 org2; do
    use_org "$org"
    if peer lifecycle chaincode queryinstalled --output json 2>/dev/null \
      | jq -e --arg id "$pkg_id" '.installed_chaincodes // [] | map(.package_id) | index($id) != null' > /dev/null; then
      log "peer0.$org đã cài $pkg_id"
    else
      log "cài package trên peer0.$org"
      retry 5 3 peer lifecycle chaincode install "$BUILD/chaincode/$CC_NAME.tar.gz" > /dev/null 2>&1 \
        || fail "peer0.$org không cài được chaincode"
    fi
  done

  use_org org1
  if committed; then
    log "$CC_NAME sequence $CC_SEQUENCE đã commit trên $CHANNEL"
  else
    for org in org1 org2; do
      use_org "$org"
      if approved_by "$CORE_PEER_LOCALMSPID"; then
        log "$CORE_PEER_LOCALMSPID đã approve"
      else
        log "$CORE_PEER_LOCALMSPID approve $CC_NAME"
        retry 5 3 peer lifecycle chaincode approveformyorg -o "$ORDERER_ADDRESS" --tls --cafile "$ORDERER_CA" \
          --channelID "$CHANNEL" --name "$CC_NAME" --version "$CC_VERSION" --sequence "$CC_SEQUENCE" \
          --package-id "$pkg_id" --waitForEvent > /dev/null 2>&1 \
          || fail "$CORE_PEER_LOCALMSPID không approve được chaincode"
      fi
    done
    use_org org1
    log "commit $CC_NAME (cần cả hai tổ chức endorse)"
    retry 5 3 peer lifecycle chaincode commit -o "$ORDERER_ADDRESS" --tls --cafile "$ORDERER_CA" \
      --channelID "$CHANNEL" --name "$CC_NAME" --version "$CC_VERSION" --sequence "$CC_SEQUENCE" \
      --peerAddresses peer0.org1.finora.local:7051 --tlsRootCertFiles "$(peer_tls_ca org1)" \
      --peerAddresses peer0.org2.finora.local:9051 --tlsRootCertFiles "$(peer_tls_ca org2)" \
      --waitForEvent > /dev/null 2>&1 \
      || fail "không commit được định nghĩa chaincode"
  fi
  log "deploy xong"
}

query_missing_proof() {
  # UUID cố định chưa từng được ghi: kết quả rỗng chứng minh chaincode chạy và trả "không tìm thấy" đúng contract.
  local result
  result=$(peer chaincode query -C "$CHANNEL" -n "$CC_NAME" \
    -c '{"Args":["GetProof","00000000-0000-5000-8000-000000000000"]}' 2>/dev/null) || return 1
  [[ -z "$result" ]]
}

smoke() {
  for org in org1 org2; do
    use_org "$org"
    if retry 20 3 query_missing_proof; then
      log "peer0.$org: chaincode $CC_NAME trả lời GetProof"
    else
      fail "peer0.$org: chaincode chưa trả lời (kiểm container CCaaS)"
    fi
  done
  log "smoke xong"
}

# Đọc một proof trực tiếp từ peer của tổ chức chỉ định (mặc định org2 = kiểm toán), không qua finora-blockchain.
get_proof() {
  local org=${1:-org2}
  local proof_id=${2:-}
  [[ "$proof_id" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || fail "proofId phải là UUID chữ thường"
  use_org "$org"
  local record height
  record=$(peer chaincode query -C "$CHANNEL" -n "$CC_NAME" -c "{\"Args\":[\"GetProof\",\"$proof_id\"]}" 2>/dev/null) \
    || fail "không truy vấn được peer0.$org"
  height=$(peer channel getinfo -c "$CHANNEL" 2>/dev/null | sed -n 's/.*"height":\([0-9]*\).*/\1/p')
  log "peer0.$org ($CORE_PEER_LOCALMSPID), chiều cao chain $CHANNEL: ${height:-?}"
  if [[ -z "$record" ]]; then
    log "không có proof $proof_id trên chain"
  else
    log "$record"
  fi
}

case "${1:-}" in
  generate) generate ;;
  channel) channel ;;
  deploy) deploy ;;
  smoke) smoke ;;
  all) channel; deploy; smoke ;;
  proof) get_proof "${2:-org2}" "${3:-}" ;;
  *) fail "dùng: network.sh generate|channel|deploy|smoke|all|proof <org1|org2> <proofId>" ;;
esac
