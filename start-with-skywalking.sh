#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────
#  start-with-skywalking.sh
#  用途：下载 SkyWalking Java Agent（首次运行），然后带 Agent 启动应用
#
#  使用方式：
#    chmod +x start-with-skywalking.sh
#    ./start-with-skywalking.sh
# ─────────────────────────────────────────────────────────────

set -e

AGENT_VERSION="9.3.0"
AGENT_DIR="$(pwd)/skywalking-agent"
AGENT_JAR="${AGENT_DIR}/skywalking-agent.jar"
APP_JAR="target/demo-0.0.1-SNAPSHOT.jar"

# ── 1. 下载 Agent（若尚未下载）────────────────────────────
if [ ! -f "${AGENT_JAR}" ]; then
  echo ">>> SkyWalking Agent 不存在，开始下载 v${AGENT_VERSION} ..."

  DOWNLOAD_URL="https://archive.apache.org/dist/skywalking/java-agent/${AGENT_VERSION}/apache-skywalking-java-agent-${AGENT_VERSION}.tgz"
  TMP_DIR=$(mktemp -d)

  curl -L "${DOWNLOAD_URL}" -o "${TMP_DIR}/agent.tgz"
  tar -xzf "${TMP_DIR}/agent.tgz" -C "${TMP_DIR}"

  # 将 plugins、optional-plugins、config 复制到本地 agent 目录
  cp -r "${TMP_DIR}/skywalking-agent/"* "${AGENT_DIR}/"
  rm -rf "${TMP_DIR}"

  echo ">>> Agent 下载完成：${AGENT_JAR}"
else
  echo ">>> 已找到 Agent：${AGENT_JAR}"
fi

# ── 2. 构建应用 ────────────────────────────────────────────
echo ">>> 构建 Spring Boot 应用 ..."
./mvnw clean package -DskipTests -q

# ── 3. 启动应用（挂载 Agent）──────────────────────────────
echo ">>> 启动应用，已挂载 SkyWalking Agent ..."
echo "    服务名：demo-service"
echo "    OAP 地址：127.0.0.1:11800"
echo "    应用地址：http://localhost:8080"
echo "    SkyWalking UI：http://localhost:8088"
echo ""

java \
  -javaagent:"${AGENT_JAR}" \
  -Dskywalking.agent.service_name=demo-service \
  -Dskywalking.collector.backend_service=127.0.0.1:11800 \
  -Dskywalking.logging.level=INFO \
  -jar "${APP_JAR}"
