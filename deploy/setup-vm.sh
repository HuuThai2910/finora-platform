#!/usr/bin/env bash
# Cài môi trường cho VM FINORA (Ubuntu 24.04): Docker, jq, swap 4 GB, script ghi trạng thái
# và các timer tự tắt VM. Chạy một lần từ thư mục deploy trên VM: sudo bash setup-vm.sh
set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ADMIN_USER=${SUDO_USER:-azureuser}

mkdir -p /opt/finora/bin /opt/finora/state/public /opt/finora/src
chown "$ADMIN_USER":"$ADMIN_USER" /opt/finora/src
# Trong lúc cài và build, không cho VM tự tắt. Xóa file này khi đã chạy xong cả cụm.
touch /opt/finora/state/keep-awake

apt-get update -y
apt-get install -y ca-certificates curl jq
if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
usermod -aG docker "$ADMIN_USER"
systemctl enable --now docker

# Swap 4 GB đỡ cho lúc build Maven và lúc cả cụm cùng khởi động; swappiness thấp để JVM
# đang chạy không bị đẩy ra swap khi RAM còn đủ.
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 4G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >>/etc/fstab
fi
echo 'vm.swappiness=10' >/etc/sysctl.d/90-finora.conf
sysctl --system >/dev/null

install -m 0755 "$HERE/host/finora-status.sh" /opt/finora/bin/finora-status.sh
install -m 0755 "$HERE/host/finora-idle-stop.sh" /opt/finora/bin/finora-idle-stop.sh
install -m 0644 "$HERE"/host/*.service "$HERE"/host/*.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now finora-status.service finora-idle-stop.timer finora-nightly-stop.timer

echo "Đã cài xong. Khi cả cụm chạy ổn, xóa /opt/finora/state/keep-awake để VM tự tắt khi rảnh."
