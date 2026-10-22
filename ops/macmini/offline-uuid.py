#!/usr/bin/env python3
"""算离线模式玩家的 UUID，给 whitelist.json 用。

为什么需要它：服务器 online-mode=false 时，玩家登录拿到的是按名字算出来的"离线 UUID"
（UUID v3，MD5("OfflinePlayer:" + 名字)）。但 `whitelist add 名字` 仍会去 Mojang 查这个名字，
名字被正版玩家注册过的话，白名单里写的就是正版 UUID，和登录时的离线 UUID 对不上，
结果是"You are not white-listed on this server!"（2026-10-22 Zan421 和 Blockmate 都中了）。

用法：
    python3 offline-uuid.py Zan421 Blockmate          # 只打印 UUID
    python3 offline-uuid.py --write ~/mcserver/whitelist.json Zan421 Blockmate
        # 直接把 whitelist.json 写成这些名字的离线 UUID（原文件备份为 whitelist.json.bak），
        # 然后在服务器上 RCON 发 `whitelist reload` 生效
"""
import hashlib
import json
import os
import shutil
import sys
import uuid


def offline_uuid(name):
    digest = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    digest[6] = (digest[6] & 0x0F) | 0x30  # version 3
    digest[8] = (digest[8] & 0x3F) | 0x80  # RFC 4122 variant
    return str(uuid.UUID(bytes=bytes(digest)))


def main(argv):
    args = argv[1:]
    target = None
    if args[:1] == ["--write"]:
        if len(args) < 3:
            print(__doc__)
            return 2
        target, args = args[1], args[2:]
    if not args:
        print(__doc__)
        return 2
    entries = [{"uuid": offline_uuid(n), "name": n} for n in args]
    if target is None:
        for e in entries:
            print(e["uuid"], e["name"])
        return 0
    target = os.path.expanduser(target)
    if os.path.exists(target):
        shutil.copy2(target, target + ".bak")
    with open(target, "w", encoding="utf-8") as f:
        json.dump(entries, f, indent=2)
        f.write("\n")
    print(f"已写入 {target}（{len(entries)} 个名字），记得 RCON 发 whitelist reload")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
