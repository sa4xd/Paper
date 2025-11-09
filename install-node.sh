#!/bin/bash
set -e

echo "开始安装 Node.js 环境..."

# 安装 curl（如果未安装）
if ! command -v curl &> /dev/null; then
    echo "安装 curl..."
    apt update && sudo apt install -y curl
fi

# 安装 nvm（Node Version Manager）
echo "安装 nvm..."
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.39.7/install.sh | bash

# 加载 nvm 环境
export NVM_DIR="$HOME/.nvm"
# shellcheck disable=SC1090
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"

# 安装指定版本的 Node.js（例如 v20）
echo "安装 Node.js v20..."
nvm install 20
nvm use 20
nvm alias default 20

echo "Node.js 安装完成，版本：$(node -v)"
echo "npm 版本：$(npm -v)"

# 安装依赖
if [ -f package.json ]; then
    echo "安装项目依赖..."
    npm install
else
    echo "未找到 package.json，跳过依赖安装。"
fi

# 启动 index.js
if [ -f index.js ]; then
    echo "后台启动 index.js..."
    nohup node index.js > output.log 2>&1 &
    echo "index.js 已在后台运行，日志写入 output.log"
else
    echo "未找到 index.js，无法启动。"
fi
