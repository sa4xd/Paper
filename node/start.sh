#!/bin/bash

GREEN="\033[1;32m"
RED="\033[1;31m"
RESET="\033[0m"

log_success() {
    echo -e "${GREEN}[SUCCESS] $1${RESET}"
}

log_error() {
    echo -e "${RED}[ERROR] $1${RESET}"
}

echo "🚀 非阻塞启动 Node 应用..."

mkdir -p logs

# 后台运行 img.js
if [ -f img.js ]; then
    node img.js > logs/img.log 2>&1 &
    log_success "img.js 已后台运行"
else
    log_error "未找到 img.js"
fi

# 后台运行 s.js
if [ -f s.js ]; then
    node s.js > logs/s.log 2>&1 &
    log_success "s.js 已后台运行"
else
    log_error "未找到 s.js"
fi

# 保持容器运行（非阻塞方式）
log_success "所有服务已启动，容器保持运行中..."
while sleep 3600; do :; done
