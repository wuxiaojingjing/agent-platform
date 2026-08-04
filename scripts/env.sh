#!/usr/bin/env bash
# 统一构建与运行环境。用法：source scripts/env.sh
#
# Maven 默认会落到系统最新 JDK（本机为 26），Spring Boot 3.5 与部分注解处理器只在
# 21 上验证过，因此这里强制钉住 21。

set -u

JDK21_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
if [ -z "${JDK21_HOME}" ]; then
  echo "[env] 找不到 JDK 21，请先安装（brew install openjdk@21 或 Temurin 21）" >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME="${JDK21_HOME}"
export PATH="${JAVA_HOME}/bin:${PATH}"

# 仓库路径含中文。locale 不是 UTF-8 时，JVM 按 ASCII 解码命令行参数，
# 传给 mvn 的 -s 路径会被替换成问号而找不到文件。
export LANG="${LANG:-en_US.UTF-8}"
export LC_ALL="${LC_ALL:-en_US.UTF-8}"

# 脚本自身路径。zsh 下 BASH_SOURCE 为空，直接用它算出的根目录会变成 "/"，
# 于是 -s /deploy/maven-settings.xml 找不到文件。两种 shell 都要能 source。
if [ -n "${BASH_SOURCE[0]:-}" ]; then
  SELF="${BASH_SOURCE[0]}"
else
  SELF="$0"
fi
SCRIPT_DIR="$(cd "$(dirname "${SELF}")" && pwd)"

# 用项目内 settings.xml 走镜像仓，避免直连 Maven Central 时 TLS 被中断导致构建不可复现。
# 不改用户全局 ~/.m2/settings.xml。
export MAVEN_ARGS="-s ${SCRIPT_DIR}/../dev/local/maven-settings.xml"

# 密钥只从环境变量进程内传递，不落配置文件、不进仓库
ENV_LOCAL="${SCRIPT_DIR}/env.local.sh"
if [ -f "${ENV_LOCAL}" ]; then
  # shellcheck disable=SC1090
  . "${ENV_LOCAL}"
  echo "[env] 已加载 ${ENV_LOCAL}"
else
  echo "[env] 未找到 scripts/env.local.sh，模型网关将不可用（系统会走降级路径）" >&2
fi

echo "[env] JAVA_HOME=${JAVA_HOME}"
java -version 2>&1 | head -1
