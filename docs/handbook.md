# Blockmate · 协议与数据细则（v1）

本文补齐《小白版从 0 到 1 实施手册》（`docs/guide.md`）第 5、6、7 步任务单里引用的"设计文档"内容：消息格式、状态机、数据库表、配置文件。手册是规范，本文是细则；两者冲突时以手册为准，并在本文"待确认"一节记下。标注【手册】的内容是手册原文规定，标注【细则】的是本文补充的决定。

## 0. 名词与角色

| 名字 | 是什么 | 跑在哪 |
| --- | --- | --- |
| hub | 后台（server/），Hub / Brain / Gateway / Memory 四部分 | Mac mini |
| mc | 伙伴游戏客户端里的控制模组（mod/），或开发期的假执行器（mock-executor/） | MacBook |
| ui | 聊天网页（ui/） | 浏览器 |
| keeper | 开关游戏客户端的守护进程（keeper/） | MacBook |
| 主人 | 你，游戏里玩家名 Zan421 | Windows |
| 伙伴 | 游戏里玩家名 Blockmate | — |

## 1. 端点与鉴权

【手册】HTTP 端口来自 `config.yaml`（默认 8787）；提供静态网页和三个 WebSocket 端点 `/ws/ui`、`/ws/mc`、`/ws/keeper`；所有连接必须带 token（查询参数或首条消息），不对就断开。

【细则】

- 静态网页在 `/`，所有 ui/ 的构建产物由 hub 直接提供。
- token 校验顺序：先看查询参数 `?token=…`；没有就等首条消息，首条必须是 `hello` 且 `body.token` 正确；3 秒内没收到合法 hello 或 token 不对，hub 以关闭码 4401 断开。
- 连接数：`/ws/mc` 和 `/ws/keeper` 同一时刻各只保留一个连接，新连接到来时旧连接被 hub 以关闭码 4409 断开；`/ws/ui` 允许多个（手机和电脑同时开）。
- hub 每 30 秒发 WebSocket ping，连续 2 次没有 pong 视为断线。

## 2. 信封

【手册】信封格式 `v / type / id / ts / from / to / body`。

【细则】每条消息是一个 JSON 对象：

```json
{"v": 1, "type": "command", "id": "7f1c2e0a-5b7d-4c9e-9a1b-2d3e4f5a6b7c", "ts": 1760950000000,
 "from": "hub", "to": "mc", "body": {"name": "follow", "args": {}}}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| v | 整数 | 协议版本，当前固定 1。收到不认识的 v 记一条 WARN 并忽略该消息 |
| type | 字符串 | 见第 3 节各通道的类型表 |
| id | 字符串 | UUID v4，发送方生成，每条消息唯一。回复类消息在 body 里引用原消息 id |
| ts | 整数 | 发送方的毫秒时间戳 |
| from / to | 字符串 | `hub`、`mc`、`ui`、`keeper` 之一 |
| body | 对象 | 各类型自己的内容，可以为空对象 |

不认识的字段一律忽略；不认识的 type 记 WARN 并忽略。

## 3. 三个通道的消息类型

### 3.1 `/ws/mc`：hub 与模组

【手册】类型 `hello / snapshot / event / command / ack / progress / result`。

| 类型 | 方向 | 含义 |
| --- | --- | --- |
| hello | mc → hub，hub → mc | 握手 |
| snapshot | mc → hub | 每 20 tick（1 秒）一条，"我现在看到什么" |
| event | mc → hub | 有事发生时立刻发 |
| command | hub → mc | 一条动作命令 |
| ack | mc → hub | 收到了某条 command |
| progress | mc → hub | 某条 command 做到哪了 |
| result | mc → hub | 某条 command 做完了（成功 / 失败 / 被取消） |

### 3.2 `/ws/ui`：hub 与网页

【细则】手册只规定了网页要有什么功能，消息类型由本文定：

| 类型 | 方向 | 含义 |
| --- | --- | --- |
| hello | ui → hub，hub → ui | 握手；hub 的回复里带当前 status 和最近 N 条历史 |
| chat | ui → hub | 我打的一句话 |
| reply | hub → ui | 伙伴的回复，流式分片 |
| status | hub → ui | 状态条内容，变化时推送，另外每 5 秒推一次 |
| stop | ui → hub | 红色"停"按钮。hub 不经过模型，直接给 mc 发 `stop` 命令 |
| preferences | hub → ui | 待确认偏好列表（有变化就推） |
| preference_action | ui → hub | 对某条偏好点了"确认"或"忽略" |
| keeper_control | ui → hub | "叫它上线 / 让它下线"（第 7 步） |
| notice | hub → ui | 固定的中文提示（模型超时、伙伴不在游戏里等） |

### 3.3 `/ws/keeper`：hub 与守护进程

【细则】

| 类型 | 方向 | 含义 |
| --- | --- | --- |
| hello | keeper → hub，hub → keeper | 握手；hub 回复里带当前期望状态 |
| keeper_cmd | hub → keeper | `start` 或 `stop` |
| keeper_status | keeper → hub | 每 10 秒一条：进程在不在、最近一次启动时间、重启次数、是否放弃 |

## 4. 握手 hello

【细则】

mc → hub：

```json
{"v":1,"type":"hello","id":"…","ts":…,"from":"mc","to":"hub",
 "body":{"token":"<token>","role":"mc","player":"Blockmate","mod_version":"0.1.0","protocol_v":1,"owner":"Zan421"}}
