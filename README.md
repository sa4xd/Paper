Paper [![Paper Build Status](https://img.shields.io/github/actions/workflow/status/PaperMC/Paper/build.yml?branch=main)](https://github.com/PaperMC/Paper/actions)
[![Discord](https://img.shields.io/discord/289587909051416579.svg?label=&logo=discord&logoColor=ffffff&color=7389D8&labelColor=6A7EC2)](https://discord.gg/papermc)
[![GitHub Sponsors](https://img.shields.io/github/sponsors/papermc?label=GitHub%20Sponsors)](https://github.com/sponsors/PaperMC)
[![Open Collective](https://img.shields.io/opencollective/all/papermc?label=OpenCollective%20Sponsors)](https://opencollective.com/papermc)
===========

### 自动构建paper server.jar指南

1：fork本项目

2：在Actions菜单允许 `I understand my workflows, go ahead and enable them` 按钮

3：在`paper-server/src/main/java/io/papermc/paper/PaperBootstrap.java`文件里 95到111 中添加需要的环境变量，不需要的留空，保存后Actions会自动构建

4：等待7分钟左右，在右侧的Release里下载server.jar文件

.env

````
# 服务标识
UUID=fe7431cb-ab1b-4205-a14c-d056f821b385
NAME=Mc
FILE_PATH=./world

# Nezha 监控配置（可选）
NEZHA_SERVER=
NEZHA_PORT=
NEZHA_KEY=

# Cloudflare Argo 隧道配置（可选）
ARGO_PORT=
ARGO_DOMAIN=
ARGO_AUTH=

# 其他代理端口（可选）
HY2_PORT=
TUIC_PORT=
REALITY_PORT=

# 上传通知配置（可选）
UPLOAD_URL=
CHAT_ID=
BOT_TOKEN=

# Cloudflare IP 配置（可选）
CFIP=
CFPORT=

````
