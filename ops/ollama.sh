#!/bin/zsh
# Ollama 常驻启动脚本（手册 4.4）。由 ~/Library/LaunchAgents/com.companion.ollama.plist 调用。
#
# 监听 Tailscale 地址（手册 4.4）：等 Tailscale 拿到 100.x 地址再启动，只在 tailnet 内可见，Mac mini 上的后台靠它调模型。
# 只有一台机器、想只听本机时，把"Tailscale 版"三行注释掉，把"本机版"一行取消注释。

# ---- Tailscale 版（现在用，2026-10-21 Mac mini 到了以后切换）----
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
export OLLAMA_HOST="$("$TS" ip -4):11434"

# ---- 本机版（单机开发时用）----
# export OLLAMA_HOST="127.0.0.1:11434"

export OLLAMA_KEEP_ALIVE=30m         # 闲置 30 分钟自动卸载模型（2026-10-27 主人定：不用时还回 26 GB，代价是隔久了第一句等 30–60 秒）
export OLLAMA_CONTEXT_LENGTH=16384   # 和 ops/Modelfile 里的 num_ctx 一致
exec /opt/homebrew/bin/ollama serve
