#!/usr/bin/env bash
# 本地构建前先跑一次：把离线中文模型放进 assets。
set -euo pipefail
cd "$(dirname "$0")/.."
URL="https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip"
TMP="${TMPDIR:-/tmp}"
echo "下载 $URL ..."
curl -L --retry 3 -o "$TMP/model-cn.zip" "$URL"
rm -rf "$TMP/model-cn" app/src/main/assets/model-cn
mkdir -p app/src/main/assets
unzip -q "$TMP/model-cn.zip" -d "$TMP/model-cn"
mv "$TMP/model-cn/vosk-model-small-cn-0.22" app/src/main/assets/model-cn
echo "已放入 app/src/main/assets/model-cn （约 42MB）"
