#!/bin/zsh
# 世界存档每日备份（手册 7.1）：
# 1) 让服务器暂停写盘并把内存里的世界写全（save-off、save-all）；2) 打包 world；3) 恢复写盘（save-on）；4) 删 14 天前的包。
# RCON 密码从 ~/mcserver/rcon.pass 读（一行，chmod 600），不要写在脚本里。
set -u
MC=/Users/__USER__/mcserver
PASS=$(cat "$MC/rcon.pass")
RCON=(/opt/homebrew/bin/mcrcon -H 127.0.0.1 -P 25575 -p "$PASS")
mkdir -p "$MC/backups"
"${RCON[@]}" "save-off" "save-all flush" >/dev/null
sleep 5
tar czf "$MC/backups/world-$(date +%F).tgz" -C "$MC" world
STATUS=$?
"${RCON[@]}" "save-on" >/dev/null
find "$MC/backups" -name 'world-*.tgz' -mtime +14 -delete
echo "$(date '+%F %T') backup exit=$STATUS size=$(du -h "$MC/backups/world-$(date +%F).tgz" 2>/dev/null | cut -f1)"
exit $STATUS
