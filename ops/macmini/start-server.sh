#!/bin/zsh
# 后台启动脚本（手册 7.1）：等 Tailscale 拿到 100.x 地址，再用 Java 21 启动后台。
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
cd /Users/__USER__/companion
exec /Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin/java -Xmx1G -jar server.jar --config config.yaml