```

ui → hub：`{"token":"<token>","role":"ui","client":"web"}`

keeper → hub：`{"token":"<token>","role":"keeper","version":"0.1.0"}`

hub 的回复（三个通道相同骨架）：

```json
{"body":{"ok":true,"server_time":1760950000000,"protocol_v":1}}
```

对 ui，回复的 body 还带 `status`（同第 8 节）和 `history`（最近 N 条 messages，N 来自 config）。对 keeper，还带 `desired`（`running` / `stopped`）。`ok:false` 之后 hub 立刻以 4401 关闭。

mock-executor 的 hello 与真模组相同，只是 `mod_version` 写 `mock`。

## 5. snapshot：模组每秒报告什么

【手册】字段：坐标、维度、生命、饥饿、背包摘要（物品 id → 数量）、主人（在视野内则给距离和坐标，否则 unknown）、当前任务状态；允许字段为 unknown（例如刚重连还没看到主人）。

【细则】"unknown" 的表示：数值和对象字段用 `null`；主人用 `visible:false`。

```json
{"v":1,"type":"snapshot","id":"…","ts":…,"from":"mc","to":"hub","body":{
  "in_world": true,
  "pos": {"x": 120.5, "y": 64.0, "z": -33.2},
  "dim": "minecraft:overworld",
  "health": 20.0,
  "food": 18,
  "inventory": {"minecraft:oak_log": 8, "minecraft:bread": 3},
  "owner": {"visible": true, "distance": 4.2, "pos": {"x": 124.0, "y": 64.0, "z": -31.0}},
  "task": {"command_id": "…", "name": "follow", "state": "running"}
}}
```

| 字段 | 说明 |
| --- | --- |
| in_world | 是否在服务器的世界里。false 时其余字段可以全是 null |
| pos / dim | 伙伴自己的坐标和维度 id |
| health | 0–20 的浮点；food 0–20 的整数 |
| inventory | 物品 id → 数量，只列非零项；空背包是 `{}` |
| owner | `visible:false` 表示没看到主人（不等于主人不在）；visible:true 时必有 distance 和 pos |
| task | 当前任务槽；空闲时为 `null` |

hub 把 snapshot 摘要成一段中文给模型看（第 9 节），不把 JSON 原样塞进提示词。

## 6. event：有事发生时报告什么

【手册】转发主人说的游戏内聊天（其他玩家的丢弃）；死亡；重生；被踢出 / 断线；背包里某种物品数量变化。

【细则】`body.kind` 取值与各自字段：

| kind | 字段 | 说明 |
| --- | --- | --- |
| chat | from, text | 只有 from 等于配置的主人名时才发。hub 存进 messages（role=owner_ingame），日志只记长度。游戏内聊天不是命令渠道，hub 不把它交给 Brain 决策，只作为记忆和上下文 |
| death | pos, dim | 第一次 isDeadOrDying 为真 |
| respawn | pos, dim | 重生完成 |
| kicked | reason | 被服务器踢出，reason 是服务器给的文本 |
| disconnected | reason | 连接断开 |
| joined | — | 进入世界完成（重连 / 重生后，先发一条完整 snapshot，再发 joined） |
| inventory_change | item, delta, count | 某物品数量变化；捡到为正，丢出为负 |

## 7. command / ack / progress / result

### 7.1 command

【手册】命令 `follow / come_here / wait_here / stop / pick_up / deliver / report_status / cancel / rejoin`。

【细则】`body = {"name": …, "args": {…}}`：

| name | args | 说明 |
| --- | --- | --- |
| follow | — | 跟随主人，没有"完成"，只会被替换或停止 |
| come_here | — | 走到主人当前位置 2 格内 |
| wait_here | — | 停下留在原地 |
| stop | — | 立刻停。网页"停"按钮和模型的 stop 工具都走这条。模组先给手头任务发 result cancelled，再回 stop 的 ack 和 result |
| pick_up | item（物品 id），radius（格，默认 8） | 捡半径内的该物品 |
| deliver | item，count，to（玩家名，默认主人） | 走到主人 3 格内丢出物品 |
| report_status | — | 让模组立刻发一条完整 snapshot |
| cancel | command_id | 取消指定任务。模组回 ack，再给被取消的任务发 result cancelled，最后给 cancel 自己回 result ok（手册 5.4：stop 和 cancel 立即回 result）。hub 对 stop / cancel 自己的 result 只记 events，不进任务槽 |
| rejoin | — | 重新连接服务器 |

### 7.2 ack

```json
{"body":{"command_id":"…","accepted":true}}
```

`accepted:false` 时带 `reason`（原因码，通常是 `rejected`，比如不在世界里却收到 deliver）。【手册】ack 超过 3 秒没来，hub 判 `executor_unresponsive`。

### 7.3 progress

```json
{"body":{"command_id":"…","note":"distance","distance":12.4,"pos":{"x":…,"y":…,"z":…}}}
```

【手册】跟随时距离每变化 8 格发一条。【细则】note 是短的英文标识（distance / pathing / arrived_near / throwing），pos 可选。hub 把 progress 写进 events，不给模型看。

### 7.4 result

```json
{"body":{"command_id":"…","status":"ok","reason":null,"detail":{}}}
```

| status | 含义 |
| --- | --- |
| ok | 做成了 |
| failed | 没做成，reason 必填 |
| cancelled | 被新任务替换或被 cancel / stop 取消，reason 可为 null |

detail 按命令不同：

- deliver【手册三态】：`{"delivery": "confirmed" | "thrown_unconfirmed" | "not_thrown", "count": 8}`。`confirmed` 只能在收到 ClientboundTakeItemEntityPacket 且捡起者是主人时报；`thrown_unconfirmed` 是已丢出但 10 秒内没收到确认；`not_thrown` 配合 reason 说明为什么。结果未知时模组绝不自动再丢一次。
- stop：`{"stationary": true | false, "attempts": 1}`。stationary=false 表示三次 cancelEverything 之后仍未静止。
- pick_up：`{"picked": 8}`。
- rejoin：`{"attempt": 2}`。

### 7.5 原因码

【手册】`target_lost / target_other_dim / target_too_far / not_picked / path_failed / stuck / item_not_found / inventory_full / timeout / died / disconnected / executor_unresponsive / rejected`。

| 原因码 | 谁产生 | 什么时候 |
| --- | --- | --- |
| target_lost | mc | 跟随中主人超过 10 秒不在视野内 |
| target_other_dim | mc | 主人换了维度 |
| target_too_far | mc | 主人可见但超出寻路上限（【细则】默认 200 格） |
| not_picked | mc | pick_up 走到了但 15 秒内背包没变化 |
| path_failed | mc | Baritone 报无路可走 |
| stuck | mc | 寻路中连续 5 秒位置没变（【手册】不重试） |
| item_not_found | mc | 半径内没有该物品 |
| inventory_full | mc | 背包满 |
| timeout | hub | 任务超过 config 里的时长 |
| died | mc | 执行中死亡 |
| disconnected | mc / hub | 执行中掉线；或 hub 在 mc 断线时替它结束任务 |
| executor_unresponsive | hub | 3 秒没 ack |
| rejected | mc | 命令在当前状态下不能做（不在世界里、参数不合法） |

## 8. 任务状态机（hub 侧，单任务槽）

【手册】同一时刻只有一个进行中的任务；新任务替换旧任务时，旧任务要收到自己的 cancelled 结果。

【细则】

```
idle ──command 发出──▶ pending ──ack accepted──▶ running ──result──▶ idle
                         │                         │
                         │ 3 秒无 ack               │ 超时 / mc 断线
                         ▼                         ▼
                   executor_unresponsive        timeout / disconnected
                   (hub 自己合成 result failed)  (hub 自己合成 result failed)
