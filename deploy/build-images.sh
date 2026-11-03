#!/usr/bin/env bash
# 构建 ragagent 生产镜像（linux/amd64），供 `docker load` 到服务器使用。
#
#   VERSION=01b9820 ./deploy/build-images.sh            # 全量：后端 jar + 前端 dist + 两个镜像
#   ./deploy/build-images.sh --save                     # 额外导出 deploy/.build/images.tar.gz
#   ./deploy/build-images.sh --skip-frontend            # 只重建后端（jar 有改动时）
#
# 本机是 arm64、目标是 amd64：Maven 与 Vite 都在本机原生跑，镜像里只做 COPY，
# 避免在容器内模拟 x86 编译。
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BUILD="$ROOT/deploy/.build"
PLATFORM=${PLATFORM:-linux/amd64}
VERSION=${VERSION:-$(git -C "$ROOT" rev-parse --short HEAD)}

JAVA_HOME=${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}
NODE_BIN=${NODE_BIN:-/opt/homebrew/opt/node@22/bin}
export JAVA_HOME
export PATH="$NODE_BIN:$JAVA_HOME/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

save=0
skip_backend=0
skip_frontend=0
for arg in "$@"; do
  case "$arg" in
    --save) save=1 ;;
    --skip-backend) skip_backend=1 ;;
    --skip-frontend) skip_frontend=1 ;;
    *) echo "未知参数: $arg" >&2; exit 2 ;;
  esac
done

stage_backend() {
  echo "== 后端：Maven 打包（跳过测试与 spotless 检查，闸门在 CI） =="
  mvn -B -q -f "$ROOT/pom.xml" -DskipTests -Dspotless.check.skip=true clean package
  rm -rf "$BUILD/app"
  mkdir -p "$BUILD/app"
  cp "$ROOT/boot/target/boot-0.0.1-SNAPSHOT.jar" "$BUILD/app/app.jar"
  cp -R "$ROOT/migrations/versioned" "$BUILD/app/generated-migrations"
}

stage_frontend() {
  echo "== 前端：Vite 构建 =="
  if [ ! -d "$ROOT/frontend/node_modules" ]; then
    ( cd "$ROOT/frontend" && npm ci )
  fi
  ( cd "$ROOT/frontend" && VITE_IS_DOCKER=true VITE_FRONTEND_COMMIT="$VERSION" npm run build )
  rm -rf "$BUILD/ui"
  mkdir -p "$BUILD/ui"
  cp -R "$ROOT/frontend/dist" "$BUILD/ui/dist"
  # 预压缩静态资源：配合容器 nginx 的 gzip_static，命中 .gz 就直接发送，
  # 省掉每次请求现压的 CPU（2 核机器上 1 MB 的包现压要几十毫秒，还会拖 TTFB）。
  if command -v gzip >/dev/null 2>&1; then
    find "$BUILD/ui/dist" -type f \( -name '*.js' -o -name '*.css' -o -name '*.html' \) -size +1k \
      -exec sh -c 'gzip -9 -c "$1" > "$1.gz"' _ {} \;
    echo "== 预压缩完成：$(find "$BUILD/ui/dist" -name '*.gz' | wc -l | tr -d ' ') 个 .gz =="
  fi
  cp "$ROOT/frontend/nginx.conf" "$ROOT/frontend/nginx-api-proxy.conf" \
     "$ROOT/frontend/nginx-http.conf" "$ROOT/frontend/docker-entrypoint.sh" "$BUILD/ui/"
}

[ "$skip_backend" -eq 1 ] || stage_backend
[ "$skip_frontend" -eq 1 ] || stage_frontend

if [ "$skip_backend" -eq 0 ]; then
  echo "== 镜像：ragagent-server:$VERSION ($PLATFORM) =="
  docker buildx build --platform "$PLATFORM" --load \
    -f "$ROOT/deploy/Dockerfile.app" -t "ragagent-server:$VERSION" "$BUILD/app"
fi
if [ "$skip_frontend" -eq 0 ]; then
  echo "== 镜像：ragagent-ui:$VERSION ($PLATFORM) =="
  docker buildx build --platform "$PLATFORM" --load \
    -f "$ROOT/deploy/Dockerfile.ui" -t "ragagent-ui:$VERSION" "$BUILD/ui"
fi

docker images --format '{{.Repository}}:{{.Tag}}\t{{.Size}}' | grep -E "ragagent-(server|ui):$VERSION" || true

if [ "$save" -eq 1 ]; then
  echo "== 导出 $BUILD/images.tar.gz =="
  docker save $(for t in ragagent-server ragagent-ui; do
      docker image inspect "$t:$VERSION" >/dev/null 2>&1 && echo "$t:$VERSION"
    done) | gzip -1 > "$BUILD/images.tar.gz"
  ls -lh "$BUILD/images.tar.gz"
fi
