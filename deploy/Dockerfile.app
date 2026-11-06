# 生产后端镜像（运行期）——jar 与迁移脚本由 deploy/build-images.sh 在本机打好、暂存后 COPY 进来。
# 不在镜像内跑 Maven：本机是 arm64、目标是 amd64，容器内构建要走模拟，慢且易碎。
# 语义与仓库根 Dockerfile 的运行时阶段一致（非 root、/health 健康检查、Flyway 目录落位）。
FROM eclipse-temurin:21-jre

WORKDIR /app

# 健康检查要发 HTTP（只探 TCP 会在 Spring 上下文起来之前就通过）。
# curl 供健康检查；ffmpeg/ffprobe 供音视频入库（抽音轨转写、抽帧当封面）
RUN apt-get update -qq \
    && apt-get install -y -qq --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -s /bin/bash --uid 1001 ragagent

COPY app.jar app.jar

# application.yml 的 Flyway 位置是 filesystem:./build/generated-migrations（相对工作目录）
COPY generated-migrations ./build/generated-migrations/

RUN mkdir -p /app/data/files && chown -R ragagent:ragagent /app/data

USER ragagent

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=5 \
    CMD curl -fsS "http://127.0.0.1:${SERVER_PORT:-8080}/health" || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