```

- 新 command 到来而槽位非空：hub 先给 mc 发新 command；模组自己负责先给旧任务回 `result cancelled` 再回新任务的 ack（【手册】6.4）。hub 收到旧任务的 cancelled 就把它从槽位清掉；如果旧任务的 cancelled 迟于新任务的 ack 到达，hub 也要接受（按 command_id 匹配，不按顺序）。
- `stop` 和 `cancel` 不占槽位，是对槽位的操作。
- hub 自己合成的 result（timeout / executor_unresponsive / disconnected）之后，如果 mc 再发来同一 command_id 的 result，记 WARN 并丢弃。
- 任务超时（【细则】默认值，config 可改）：follow 不超时；come_here 90 秒；pick_up 45 秒；deliver 90 秒；stop 5 秒；rejoin 60 秒；wait_here / report_status 5 秒。
- 伙伴离线（/ws/mc 没有连接或 in_world=false）时收到动作请求：【手册】直接用人话回"我现在不在游戏里"，不调模型。

## 9. Brain：每次问模型带什么

【手册】人设来自 persona.md；工具 `follow / come_here / wait_here / stop / pick_up(item, radius) / deliver(item, count, to) / report_status / remember_preference`；每次请求带人设 + 最近 N 条对话 + 最新 snapshot 摘要 + 已确认偏好；结果到达后的"说一句话"请求不带动作工具；只执行第一个动作工具，其余用人话说"一次只做一件"。

【细则】

提示词顺序：system = persona.md 全文 + 「已确认的偏好」列表 + 「最近 3 条日记」（episodes）+ 「现在的状态」（snapshot 摘要）；然后是最近 N 条 messages（role 映射：owner→user，companion→assistant，owner_ingame→user 并加前缀"（游戏里说）"）；最后是这次的输入。

snapshot 摘要是一句中文，例如：「你在主世界 (120, 64, -33)，生命 20/20，饥饿 18/20，背包：橡木原木 8、面包 3。主人在 4 格外。当前任务：跟随中。」看不到主人时写「没看到主人」，不在世界里写「你现在不在游戏里」。物品 id 到中文名的映射放 `server/src/main/resources/items_zh.json`，没有映射的直接显示 id。

工具定义（给模型的 JSON schema 要和这里一致）：

| 工具 | 参数 | 说明给模型 |
| --- | --- | --- |
| follow | — | 开始跟随玩家，直到玩家让你停 |
| come_here | — | 走到玩家身边 |
| wait_here | — | 停下，在原地等 |
| stop | — | 立刻停下正在做的事 |
| pick_up | item（英文物品 id，如 minecraft:oak_log），radius（可选，默认 8） | 捡起附近地上的某种物品 |
| deliver | item，count，to（可选，默认主人） | 把背包里的物品交给玩家 |
| report_status | — | 看看自己现在的状态 |
| remember_preference | text | 玩家表达了一个长期偏好或约定时记下来，需要玩家确认才生效 |

决策请求 = 带全部工具；"说一句话"请求（result 到达后、伙伴离线时的提示等）= 只带 remember_preference 或不带工具。模型一次返回多个工具调用时，全部按顺序存进 messages.tool_calls，只执行第一个动作工具。

## 10. Gateway：和 Ollama 怎么说话

【手册】ModelClient 接口 + OllamaClient 实现；base_url 来自 config；优先 OpenAI 兼容的 `/v1/chat/completions` 带 tools；关不掉思考就改用原生 `/api/chat` 带 `think:false`；超时默认 60 秒；连不上时网页收到固定中文提示，不崩。

【细则】

- 先实现原生 `/api/chat`（stream:true，think:false，tools），因为手册 4.5 已经验证了这条路；OpenAI 兼容路径作为第二个实现，config 里 `ollama.api: native | openai` 切换，默认 native。
- 流式：文本分片按到达顺序转成 ui 的 `reply` 分片；tool_calls 在流结束时一次性取出。
- 超时分两段：首字 30 秒、总时长 60 秒（config 可改）。超时或连接失败，hub 给 ui 发 `notice`：「我脑子有点转不动，等一下再和我说」，并记 WARN。
- 每次请求记录到 events（kind=model_call）：模型名、输入 token 数、输出 token 数、耗时毫秒、是否有工具调用；不记内容。

## 11. Memory：SQLite 表

【手册】表 `messages / events / preferences(pending/active) / episodes`，FTS5 全文索引；每天结束或伙伴下线时把当天 events 概括成一条 episode；下次对话开始时带最近 3 条；remember_preference 只写 pending，网页确认才 active；删除一条消息连带删掉派生条目；每天凌晨 VACUUM INTO 备份到 data/backup/，保留 14 份。

【细则】文件 `data/companion.db`，WAL 模式。

```sql
CREATE TABLE messages (
  id          TEXT PRIMARY KEY,          -- UUID
  ts          INTEGER NOT NULL,          -- 毫秒
  role        TEXT NOT NULL,             -- owner | companion | owner_ingame | system
  text        TEXT NOT NULL,
  reply_to    TEXT,                      -- companion 回复的是哪条 owner 消息
  tool_calls  TEXT,                      -- JSON 数组，模型这次返回的全部工具调用（含 call id）
  deleted     INTEGER NOT NULL DEFAULT 0
);
CREATE VIRTUAL TABLE messages_fts USING fts5(text, content='messages', content_rowid='rowid');

