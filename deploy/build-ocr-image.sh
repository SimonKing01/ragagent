#!/usr/bin/env bash
# 构建 OCR 服务镜像（RapidOCR，CPU）。context 是仓库根目录（要拷 scripts/ocr-server.py）。
#
#   ./deploy/build-ocr-image.sh            # 构建并导出到 deploy/.build/ocr-image.tar.gz
#   ./deploy/build-ocr-image.sh --no-save  # 只构建
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${OCR_VERSION:-latest}"
PLATFORM="${PLATFORM:-linux/amd64}"

echo "== OCR 镜像：ragagent-ocr:$TAG ($PLATFORM) =="
docker buildx build --platform "$PLATFORM" --load \
  -f "$ROOT/deploy/Dockerfile.ocr" -t "ragagent-ocr:$TAG" "$ROOT"

if [ "${1:-}" = "--no-save" ]; then
  exit 0
fi
mkdir -p "$ROOT/deploy/.build"
docker save "ragagent-ocr:$TAG" | gzip > "$ROOT/deploy/.build/ocr-image.tar.gz"
echo "== 已导出 $ROOT/deploy/.build/ocr-image.tar.gz =="
echo "   服务器上：gunzip -c ocr-image.tar.gz | sudo docker load"
