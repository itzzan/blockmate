#!/bin/zsh
# Ollama 常驻启动脚本（手册 4.4）。由 ~/Library/LaunchAgents/com.companion.ollama.plist 调用。
#
# 现阶段只有 MacBook 一台机器，先只听本机 127.0.0.1。
# Mac mini 到了以后，按手册 4.4 改成监听 Tailscale 地址：把下面"本机版"三行注释掉，把"Tailscale 版"四行取消注释。

# ---- 本机版（现在用）----
export OLLAMA_HOST="127.0.0.1:11434"

# ---- Tailscale 版（Mac mini 到了以后用）----
# TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
# until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
# export OLLAMA_HOST="$("$TS" ip -4):11434"

export OLLAMA_KEEP_ALIVE=-1          # 模型加载后一直留在内存，不闲置卸载
export OLLAMA_CONTEXT_LENGTH=16384   # 和 ops/Modelfile 里的 num_ctx 一致
exec /opt/homebrew/bin/ollama serve