CREATE TABLE events (
  id          TEXT PRIMARY KEY,
  ts          INTEGER NOT NULL,
  kind        TEXT NOT NULL,   -- command | ack | progress | result | snapshot_change | chat | death | respawn | kicked
                               -- | disconnected | joined | inventory_change | model_call | executor_online | executor_offline
  command_id  TEXT,            -- 和某条命令相关时填
  source_message_id TEXT,      -- 由哪条消息触发（例如 command 由某条 owner 消息产生）
  payload     TEXT NOT NULL    -- JSON；chat 事件的 payload 只存长度和 from，不存原文（原文在 messages）
);
CREATE INDEX events_ts ON events(ts);

CREATE TABLE preferences (
  id          TEXT PRIMARY KEY,
  ts          INTEGER NOT NULL,
  text        TEXT NOT NULL,
  status      TEXT NOT NULL,   -- pending | active | ignored
  source_message_id TEXT,
  decided_ts  INTEGER
);

CREATE TABLE episodes (
  id          TEXT PRIMARY KEY,
  day         TEXT NOT NULL,   -- YYYY-MM-DD（本地日期）
  summary     TEXT NOT NULL,   -- 模型写的一段中文日记
  from_ts     INTEGER NOT NULL,
  to_ts       INTEGER NOT NULL,
  created_ts  INTEGER NOT NULL
);
CREATE VIRTUAL TABLE episodes_fts USING fts5(summary, content='episodes', content_rowid='rowid');
```

- 写 episode 的时机：本地时间每天 23:55，或伙伴从在线变离线且当天还没写过；输入是当天的 events（去掉 progress 和 model_call）和 messages 的摘要，调一次模型，不带工具。同一天第二次触发就覆盖当天那条。
- 删除消息：把 messages.deleted 置 1 并清空 text，同时删除 `source_message_id` 等于它的 events 和 preferences；当天的 episode 标记需要重算（下次触发时重写）。
- 备份：每天 03:30 `VACUUM INTO 'data/backup/companion-YYYY-MM-DD.db'`，然后只保留最新 14 个文件。
- 最近 N 条对话：按 ts 倒序取 N 条未删除的 messages（N 来自 config.history_n，默认 20）。

## 12. config.yaml 与 persona.md

【手册】密码和令牌只从 config.yaml 读，不进 git；给一份 config.example.yaml，每个字段有中文注释；persona.md 由用户自己写，一页以内。

【细则】`config.example.yaml` 的字段（真实文件 `config.yaml` 在 .gitignore 里）：

```yaml
port: 8787                      # HTTP / WebSocket 端口
token: "CHANGE_ME"              # 所有连接的口令，自己生成一串长随机字符
owner_name: "Zan421"            # 主人的游戏玩家名
companion_name: "Blockmate"     # 伙伴的游戏玩家名
persona_path: "persona.md"      # 人设文件，相对 config.yaml 所在目录
data_dir: "data"                # SQLite 和备份放哪
history_n: 20                   # 每次给模型看最近几条对话
ollama:
  base_url: "http://127.0.0.1:11434"   # Mac mini 上部署时改成 http://<mbp的100.x>:11434
  model: "companion-local"
  api: "native"                 # native = /api/chat；openai = /v1/chat/completions
  first_token_timeout_s: 30
  total_timeout_s: 60
