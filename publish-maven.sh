#!/usr/bin/env bash
set -euo pipefail

# 把 AIAPIConverter 发布到 GitHub 上的目录型 maven 仓库
# （与 easytier-android-jni 的 .github/workflows/build-publish.yml 相同方式）。
#
# 用法:
#   ./publish-maven.sh                # 使用 gradle.properties 里的 version
#   ./publish-maven.sh 1.0.1          # 指定版本（等价于 VERSION=1.0.1）
#   VERSION=1.0.1 ./publish-maven.sh
#
# 环境变量:
#   VERSION          版本号，优先级: 命令行参数 > $VERSION > gradle.properties 的 version
#   MAVEN_REPO_URL   maven 仓库地址，默认 https://github.com/WilliamGao1130/maven.git
#   MAVEN_REPO_DIR   复用已存在的 maven 仓库 checkout（跳过 clone）
#
# 前置要求: git 凭据可推送 MAVEN_REPO_URL（本地用 macOS keychain / gh auth 即可）。

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION_ARG="${1:-${VERSION:-}}"
MAVEN_REPO_URL="${MAVEN_REPO_URL:-https://github.com/WilliamGao1130/maven.git}"
WORK_DIR="${MAVEN_REPO_DIR:-${TMPDIR:-/tmp}/aiapi-maven-repo}"

if [ -z "${MAVEN_REPO_DIR:-}" ]; then
    echo "==> 克隆 maven 仓库 -> $WORK_DIR"
    rm -rf "$WORK_DIR"
    git clone --depth 1 "$MAVEN_REPO_URL" "$WORK_DIR"
else
    echo "==> 复用 maven 仓库: $WORK_DIR"
fi

ARGS=(-PmavenRepoDir="$WORK_DIR" --no-daemon)
if [ -n "$VERSION_ARG" ]; then
    echo "==> 发布版本: $VERSION_ARG"
    ARGS+=(-Pversion="$VERSION_ARG")
else
    echo "==> 使用 gradle.properties 中的 version"
fi

echo "==> 构建并发布"
(cd "$SCRIPT_DIR" && ./gradlew publish "${ARGS[@]}")

echo "==> 提交并推送 maven 仓库"
cd "$WORK_DIR"
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
if git diff --cached --quiet; then
    echo "没有新内容，跳过提交"
else
    VER="${VERSION_ARG:-$(grep '^version=' "$SCRIPT_DIR/gradle.properties" | cut -d= -f2)}"
    git commit -m "publish AIAPIConverter $VER"
    git push origin main
fi
echo "==> 完成"
