#!/bin/bash
log_success() {
    echo -e "${GREEN}[SUCCESS] $1${RESET}"
}

log_error() {
    echo -e "${RED}[ERROR] $1${RESET}"
}


# 启动 Node 应用
echo "🚀 启动 Node 应用..."

node s.js

node img.js


# 保持容器运行（可选）
tail -f /dev/null