timeouts_s:
  ack: 3
  come_here: 90
  pick_up: 45
  deliver: 90
  stop: 5
  rejoin: 60
episode_time: "23:55"           # 每天写日记的时间
backup_time: "03:30"            # 每天备份的时间
log_level: "INFO"
```

persona.md 由 Brain 每次请求前检查修改时间，变了就重新读，不用重启。

## 13. keeper 通道细则

【手册】收到 start 执行 Prism 的启动命令；"游戏是否在跑"靠每 10 秒 `pgrep -f companion-vanilla-1.20.1`；stop 发 SIGTERM，15 秒没退就 SIGKILL；期望"在跑"而进程不在就重启，10 分钟内最多 3 次；每 10 秒报状态；后台连不上时保持最后的期望状态。

【细则】

keeper_cmd：`{"action": "start" | "stop"}`。

keeper_status：

```json
{"body":{"running":true,"pid":12345,"desired":"running","last_start_ts":1760950000000,
         "restarts_10min":1,"gave_up":false,"keeper_version":"0.1.0"}}
```

`keeper.yaml`（MacBook，gitignore）：`hub_url`、`token`、`launch_cmd`（完整命令行数组）、`process_pattern`（给 pgrep -f 用，默认 `companion-vanilla-1.20.1`）、`check_interval_s: 10`、`max_restarts_per_10min: 3`。

待验证：Prism 启动的游戏进程命令行里是否真的含实例名（第 3 步时游戏进程没抓到，3.6 做的时候要确认；不含就改 process_pattern 为实例目录路径）。

## 14. ui 通道细则

- `chat`：`{"text": "跟我来"}`。hub 先存 messages(role=owner)，再交 Brain。
- `reply`：`{"reply_id": "…", "in_reply_to": "<chat 的 id>", "chunk": "来", "done": false}`；最后一片 `done:true` 且带 `message_id`（存库后的 companion 消息 id）。
- `status`：
  ```json
  {"companion": {"online": true, "in_world": true, "health": 20, "food": 18, "dim": "minecraft:overworld", "pos": {…}},
   "task": {"name": "follow", "state": "running"} ,
   "keeper": {"connected": true, "running": true, "desired": "running", "gave_up": false},
   "model": {"ok": true}}
  ```
  伙伴离线时 `companion.online:false`，其余为 null。
- `stop`：空 body。hub 直接给 mc 发 stop 命令，并给 ui 推一条 status。
- `preferences`：`{"items": [{"id": "…", "text": "…", "ts": …}]}`。
- `preference_action`：`{"id": "…", "action": "confirm" | "ignore"}`。
- `keeper_control`：`{"action": "start" | "stop"}`。
- `notice`：`{"text": "我现在不在游戏里", "level": "info" | "warn"}`。

网页的 token 存在 localStorage（键 `companion_token`），断线按 1/2/4/…/15 秒退避重连，状态条显示"重连中"。

## 15. 模组侧（第 6 步）与本文的对应

手册 6.2–6.4 的规定照旧，这里只补充与协议相关的几点：

- `config/companion-client.toml`：`hub_url = "ws://mini:8787/ws/mc"`、`token`、`owner_name = "Zan421"`、`player_name = "Blockmate"`、`reconnect_max_s = 30`。
- 与 hub 断开的瞬间：先 `cancelEverything()`，再把槽位里的任务记为 `disconnected`（重连后不补发 result，hub 那边已经自己合成了）。
- 重连 / 重生后的顺序：完整 snapshot → `joined` 事件 → 才接受新 command；在此之前收到的 command 回 `ack accepted:false, reason:rejected`。
- 所有 Baritone 设置在进入世界时写死：allowBreak / allowPlace / allowParkour / allowInventory = false，followRadius = 3，并把这几项的值打一行 `[companion] baritone settings …` 到 latest.log。

## 16. 日志规范

【手册】INFO 级别不得包含聊天内容原文，只记事件和长度。

【细则】每条日志一行，格式 `时间 级别 模块 事件 key=value …`。例如 `INFO hub chat_in len=5 msg_id=…`、`INFO hub command name=follow cmd_id=…`、`INFO gateway model_call ms=1830 in_tok=912 out_tok=14 tool=follow`。DEBUG 级别可以含原文，默认关。日志文件 `logs/server.log`，按天切分，保留 14 天。

## 17. 待确认（本文超出手册的决定）

1. 第 3 节 ui / keeper 通道的消息类型是本文定的，手册只写了功能。
2. 第 5 节用 `null` 和 `visible:false` 表示 unknown。
3. 第 8 节的各命令超时默认值。
4. 第 10 节先实现 Ollama 原生 `/api/chat`，OpenAI 兼容路径作为可切换的第二实现。
5. 第 11 节的字段和索引；命令生命周期记在 events 里，不单独建 tasks 表。
6. 第 12 节 config.yaml 的字段名。
7. 第 13 节 keeper 的 `process_pattern` 可配置，以防实例名不在进程命令行里。
8. 物品中文名映射文件 `items_zh.json` 由任务单 3 一并生成，只覆盖常见物品。

这些你觉得不对的，直接说哪一条改成什么。
