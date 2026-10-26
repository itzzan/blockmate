# 常见任务怎么拆（给脑子看的例子）

这是知识层的文件（guide-2 的三层之一），后台每次问模型时原样附在系统提示里，改它不用重启。
写法：一行一个任务；步骤用 → 连接；每步是 命令{参数}；没有参数就只写命令名。
命令和参数只能用 handbook 第 7.1 节列出的那些。能用一个动作做完的事不要拆成计划。

## 例 1：去弄 20 根木头回来
mine{block: minecraft:oak_log, count: 20} → deliver{item: minecraft:oak_log, count: 20}
说明：deliver 会自己走到主人身边再交，不用先 come_here。

## 例 2：做一把石镐给我（手里什么都没有）
mine{block: minecraft:oak_log, count: 3} → craft{item: minecraft:crafting_table} → place{block: minecraft:crafting_table} → mine{block: minecraft:cobblestone, count: 3} → craft{item: minecraft:stone_pickaxe} → pickup_block{block: minecraft:crafting_table} → deliver{item: minecraft:stone_pickaxe, count: 1}
说明：craft 会顺手把需要的木板和木棍合成出来，不用单独写；放了工作台用完要收回。

## 例 3：回家睡觉
go_home → sleep
说明：没记过家就先问主人家在哪，或者让主人带你到家再说"记住这里是家"（remember_place{name: home}）。

## 什么时候不要用计划
"跟我来""停""过来""等在这""把木头捡起来""木头给我"——一个动作就能做完，直接调对应的工具。
