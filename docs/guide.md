# 自托管 AI 伙伴 · 小白版从 0 到 1 实施手册

Oct 19, 2026 · @Zan

这本手册把三台空电脑一步步带到这个时刻：你在 Windows 的网页上和伙伴聊天，它以一个独立玩家的身份进到 Mac mini 上的原版 1.20.1 服务器里，听你的话跟着走、停下、过来、捡东西、把东西交给你。每一步都先讲它是什么、为什么要做，再给命令，命令后面解释每一行；每步末尾有“怎么确认做对了”和“要记下什么”。只做原版生存，不做整合包；大脑用 MacBook 上的本地模型，不花钱订阅。

## 读之前：这套东西是什么

**一句话说清**。你要做的是一个“会玩 Minecraft 的聊天伙伴”。它有三个部件：一个**脑子**（大模型，负责听懂你的话、决定说什么、决定做什么）；一个**身体**（一个真正的 Minecraft 游戏客户端，里面装了一个我们自己写的模组，负责“看周围”和“按命令走”）；一个**中枢**（我们自己写的后台程序，把网页、脑子、身体三者连起来，并把聊过的话、做过的事存成记忆）。你在网页上打一句“跟我来”，中枢把这句话连同它当前看到的世界交给脑子，脑子说“这是跟随命令”，中枢把命令发给身体，身体在游戏里真的走起来，走到了再回一句“来了”。

**三台电脑各干什么**。下图是做完之后的样子。记住一个原则：Mac mini 是“永远开着的那台”，世界和记忆都在它上面，所以它上面的东西都要“开机自启、挂了自己起”；MacBook 是“玩的时候才需要开的那台”，伙伴的身体和脑子都在它上面，因为这两样最吃内存，而 MacBook 有 48 GB；Windows 是你自己的机器，上面什么都不用装，只多开一个浏览器标签页。

&#91;embedded content: 三台电脑的分工 · 谁连谁\]

图里的数字是“端口”：一台电脑上可以同时跑很多服务，端口号就是门牌号，连进来的人靠它找到正确的那个服务。25565 是 Minecraft 服务器的默认端口，8787 是我们给后台定的，11434 是 Ollama 的默认端口，443 是 HTTPS 网页的标准端口。后面设访问规则、改配置文件时会反复用到这几个数。

**这次做到哪里为止**。文字聊天，加五个游戏动作：跟随、停下、过来、捡起、交付。在原版生存服务器上跑通。脑子用 MacBook 上的本地模型，不接任何付费服务。语音、形象、整合包、让朋友进服、ChatGPT 订阅路径——都不在这次里。先把底层搭稳，这些以后都是往上加，不用推倒重来。

**做完的标准（最后一步会逐条验）。**

| 编号 | 你能做到的事 | 怎么算通过 |
| --- | --- | --- |
| A | 和它在网页上聊 20 轮 | 人设稳定，不自称模型，能接上上一句 |
| B | 它以独立玩家身份在你的服务器里 | 玩家列表里有它，你能看见它 |
| C | “跟我来”“停”“过来”“把木头捡起来”“木头给我” | 10 次指令至少 9 次正确执行；“停”1 秒内生效 |
| D | 跟随时连续聊 5 轮 | 跟随不中断，闲聊不会被当成命令 |
| E | 把它关进栅栏里 | 5 秒内说“卡住了”，不挖墙、不无限重试 |
| F | 第二天打开网页 | 它能说出昨天一起做了什么 |
| G | 重启任意一台电脑 | 不用手动点任何东西，几分钟内自己恢复 |

**这本手册怎么读**。八步，按顺序做，不要跳。每一步固定四块：“这一步在做什么”讲概念和理由；“动手”是编号的操作，每段命令下面紧跟一条“这几行在做什么”；“确认做对了”是你能亲眼看到的结果，没看到就不要进下一步；“记到小本子”是这一步产生、后面要用的信息。需要写代码的地方（第 5、6、7 步）你不写代码：我给你一张张“任务单”，你原样交给 AI 编程助手，然后按任务单里的方法验收。

命令都在“终端”里敲。以 `$` 开头的行是你要输入的，`$` 本身不要输；不以 `$` 开头的行是屏幕上应该出现的输出。尖括号里的内容（比如 `<你>`、`<伙伴的玩家名>`）是占位符，要换成你自己的值，尖括号也不要。命令在哪台电脑上敲，每步开头会说；没说就是在这一步的那台电脑上。

## 第 0 步 · 准备：凑齐东西，学会三个基本动作

**这一步在做什么**。不装任何东西，只把账号、设备和三个你后面要反复用的基本动作准备好。很多人卡在中途不是因为难，而是因为做到第 3 步发现少一个账号、或者不知道“终端”是什么。这一步半小时做完。

### 0.1 要凑齐的东西

| 类别 | 具体 | 为什么需要 |
| --- | --- | --- |
| 账号 | 你自己的 Microsoft 账号（拥有 Minecraft Java 版） | 伙伴用“离线账号”，不用买第二份游戏；但启动器要求先登录一个正版账号，才允许添加离线账号 |
| 账号 | 一个 Tailscale 账号（用 Google / Microsoft / GitHub 登录即可） | 第 1 步组网；免费档够用 |
| 账号 | 一个 GitHub 账号（可选） | 放代码；不想用也可以只存在 MacBook 本地 |
| 设备 | Mac mini 接电、接网（最好网线），初始设置时接一次显示器和键鼠 | 第 1 步做完以后就能从 MacBook 远程操作它，显示器可以拔掉 |
| 设备 | MacBook 接电源 | 共玩时要开着；开发也在它上面做 |
| 设备 | Windows 主机已经能正常玩 Minecraft 1.20.1 | 用你现在的启动器就行，不用改 |
| 软件 | 一个 AI 编程助手（Claude Code / Cursor / Codex 任选） | 第 5、6、7 步的代码由它写，你负责验收 |
| 时间 | 第 1–4 步合计 1–2 天；第 5 步 1–2 周；第 6 步 2–3 周；第 7 步 2–3 天 | 前提是每天有整块时间；第 6 步最不确定 |

### 0.2 基本动作一：打开终端，敲一条命令

“终端”是 Mac 自带的一个应用，你在里面用打字的方式指挥电脑，而不是点鼠标。这本手册里几乎所有操作都在它里做，因为打字的命令可以原样复制，鼠标操作没法复制。

1. 按 Command + 空格，输入“终端”或 Terminal，回车。一个黑色或白色的窗口打开，最后一行是闪烁的光标，这就是“等你输入”。
2. 输入下面这行，回车：

```bash
$ whoami
```

屏幕会打印你的 macOS 用户名。这个名字后面会用到很多次（手册里写作 `<你>`），记下来。

几个会反复遇到的事：

- `~` 表示“我的个人文件夹”，也就是 `/Users/<你>`。`~/mcserver` 就是个人文件夹下叫 mcserver 的文件夹。
- 以 `sudo` 开头的命令是“用管理员身份执行”，会要你输入开机密码。输密码时屏幕上什么都不显示，连星号都没有，这是正常的，敲完回车即可。
- 一条命令跑起来后屏幕一直滚动、光标不回来，说明它还在跑。要强行停掉它，按 Control + C。
- 命令打错了不会弄坏电脑，只会报错。把报错原文记下来。
- 从手册复制命令时，一次复制一行（一个 `$` 开头的段落），粘到终端里回车，等它跑完再复制下一行。

### 0.3 基本动作二：装 Homebrew（两台 Mac 都要装）

Homebrew 是 Mac 上的“软件包管理器”：以后装 Java、Tailscale、Ollama 这些东西，都是一句 `brew install xxx` 解决，不用到处找安装包。它自己不自带，要先装。在 Mac mini 和 MacBook 上各做一遍：

```bash
$ /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
```

这一行在做什么：`curl` 是“从网上下载”，它把 Homebrew 官方的安装脚本下下来；外面的 `/bin/bash -c` 把下下来的脚本直接跑起来。它会先列出要装的东西，让你按回车确认，然后要开机密码。几分钟后结尾有一段 Next steps，里面有两行以 `echo` 和 `eval` 开头的命令，**把这两行原样复制到终端里跑一遍**——这是让终端以后能找到 `brew` 这个命令。然后关掉终端重新打开，敲：

```bash
$ brew --version
```

能打印出版本号就装好了。如果说 command not found，就是 Next steps 那两行没跑。在 Apple 芯片的 Mac 上 Homebrew 装在 `/opt/homebrew`，后面几处路径会用到这个位置。

### 0.4 基本动作三：用 nano 改一个文本文件

很多配置是纯文本文件，要在终端里改。nano 是自带的最简单的编辑器。练一次：

```bash
$ nano ~/test.txt
```

屏幕变成一个编辑界面。随便打几个字，然后：按 Control + O（字母 O，意思是写出），底部问你文件名，直接回车；再按 Control + X 退出。这就是“保存并退出”。后面凡是说“用 nano 打开某文件，粘进下面内容”，都是这三个动作：打开、粘贴（Command + V）、Control + O 回车 Control + X。练完把测试文件删掉：

```bash
$ rm ~/test.txt
```

`rm` 是删除，不进废纸篓，删了就没了，所以手册里尽量不用它。

### 0.5 一个贯穿全程的小本子

在 MacBook 上建一个文本文件，比如 `我的伙伴-记录.md`，用任何编辑器都行。每做完一步记三样：日期、这一步“确认做对了”的结果、遇到的报错原文。每步末尾的“记到小本子”就是记到这里。出问题时这是唯一能帮你回溯的东西，也是贴给 AI 助手排错时最有用的材料。

两条规矩：所有密码、密钥、随机字符串放进密码管理器（比如 Mac 自带的“密码”应用），小本子里只写“存在哪”；每一步的“确认做对了”没全部通过，不进下一步。

### 0.6 确认这一步做对了

1. 两台 Mac 上 `brew --version` 都能打印版本号。
2. 你知道自己在两台 Mac 上的用户名（`whoami`）。
3. 你用 nano 成功保存过一个文件。
4. 三个账号都能登录。

记到小本子：两台 Mac 的用户名；Tailscale 用哪个账号登录的。

## 第 1 步 · Tailscale：让三台电脑互相找到

**这一步在做什么**。三台电脑要互相说话：Windows 要连 Mac mini 的服务器和网页，MacBook 要连 Mac mini 的后台，Mac mini 要连 MacBook 的大模型。家里的路由器给每台电脑的地址会变，出了家更是连不到，而且直接把服务器暴露到公网很危险。Tailscale 解决的就是这件事：它给你建一个**私有网**（它叫 tailnet），里面每台电脑有一个永远不变的 `100.x.x.x` 地址和一个名字，不管它们在家里、公司还是手机热点下，都能用名字找到彼此；不在这个网里的电脑根本看不到它们。所以我们不开公网端口、不搞内网穿透，全靠它。

做完之后你能看到的：在 MacBook 上敲 `ssh mini` 就进到了 Mac mini 的终端；三台电脑在 Tailscale 的管理页上都亮着。

### 1.1 在三台电脑上安装并登录

1. **先 Windows**。打开 [tailscale.com/download](https://tailscale.com/download)，下载 Windows 版，安装。安装完右下角托盘多一个图标，点它 → Log in，浏览器里用你选定的账号登录。第一次登录会自动创建你的 tailnet。
2. **再两台 Mac**。在 Mac mini 和 MacBook 的终端里各敲：

```bash
$ brew install --cask tailscale-app
```

这一行在做什么：`brew install` 是装软件；`--cask` 表示装的是一个带图形界面的应用（会出现在“应用程序”文件夹里）；`tailscale-app` 是 Tailscale 的 Standalone 版本，官方推荐 Mac 用这个变体，因为它带命令行工具，我们后面要用命令行做 HTTPS。不要从 App Store 装，那个版本没有命令行。

3. 在“应用程序”里打开 Tailscale。系统会弹窗要求允许“系统扩展”或“VPN 配置”，全部允许（它要在系统里建一张虚拟网卡，没有这个权限就不工作）。然后点菜单栏图标 → Log in，用和 Windows 同一个账号。
4. **给三台机器起名字**。打开 [login.tailscale.com/admin/machines](https://login.tailscale.com/admin/machines)，这是你 tailnet 的管理页，三台机器都应该在列表里。每台右侧的“…”→ Edit machine name，分别改成 `win`、`mini`、`mbp`。以后所有命令里的 `mini`、`mbp` 就是这两个名字，所以要一字不差。
5. 同一页顶部切到 DNS 标签页：确认 MagicDNS 是打开的（新建的 tailnet 默认开；它的作用就是让 `mini` 这种名字能当地址用）；把 HTTPS Certificates 打开（第 5 步的网页要用）。页面上有一个 tailnet 名字，形如 `tailxxxx.ts.net`，记到小本子。

### 1.2 给两台 Mac 打标签，写访问规则

**先理解为什么要写规则**。在同一个 tailnet 里，默认所有机器之间什么都能连。现在只有你一个人，这没问题；但将来要邀请朋友进来玩，就必须能说“朋友只能到游戏端口，不能碰后台和大模型”。现在就把规则写好，以后只是加一行。规则需要给机器分类，分类的办法是“标签”：Mac mini 贴 `tag:mcserver`（服务器），MacBook 贴 `tag:workstation`（工作机）。

打开 [login.tailscale.com/admin/acls](https://login.tailscale.com/admin/acls)，这是策略文件的编辑页。把里面的内容**全部删掉**，换成下面这段（把三处 `you@example.com` 都换成你登录 Tailscale 的邮箱），点 Save：

```json
{
  "tagOwners": {
    "tag:mcserver":    ["you@example.com"],
    "tag:workstation": ["you@example.com"]
  },
  "grants": [
    {"src": ["autogroup:member", "tag:workstation"], "dst": ["tag:mcserver"], "ip": ["tcp:25565"]},
    {"src": ["you@example.com"], "dst": ["tag:mcserver"],    "ip": ["tcp:22", "tcp:443", "tcp:8787", "tcp:25575", "tcp:5900"]},
    {"src": ["you@example.com"], "dst": ["tag:workstation"], "ip": ["tcp:22", "tcp:5900"]},
    {"src": ["tag:workstation"], "dst": ["tag:mcserver"],    "ip": ["tcp:8787"]},
    {"src": ["tag:mcserver"],    "dst": ["tag:workstation"], "ip": ["tcp:11434"]}
  ]
}
```

这段在说什么（一行一条规则，`src` 是谁发起，`dst` 是连到谁，`ip` 是允许哪些端口）：

- `tagOwners`：谁有权把这两个标签贴到机器上。没有这段，下一步贴标签时会报错。
- 第 1 条：所有成员（现在就是你的 Windows，将来包括朋友）和 MacBook，都能连服务器的 25565——玩游戏。
- 第 2 条：只有你本人能连 Mac mini 的 22（SSH 远程终端）、443（聊天网页）、8787（后台本身）、25575（服务器管理口 RCON）、5900（屏幕共享）。
- 第 3 条：只有你本人能远程操作 MacBook。
- 第 4 条：MacBook（上面的模组和 keeper）能连后台的 8787。
- 第 5 条：Mac mini（上面的后台）能连 MacBook 的 11434——调大模型。

没写的就是不允许的。贴标签：回到 Machines 页，`mini` 右侧“…”→ Edit ACL tags → 勾 `tag:mcserver`；`mbp` 同样操作勾 `tag:workstation`。Windows 不贴标签，它就是“你本人的设备”。

做错会怎样：规则写少了一行，对应的连接会“连不上”但不报任何错，这是后面排错时最容易忘的一个地方。标签贴错机器，效果一样。

### 1.3 让两台 Mac 能被远程操作

在 Mac mini 和 MacBook 上各做一次：系统设置 → 通用 → 共享 → 打开“远程登录”和“屏幕共享”。“远程登录”就是 SSH：以后你在 MacBook 的终端里敲 `ssh <你>@mini`，屏幕上的终端就变成了 Mac mini 的终端，你敲的每一条命令都在 Mac mini 上执行，敲 `exit` 回到 MacBook。“屏幕共享”是看它的桌面：在 MacBook 上打开“屏幕共享”应用，输入 `mini`。有了这两样，Mac mini 的显示器就可以拔了。

### 1.4 确认这一步做对了

在 MacBook 的终端里。先做一个“别名”：Tailscale 的命令行工具藏在应用包里，路径很长，我们告诉终端“以后我敲 tailscale 就是指那个长路径”：

```bash
$ echo 'alias tailscale="/Applications/Tailscale.app/Contents/MacOS/Tailscale"' >> ~/.zshrc && source ~/.zshrc
$ tailscale status
$ tailscale ping mini
$ tailscale ping win
```

这几行在做什么：第 1 行把别名定义追加到 `~/.zshrc`（终端每次启动都会读的配置文件），`source` 让当前这个终端立刻生效；第 2 行列出 tailnet 里所有机器和状态；第 3、4 行是通过 Tailscale 去“敲门”。

要看到的结果：

- `tailscale status` 里三台机器都在。同一个局域网里的机器后面显示 `direct`（直连）。如果显示 `relay`，说明流量在绕道 Tailscale 的中继服务器，游戏会卡；先查路由器是不是关了 UPnP，或两台机器是不是连了不同的 Wi-Fi。
- `tailscale ping mini` 几次之后显示 `via <局域网地址>`，就是直连成功。
- `ssh <你>@mini` 能登进去（第一次会问是否信任这台机器，输 yes；然后要 Mac mini 的开机密码）。登进去后敲 `whoami` 确认是 Mac mini 的用户名，再 `exit`。

记到小本子：三台机器的 `100.x` 地址（在每台机器上敲 `tailscale ip -4`，或直接看管理页）；tailnet 名字。后面配置文件里的 `<mini的100.x>`、`<mbp的100.x>` 就是这两个数。

## 第 2 步 · Mac mini：开一台 24 小时在线的原版服务器

**这一步在做什么**。你平时玩的 Minecraft 是“客户端”：有画面，世界存在你自己电脑上。要让两个玩家（你和伙伴）进同一个世界，世界必须放在一个两边都能连到的地方，这就是“专用服务器”：一个没有画面、只负责“开着世界”的 Minecraft 程序。Mojang 官方免费提供它，它就是一个 Java 程序，所以要先装 Java。我们还要让它做到四件事：只在 Tailscale 网里可见；允许伙伴的“离线账号”进来；只让白名单里的名字进来；开机自己启动、挂了自己重启。

做完之后你能看到的：在 Windows 上打开 Minecraft，多人游戏里输入地址 `mini`，进入一个属于你的世界；把 Mac mini 重启，两分钟后还能进。

下面的命令全部在 Mac mini 上执行。你可以直接坐在它前面，也可以在 MacBook 上 `ssh <你>@mini` 之后敲，效果一样。

### 2.1 让 Mac mini 永远不睡

为什么：Mac 默认过一会儿就休眠，休眠了服务器就断了。另外我们的“开机自启”是挂在“用户登录后”这个时机上的，所以开机必须能自动登录，不能停在输密码的界面。

1. 系统设置 → 用户与群组 → 自动登录：选你的用户。它会要求关闭 FileVault（磁盘加密），接受：这台机器不离家，加密带来的是“开机必须有人输密码”，和我们的目标冲突。
2. 终端里：

```bash
$ sudo pmset -a sleep 0 disksleep 0 autorestart 1 womp 1
```

这一行在做什么：`pmset` 是电源管理设置，`-a` 表示对所有供电方式生效；`sleep 0` 不休眠，`disksleep 0` 硬盘不休眠，`autorestart 1` 断电来电后自动开机，`womp 1` 允许网络唤醒。

### 2.2 装 Java 17

为什么是 17：Minecraft 1.20.1 要求 Java 17，用更新的版本跑它不保证正常。后面的后台用 Java 21，两个可以同时装，互不干扰，我们每次都用完整路径指名用哪个。

```bash
$ brew install openjdk@17
$ sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
$ /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java -version
```

这几行在做什么：第 1 行装 Java 17；第 2 行在 macOS 的“标准 Java 目录”里建一个指向它的快捷方式（`ln -s` 是建快捷方式，Homebrew 装的 Java 默认藏在自己的目录里，系统和启动器找不到）；第 3 行用完整路径问它的版本。最后要打印出 `openjdk version "17.x"`。以后所有地方写的 `/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java`，就是这个 Java。

### 2.3 下载服务器，首次启动

```bash
$ mkdir -p ~/mcserver && cd ~/mcserver
$ curl -o server.jar https://piston-data.mojang.com/v1/objects/84194a2f286ef7c14ed7ce0090dba59902951553/server.jar
$ /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java -Xmx3G -jar server.jar --nogui
```

这几行在做什么：第 1 行建一个文件夹并进入它（`mkdir -p` 建文件夹，`cd` 进入）；第 2 行从 Mojang 官方地址下载 1.20.1 的服务器程序，存为 `server.jar`（这个地址来自 Mojang 的 1.20.1 发布文章）；第 3 行用 Java 跑它，`-Xmx3G` 是最多给它 3 GB 内存，`--nogui` 是不开那个没用的小窗口。

第一次跑它会在几秒内自己退出，并在文件夹里生成 `eula.txt` 和 `server.properties`。这是正常的：它要你先同意用户协议。用 nano 打开 `eula.txt`，把 `eula=false` 改成 `eula=true`，保存退出（Control + O、回车、Control + X）。

### 2.4 改服务器配置

`server.properties` 是服务器的总配置，一行一项。用 nano 打开它，找到下面这些行改成对应的值（找不到的就在末尾加一行）。`100.x.x.x` 换成小本子里 Mac mini 的 Tailscale 地址：

```properties
online-mode=false
white-list=true
enforce-whitelist=true
server-ip=100.x.x.x
server-port=25565
enable-rcon=true
rcon.port=25575
rcon.password=一串长随机字符（记进密码管理器）
view-distance=8
simulation-distance=6
motd=我和伙伴的世界
```

每一项的意思：

- `online-mode=false`：不向 Mojang 核验登录者的账号。伙伴用的是“离线账号”（只有一个名字，没有买过游戏），全靠这一行才能进来。
- `white-list=true` 和 `enforce-whitelist=true`：只有白名单里的名字能进，不在名单上的立刻踢出。关了认证之后这是唯一的门。
- `server-ip=100.x.x.x`：只在 Tailscale 那张虚拟网卡上开门。公网、家里普通局域网都连不到它。
- `enable-rcon` 三行：RCON 是一个“远程控制口”，让别的程序能给服务器发命令（比如“谁在线”“加白名单”）。服务器开机自启后没有控制台窗口，我们就靠它。密码要长而随机。
- `view-distance=8`、`simulation-distance=6`：服务器给每个玩家加载多大范围的地图。调小是为了省 Mac mini 的 16 GB 内存。
- `motd`：服务器列表里显示的一句话，随便写。

**安全说明，请记住**：关了认证之后，白名单只认名字不认人。现在 tailnet 里只有你自己，没问题；将来邀请朋友进 tailnet，他们技术上可以用你的名字登录。所以朋友必须是信得过的人，并且永远不要给任何名字 `/op`（管理员权限）。

### 2.5 做一个启动脚本，让它开机自启

**先理解 launchd**。macOS 自带一个“管家”叫 launchd：你给它一个小配置文件（扩展名 .plist），里面写“跑这个程序，登录后就跑，挂了就重起”，它就会照做。我们不直接让它跑 Java，而是让它跑一个小脚本，脚本里先等 Tailscale 就绪再启动服务器——因为服务器要绑定 `100.x` 地址，开机时 Tailscale 比它慢几秒，不等就会因为“地址还不存在”而启动失败。

用 nano 新建 `~/mcserver/start.sh`，粘进下面内容：

```bash
#!/bin/zsh
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
cd ~/mcserver
exec /Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java \
  -Xms3G -Xmx3G -XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 \
  -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch \
  -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M \
  -XX:G1ReservePercent=20 -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem \
  -XX:MaxTenuringThreshold=1 \
  -jar server.jar --nogui
```

这个脚本在做什么：第 1 行声明这是一个 zsh 脚本；第 2 行把 Tailscale 命令行工具的长路径存进变量 `TS`；第 3 行是一个循环：每 2 秒问一次“有没有 100. 开头的地址”，有了才往下走；第 4 行进入服务器文件夹；从 `exec` 开始是启动命令本身（`exec` 的意思是“用 Java 替换掉这个脚本自己”，这样 launchd 监管的就是 Java 本身）。`-Xms3G -Xmx3G` 是固定给 3 GB 内存；那一大串 `-XX` 是 Minecraft 服务器圈通用的 Aikar 参数，作用是让垃圾回收更平滑、少卡顿，照抄即可，不用理解每一项。行尾的 `\` 是“下一行还是同一条命令”。

再用 nano 新建 launchd 的配置 `~/Library/LaunchAgents/com.companion.mcserver.plist`（这个文件夹如果不存在，先 `mkdir -p ~/Library/LaunchAgents`；把两处 `<你>` 换成你的用户名）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.companion.mcserver</string>
  <key>ProgramArguments</key><array><string>/Users/<你>/mcserver/start.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>15</integer>
  <key>ExitTimeOut</key><integer>120</integer>
  <key>StandardOutPath</key><string>/dev/null</string>
  <key>StandardErrorPath</key><string>/Users/<你>/mcserver/logs/launchd.err</string>
</dict></plist>
```

这个文件在说什么：`Label` 是这个常驻服务的名字，以后启停都用它；`ProgramArguments` 是跑什么（必须写完整路径，这里不能用 `~`）；`RunAtLoad` 登录后立刻跑；`KeepAlive` 退出了就重起；`ThrottleInterval` 两次重起之间至少隔 15 秒（防止挂了以后疯狂重启）；`ExitTimeOut` 关机时给它 120 秒保存世界，不要硬杀；最后两行是输出写到哪（正常输出丢掉，因为服务器自己会写 `logs/latest.log`；错误输出存下来排错用）。

加载它：

```bash
$ chmod +x ~/mcserver/start.sh
$ mkdir -p ~/mcserver/logs
$ launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.companion.mcserver.plist
$ tail -f ~/mcserver/logs/latest.log
```

这几行在做什么：第 1 行给脚本“可执行”权限（不加这一行 launchd 会拒绝跑它）；第 2 行建日志文件夹；第 3 行把配置交给 launchd（`gui/$(id -u)` 的意思是“当前登录用户的桌面会话”），交了它就立刻开始跑；第 4 行持续显示服务器日志的最后几行。看到 `Done (x.xxxs)! For help, type "help"` 就是启动成功。按 Control + C 退出 `tail`，服务器不会停，它已经是 launchd 在管了。

以后用得着的两条：手动重启 `launchctl kickstart -k gui/$(id -u)/com.companion.mcserver`；彻底停掉 `launchctl bootout gui/$(id -u)/com.companion.mcserver`。

### 2.6 加白名单

服务器现在没有控制台窗口了，给它发命令要通过 2.4 开的 RCON。装一个小工具：

```bash
$ brew install mcrcon
$ mcrcon -H 127.0.0.1 -P 25575 -p '你的rcon密码' "whitelist add <你的玩家名>" "whitelist add <伙伴的玩家名>" "whitelist list"
```

这几行在做什么：`mcrcon` 是 RCON 客户端；`-H 127.0.0.1` 是连本机，`-P 25575` 是 RCON 端口，`-p` 后面是密码；后面每一个引号里是一条服务器命令，依次执行。`whitelist list` 会把两个名字印出来。

伙伴的玩家名现在就定下来：3–16 位，只能用字母、数字、下划线（比如人设名的拼音）。后面第 3 步的离线账号、第 5 步的配置都要和这里一模一样，大小写也算。如果 `brew install mcrcon` 说找不到，任何一个 RCON 客户端都行；实在不行，先 `bootout` 停掉 launchd 的服务器，手动跑一次 `~/mcserver/start.sh`，在它的控制台里直接敲 `whitelist add …`，完了 Control + C，再 `bootstrap` 回去。

### 2.7 确认这一步做对了

1. Windows 上打开 Minecraft 1.20.1（你平时的正版账号），多人游戏 → 添加服务器 → 地址填 `mini`，能进入世界。
2. 在 Windows 上关掉 Tailscale（托盘图标 → Disconnect）再连，应该连不上；重新连接 Tailscale 后恢复。这证明服务器确实只在 tailnet 里。
3. 用一个不在白名单的名字试（暂时把你自己从白名单里 `whitelist remove` 掉再连），应该被拒；试完加回来。
4. 重启 Mac mini（什么都不点），两分钟后 Windows 再连，仍能进。这证明自动登录和 launchd 都对了。

记到小本子：伙伴的玩家名；RCON 密码存在哪；服务器空载时“活动监视器”里 Java 的内存占用（以后比较用）。

## 第 3 步 · MacBook：给伙伴装一个能自己进服的游戏客户端

**这一步在做什么**。伙伴在游戏里的“身体”就是一个普通的 Minecraft 客户端，和你的一样，只是没有人坐在前面操作，改由程序操作。要让程序能操作它，就要给它装“模组”，而装模组要先装一个模组框架，这里用 Forge。这一步装两个模组：Baritone（现成的寻路模组，负责“怎么走到你身边”，别人写好的）和将来我们自己写的控制模组（第 6 步才有）。服务器那边什么都不用改：这两个都是“纯客户端模组”，服务器不知道也不关心。

还要解决两个实际问题：伙伴没有买过游戏，所以用“离线账号”（一个只有名字的账号，第 2 步的 `online-mode=false` 就是为它开的门）；客户端要能用一条命令启动并自动进服，不能每次靠人点鼠标，因为第 7 步要让它自动拉起。官方启动器这两样都做不到，所以用开源的 Prism Launcher。

做完之后你能看到的：在 MacBook 上敲一条命令，游戏窗口弹出来并自动进了你的服务器；你在 Windows 里看到伙伴站在出生点。它还不会听你的话，那是第 6 步的事。

全部在 MacBook 上操作。

### 3.1 装 Java 17 和 Prism Launcher

```bash
$ brew install openjdk@17
$ sudo ln -sfn /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk
$ brew install --cask prismlauncher
```

这几行在做什么：前两行和 2.2 完全一样，只是这次在 MacBook 上（客户端也是 1.20.1，也要 Java 17）；第 3 行装 Prism Launcher，它是一个应用，所以是 `--cask`。

第一次打开 Prism，它会问 Java 在哪：选 `/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home/bin/java`，或者点“自动检测”里那个 17。语言选中文也可以，下面的菜单名按中文写。

### 3.2 添加账号：先你的，再伙伴的

为什么要先加你自己的：Prism 的代码里写死了“必须已经有一个拥有 Minecraft 的正版账号，才允许添加离线账号”。这不影响你在 Windows 上玩：离线账号启动游戏时不会占用你正版账号的在线登录。

1. Prism 右上角“账户”→“管理账户”→“添加 Microsoft”，按提示在浏览器里登录你自己的账号。
2. 再点“添加离线”，输入伙伴的玩家名，和 2.6 白名单里的一模一样。把这个离线账号设为默认。

做错会怎样：名字差一个字母或大小写，进服时会被拒，提示 not white-listed。

### 3.3 建实例，装 Forge 和 Baritone

“实例”是 Prism 的概念：一套独立的游戏版本 + 模组 + 设置，互不干扰。

1. “添加实例”→ 名字填 `companion-vanilla-1.20.1`（后面的命令行和守护进程都用这个名字，不要改），版本选 1.20.1，“模组加载器”选 Forge，版本选带“推荐”标记的 47.4.x，确定。把这个具体版本号记到小本子，第 6 步要用同一个。
2. 第一次“启动”这个实例，让它联网下载游戏和 Forge 的库，进到主菜单后退出。主菜单左下角应该有 Forge 字样。
3. 下载 Baritone：打开 [Baritone v1.10.5 发布页](https://github.com/cabaletta/baritone/releases/tag/v1.10.5)，下载 **`baritone-api-forge-1.10.5.jar`**。页面上有好几个 jar，必须是名字里带 `api` 和 `forge` 的这一个。为什么：Baritone 官方说明写了，standalone 版会把全部代码“混淆”（把名字打乱），别的模组无法调用它；第 6 步我们的控制模组就要调用它，所以必须是 api 版。装错了现在看不出来，到第 6 步才会报错。
4. Prism 里右键实例 →“编辑”→“模组”→“添加文件”，选刚下载的 jar。

### 3.4 把画面调到最低

为什么：这个客户端没人看，画面越简单越省内存和电，给同一台机器上的大模型留余量。

1. “编辑”→“设置”→ Java：最大内存 4096 MB，最小 2048 MB。
2. 启动一次实例，在游戏里“选项 → 视频设置”：渲染距离 4、模拟距离 4、图形“流畅”、粒子“最少”、云关、实体阴影关、最大帧率 30、垂直同步关。退出游戏。
3. 游戏关着的时候，Prism 里右键实例 →“打开文件夹”，用文本编辑器打开里面的 `minecraft/options.txt`，确认或改成这几行（其他行不动）：

```text
pauseOnLostFocus:false
renderDistance:4
maxFps:30
narrator:0
onboardAccessibility:false
```

最重要的是 `pauseOnLostFocus:false`：默认情况下游戏窗口一失去焦点就弹暂停菜单，伙伴就会“定在那里”。这个文件必须在游戏关着时改，否则游戏退出时会把它改回去。窗口保持窗口模式，不要全屏。

### 3.5 让 MacBook 在共玩时不睡

系统设置 → 电池 → 选项 → 打开“接通电源适配器时，屏幕关闭时防止自动睡眠”。终端里：

```bash
$ sudo pmset -c sleep 0 displaysleep 10
```

这一行在做什么：`-c` 是“只在接电源时”；不休眠，屏幕 10 分钟后自己关。共玩时接电、开盖；屏幕自己黑掉没关系，合盖会睡（除非接了外接显示器）。

### 3.6 用命令行启动并自动进服

```bash
$ "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch companion-vanilla-1.20.1 --profile "<伙伴的玩家名>" --server mini:25565
```

这一行在做什么：前面引号里是 Prism 程序本身的路径（路径有空格，所以要引号）；`--launch` 启动哪个实例；`--profile` 用哪个账号；`--server` 启动后直接连哪个服务器。这三个参数来自 Prism 官方 wiki 的命令行说明。如果 Prism 窗口已经开着，这条命令会把请求转给开着的那个然后自己立刻退出，这是正常的（第 7 步的 keeper 要考虑这个特性）。

### 3.7 确认这一步做对了

1. 上面的命令跑完，游戏窗口出现并自动进入你的服务器；你在 Windows 里按 Tab 能看到伙伴的名字，走过去能看到它站在出生点。
2. 在伙伴的游戏窗口里按 T 打开聊天，输入 `#goto 100 64 100`，它应该自己走起来；输入 `#stop` 停下。`#` 开头的是 Baritone 自己的命令，这证明 Baritone 在原版服务器上能用。
3. 在 MacBook 上把游戏窗口最小化、切到别的应用，回 Windows 看：它仍然在世界里，没掉线。让它这样挂 30 分钟。
4. 活动监视器（应用程序 → 实用工具）里找到这个 Java 进程，记下它的内存和 CPU。

记到小本子：实例目录路径（“打开文件夹”里看）、Forge 的具体版本号、内存占用。

## 第 4 步 · MacBook：装本地大模型 Ollama

**这一步在做什么**。伙伴的“脑子”是一个大语言模型。你可以花钱调云端的，也可以在自己电脑上跑一个；这次选后者，因为 MacBook 有 48 GB 内存，足够跑一个中等尺寸的模型，而且不花钱、不依赖网络。Ollama 是一个跑本地模型的程序：你告诉它模型名，它负责下载、加载到内存、对外提供一个和 OpenAI 一样格式的接口，后台程序通过这个接口问它话。

还有一个关键概念叫**工具调用**（tool calling）。模型本身只会出文字。要让它“让伙伴走起来”，做法是：后台在每次提问时附上一张“工具清单”（比如 follow、stop），模型如果认为你在下命令，就不回文字，而是回一条结构化的“请调用 follow”，后台看到这条就去指挥模组。不是所有模型都会这招，所以选模型时要看标签。

做完之后你能看到的：MacBook 上常驻一个模型服务；在 Mac mini 上敲一条命令问它“跟着我走”，它回的不是一句话，而是“调用 follow”。全部在 MacBook 上操作，除了 4.5。

### 4.1 安装，先手动跑一次

```bash
$ brew install ollama
$ ollama --version
```

要求版本 0.40 以上（从这个版本起，Apple 芯片上默认用 MLX 加速，快很多）。不要再去官网下载 Ollama 的桌面版 App，两个会抢同一个端口。

### 4.2 选模型，下载

选模型要满足三件事：中文好；支持工具调用（模型库页面上标有 tools）；留出内存给游戏客户端。MacBook 48 GB，游戏客户端吃 4–5 GB，系统自己要几个 GB，所以模型本体控制在 25 GB 以内。写这本手册时的候选如下；以 [ollama.com/library](https://ollama.com/library) 页面上的实际名字和大小为准，名字对不上就选页面上标了 tools 的最新一代 Qwen 中等尺寸版本：

| 模型 | 大约占用 | 建议 |
| --- | --- | --- |
| `qwen3.8:27b` | 约 18 GB | **首选**，余量最足 |
| `qwen3.6:35b` | 约 24 GB | 更聪明，但和游戏客户端一起跑时余量小 |
| `gemma4:26b` | 约 16 GB | 备选，中文稍弱 |

```bash
$ ollama pull qwen3.8:27b
```

`pull` 是下载。十几 GB，等它完成。然后试一句：

```bash
$ ollama run qwen3.8:27b "用一句话介绍你自己"
```

第一次要等十几秒把模型加载进内存。能用中文回答就行。如果它进入了对话模式，按 Control + D 退出。

### 4.3 建一个专用模型，固定上下文长度

为什么：“上下文长度”是模型一次能看多少字。伙伴每次回答前要看：人设、最近的对话、它在游戏里看到的东西、工具清单，Ollama 默认的长度不够。另外我们给这个配置起一个固定名字 `companion-local`，后台配置里永远写这个名；以后换底层模型，只改这里，后台不用动。

用 nano 新建 `~/companion/ops/Modelfile`（先 `mkdir -p ~/companion/ops`），内容：

```text
FROM qwen3.8:27b
PARAMETER num_ctx 16384
PARAMETER temperature 0.7
```

这三行在说什么：基于哪个模型；上下文长度 16384 个 token（大约一万多个汉字）；“温度”0.7 是回答的随机程度，越低越死板，越高越天马行空，0.7 是聊天的常用值。人设不写在这里，由后台每次请求时发送（第 5 步），这样改人设不用重建模型。

```bash
$ cd ~/companion/ops
$ ollama create companion-local -f Modelfile
$ ollama list
```

`create` 按 Modelfile 生成一个新的模型名（不会再下载，几秒完成）；`list` 列出所有模型，里面应该有 `companion-local`。

### 4.4 让它绑定到 Tailscale 地址，并开机常驻

为什么：Ollama 默认只听本机（`127.0.0.1`，这个地址永远指“我自己”），别的电脑连不到。我们让它听 MacBook 的 `100.x` 地址，这样 Mac mini 能连，而且因为 `100.x` 只在 tailnet 里存在，咖啡馆 Wi-Fi 里的陆生人连不到。做法和 2.5 一模一样：一个等 Tailscale 就绪的脚本，加一个 launchd 配置。

用 nano 新建 `~/companion/ops/ollama.sh`：

```bash
#!/bin/zsh
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
export OLLAMA_HOST="$("$TS" ip -4):11434"
export OLLAMA_KEEP_ALIVE=-1
export OLLAMA_CONTEXT_LENGTH=16384
exec /opt/homebrew/bin/ollama serve
```

这个脚本在做什么：前三行和 2.5 一样，等 Tailscale 地址就绪；`OLLAMA_HOST` 告诉 Ollama 听哪个地址和端口（这里直接把当前的 `100.x` 填进去）；`OLLAMA_KEEP_ALIVE=-1` 让模型加载后永远留在内存里，否则它闲几分钟就卸掉，每次聊天前要等十几秒重新加载；`OLLAMA_CONTEXT_LENGTH` 和 Modelfile 里的一致；最后一行启动服务。

再用 nano 新建 `~/Library/LaunchAgents/com.companion.ollama.plist`（`<你>` 换成用户名）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.companion.ollama</string>
  <key>ProgramArguments</key><array><string>/Users/<你>/companion/ops/ollama.sh</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>StandardOutPath</key><string>/Users/<你>/companion/logs/ollama.log</string>
  <key>StandardErrorPath</key><string>/Users/<你>/companion/logs/ollama.err</string>
</dict></plist>
```

每一项的意思和 2.5 相同。加载：

```bash
$ chmod +x ~/companion/ops/ollama.sh && mkdir -p ~/companion/logs
$ launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.companion.ollama.plist
$ tail -5 ~/companion/logs/ollama.err
```

日志里出现 `Listening on 100.x.x.x:11434` 即成功。注意：不要再用 `brew services start ollama`，那会再起一个只听本机的实例，两个打架。

### 4.5 从 Mac mini 发一条带工具的请求

这是这一步最重要的验证：它同时证明网路通、模型在、模型会工具调用。在 Mac mini 上（`ssh <你>@mini`），把 `<mbp的100.x>` 换成 MacBook 的地址：

```bash
$ curl -s http://<mbp的100.x>:11434/api/tags | head -c 300
$ curl -s http://<mbp的100.x>:11434/api/chat -d '{
  "model": "companion-local", "stream": false, "think": false,
  "messages": [
    {"role": "system", "content": "你是玩家的游戏伙伴。玩家要你做动作时，调用对应的工具，不要只用文字回答。"},
    {"role": "user", "content": "跟着我走"}
  ],
  "tools": [{"type": "function", "function": {"name": "follow", "description": "开始跟随玩家", "parameters": {"type": "object", "properties": {}}}},
            {"type": "function", "function": {"name": "stop", "description": "立刻停下", "parameters": {"type": "object", "properties": {}}}}]
}'
```

这两条在做什么：第 1 条问 Ollama “你有哪些模型”，`| head -c 300` 只显示前 300 个字符。能印出模型列表，说明 Tailscale 规则（`tag:mcserver` 到 `tag:workstation` 的 11434）和绑定地址都对。第 2 条是一次完整的“聊天请求”：`-d` 后面是请求内容，里面有模型名、一条系统指令（相当于临时人设）、一句用户的话、一张有两个工具的清单。`"think": false` 关掉“思考模式”，否则每句话前它会先自言自语几秒。

要看到的结果：第 2 条的回复里有 `"tool_calls"`，并且里面 `"name": "follow"`。这就是“它听懂了这是命令”。如果它只回了一句话没有 tool\_calls，换一个标了 tools 的模型再试。

### 4.6 确认这一步做对了

1. MacBook 上 `ollama ps` 显示 `companion-local` 已加载，处理器一列是 `100% GPU`（走的是 Apple 芯片的统一内存，这是对的）。
2. 4.5 的两条命令都通过。
3. 同时把第 3 步的游戏客户端跑起来，活动监视器 → 内存 → 底部“内存压力”保持绿色。变黄或变红就换 4.2 表里更小的模型。
4. 重启 MacBook，登录后一分钟内，4.5 的第 1 条命令再次能通。

记到小本子：选定的模型名；`ollama ps` 显示的大小；4.5 第 2 条命令从发出到返回的秒数——这是以后伙伴“反应时间”的底线。

## 第 5 步 · 后台：让它先能在网页上聊天

**这一步在做什么**。前四步装的都是别人写好的软件。从这一步开始要有自己的代码：“后台”是一个 Java 程序，跑在 Mac mini 上，干四件事——接网页的连接（Hub）、把你的话配上人设和记忆去问模型（Brain）、和模型说话的那一小层（Gateway）、把聊天和事件存进数据库（Memory）。这一步还不接真的游戏客户端，而是先写一个“假执行器”假装是游戏：这样后台的所有逻辑都能在不开游戏的情况下测通，第 6 步出问题时你就知道问题一定在模组这边。

做完之后你能看到的：在 Windows 浏览器里打开一个网页，和伙伴用中文聊天，它记得之前聊过什么；你说“跟我来”，它把命令发给假执行器，假执行器在终端里打印“收到 follow”，它再把结果说回来。

### 5.0 你和 AI 编程助手怎么分工

你不写代码。代码由 AI 编程助手（Claude Code、Cursor、Codex 都行）写，你做三件事：

1. 把下面的“任务单”原样复制给它，一次一张。任务单写的是“要做成什么样”和“怎么算做对了”，不写怎么写代码，那是它的事。
2. 它说做完了，你按任务单里的“验收”逐条试。试的方法都是你能亲眼看到的事，不需要看懂代码。
3. 没通过，把报错原文、你做了什么、看到了什么，原样贴回去让它改。不要自己猜原因。

开发在 MacBook 上做（代码、编译、测试都在这），测通了再把打包好的文件拷到 Mac mini 上跑。第 5、6、7 步都是这个套路。

两个会反复出现的词：**WebSocket** 是网页、后台、模组之间“保持一条长连接、随时双向发消息”的方式，和普通网页“点一下请求一次”不同；地址以 `ws://` 或 `wss://` 开头。**token** 是一串你自己生成的长随机字符，当口令用：没有它的连接后台一律不理。

### 5.1 MacBook 上的开发环境和仓库骨架

```bash
$ brew install git openjdk@21
$ sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
$ brew install --cask visual-studio-code
$ mkdir -p ~/companion/{protocol,server,mock-executor,keeper,mod,ui,ops,docs} && cd ~/companion && git init
$ printf 'data/\nlogs/\n*.jar\nbuild/\n.gradle/\nconfig.yaml\n' > .gitignore
```

这几行在做什么：第 1 行装 git（代码的版本管理，每次改动都能回溯）和 Java 21（后台用 21，模组用 17，两个都装着不冲突）；第 2 行和 2.2 同理；第 3 行装一个编辑器，你用它看文件和改配置；第 4 行一次建好全部子文件夹（花括号是“每个名字各建一个”）并把这个文件夹变成 git 仓库；第 5 行写一个 `.gitignore`，告诉 git 这些东西（数据、日志、编译产物、含密码的配置）不要入库。

每个文件夹是什么：`protocol` 是后台和模组之间消息的共同定义；`server` 是后台；`mock-executor` 是假执行器；`keeper` 是第 7 步的守护进程；`mod` 是第 6 步的模组；`ui` 是网页；`ops` 放脚本和配置；`docs` 放设计文档。构建工具用 Gradle（Java 圈的“编译 + 打包 + 跑测试”工具，仓库里自带一个 `./gradlew`，不用另装）。

把《详细设计与实施手册》导出成 Markdown，存为 `docs/handbook.md`。那份文档里有消息格式、状态机、数据库表结构，AI 助手会自己去读，你不用转述。

### 5.2 先给助手一段总说明

打开 AI 编程助手，工作目录选 `~/companion`，第一句话把下面这段贴给它。以后每次新开会话都先贴（它不记得上次的对话）：

```text
这个仓库是一个自托管的“AI 伙伴”：它用中文和我聊天，并以独立玩家身份在我的 Minecraft 1.20.1 原版服务器里跟随、停下、过来、捡起、交付物品。
设计文档在 docs/handbook.md，其中协议、消息格式、状态机、数据库表是规范，实现必须遵守；有矛盾先问我。
约束：
- 后台（server、protocol、mock-executor、keeper）用 Java 21 + Gradle，打包成单个可运行 jar；模组（mod）用 Java 17 + ForgeGradle。
- 大模型是 MacBook 上的 Ollama，地址和模型名来自 config.yaml，模型名固定为 companion-local。不接任何云服务。
- 命令只来自网页（/ws/ui）；游戏内聊天不是命令渠道。同一时刻只有一个进行中的任务，新任务替换旧任务时旧任务要收到自己的 cancelled 结果。
- 日志 INFO 级别不得包含聊天内容原文，只记事件和长度。密码和令牌只从 config.yaml 读，不进 git。
- 每个模块要有能用 ./gradlew test 跑的单元测试。网页和所有面向我的文案用中文。
- 每张任务单做完，用一段话告诉我：怎么跑、怎么验、哪些地方你做了设计文档没写的决定。
```

这段在说什么：第一段是项目是什么；然后是它必须遵守的规矩。其中最重要的两条：“命令只来自网页”——朋友将来在游戏里说话不能指挥伙伴；“同一时刻只有一个任务”——你说“跟我来”之后再说“等在这”，跟随就被取消，不会两个一起跑。

### 5.3 任务单 1 · 协议（protocol/）

这张在做什么：后台和模组要互相发消息，消息长什么样必须两边一致。这张单把“消息长什么样”写成一个两边都能用的小库。

```text
任务：在 protocol/ 实现 docs/handbook.md 里的 WebSocket 消息协议。
内容：
1. 信封格式 v/type/id/ts/from/to/body；消息类型 hello/snapshot/event/command/ack/progress/result；
   命令 follow/come_here/wait_here/stop/pick_up/deliver/report_status/cancel/rejoin；
   结果原因码 target_lost/target_other_dim/target_too_far/not_picked/path_failed/stuck/item_not_found/inventory_full/timeout/died/disconnected/executor_unresponsive/rejected。
2. 一套 Java 类（record）+ Gson 序列化，后台和模组都能用（所以这个模块要用 Java 17 语法编译，字节码目标 17）。
3. protocol/examples/ 下每种消息至少一个 JSON 样例；测试逐个读入、解析、再序列化，必须一致。
4. deliver 的 result 要能表达三种状态：已丢出 / 已确认接收 / 结果未知；snapshot 里的字段允许为 unknown（例如刚重连还没看到主人）。
验收：./gradlew :protocol:test 全绿；examples/ 里至少 12 个文件。
```

你怎么验：在 `~/companion` 里跑 `./gradlew :protocol:test`，最后一行是 `BUILD SUCCESSFUL`。打开 `protocol/examples/command-follow.json` 之类的文件看一眼，字段名和设计文档一致。“全绿”的意思是所有自动测试都通过，有一个失败都会打印 FAILED。

### 5.4 任务单 2 · 假执行器（mock-executor/）

这张在做什么：一个假装自己是游戏客户端的小程序。它不开游戏，只是按协议“演”一个客户端：定时发编造的状态，收到命令就假装执行。它还能故意“装死”和“装卡住”，用来测后台在异常时会不会乱。

```text
任务：在 mock-executor/ 写一个命令行程序，假装自己是装了控制模组的游戏客户端。
行为：
1. 启动后连接 ws://<后台地址>/ws/mc，发 hello，之后每 1 秒发一条编造的 snapshot（坐标慢慢变化，生命值 20，背包里有 8 个 oak_log）。
2. 收到 command 后：立刻回 ack；在终端打印“收到 <命令>”；1 秒后发 progress；3 秒后发 result ok。
   stop 和 cancel 立即回 result；如果正在执行别的任务，先给旧任务发 result cancelled。
3. 支持两个开关用来制造故障：--fail stuck 让下一个任务 3 秒后返回 stuck；--silent 让它收到命令后什么都不回（用来测后台的超时）。
4. 连接断了每 5 秒重连。
验收：在没有后台的情况下启动，它不崩，每 5 秒打印一次“重连中”。其余验收在任务单 3 一起做。
```

### 5.5 任务单 3 · 后台（server/）

这张在做什么：整个系统的中枢。它最长，但你只需要看懂最后的验收 a–f，每一条都是你能亲手做的事。

```text
任务：在 server/ 实现后台，按 docs/handbook.md 的 Hub / Brain / Gateway / Memory 四个模块划分。
1. 对外：HTTP 端口来自 config.yaml（默认 8787），提供静态网页（ui/ 的产物）和三个 WebSocket 端点 /ws/ui、/ws/mc、/ws/keeper。
   所有连接必须带 config.yaml 里的 token（查询参数或首条消息），不对就断开。
2. Hub：维护“伙伴在线/离线”和“当前任务”（单任务槽）；把网页来的文字交给 Brain；把 Brain 决定的工具调用翻译成 command 发给 /ws/mc；
   收到 result 后再让 Brain 生成一句人话回复网页。ack 超过 3 秒没来 = executor_unresponsive；任务超过 config 里的时长 = timeout。
   伙伴离线时收到动作请求，直接用人话回“我现在不在游戏里”，不调模型。
3. Brain：人设来自 persona.md（路径在 config 里）；工具定义 follow/come_here/wait_here/stop/pick_up(item,radius)/deliver(item,count,to)/report_status/remember_preference；
   每次请求带：人设 + 最近 N 条对话（N 在 config）+ 最新 snapshot 的摘要 + 已确认的偏好。
   结果到达后的“说一句话”请求不带动作工具，防止它自己给自己下命令。
4. Gateway：一个 ModelClient 接口 + 一个 OllamaClient 实现，请求 config 里的 base_url（形如 http://100.x.x.x:11434）。
   优先用 OpenAI 兼容的 /v1/chat/completions 带 tools；如果这条路关不掉思考模式，改用 Ollama 原生 /api/chat 并带 think:false。
   模型返回的多个工具调用按顺序保存（含 call id），但本版本只执行第一个动作工具，其余用人话告诉我“一次只做一件”。
   模型超时（config，默认 60 秒）或连不上时，网页收到一条固定的中文提示，不崩。
5. Memory：SQLite（sqlite-jdbc），表 messages/events/preferences(pending/active)/episodes，FTS5 全文索引；
   每天结束或伙伴下线时把当天的 events 概括成一条 episode（调一次模型）；下次对话开始时把最近 3 条 episode 塞进提示词。
   remember_preference 工具只写 pending，网页上我点“确认”才变 active。删除一条消息要连带删掉由它生成的派生条目。
   每天凌晨 VACUUM INTO 备份到 data/backup/，保留 14 份。
6. 打包：./gradlew :server:shadowJar（或等价）产生单个 jar，启动命令 java -jar server.jar --config config.yaml。给我一份 config.example.yaml，每个字段有中文注释。
验收（都在 MacBook 上，配合 mock-executor）：
 a. 启动后台、启动 mock-executor，后台日志出现“executor online”。
 b. 用 websocat 或任务单 4 的网页发“你好，你叫什么”，10 秒内收到符合人设的中文回复。
 c. 发“跟我来”，mock-executor 打印“收到 follow”，3 秒后网页收到一句表示“来了/跟着你”的回复。
 d. 用 --silent 重启 mock-executor，发“过来”，3 秒内网页收到“没反应”类回复，后台不崩。
 e. 关掉后台再启动，发“我们刚才聊了什么”，它能说出上一条。
 f. grep 日志文件，找不到你发过的任何一句原话。
```

这张单里几个值得你知道的设计：第 3 条“结果到达后的请求不带动作工具”是防止伙伴自己给自己下命令、无限循环；第 5 条的 episode 是“每天写一段日记”，第二天它能说出昨天做了什么就靠这个；“偏好要你点确认才生效”是为了不让它把你随口一句话当成永久设定。

**人设文件 `persona.md` 你自己写**，一页以内。建议包含：名字；说话风格（举三句例句，例句比形容词有用得多）；和你的关系；不做的事（不自称 AI 或模型、不长篇大论、每次回复不超过两句）；在游戏里的规矩（一次只做一件事，做不到就说做不到）。写完读一遍，把所有“它应该很聪明”之类的形容词删掉，只留具体的说法。以后它说话哪里不像，就改这个文件，不用动代码。

### 5.6 任务单 4 · 网页（ui/）

这张在做什么：你在 Windows 浏览器里看到的那个页面。它由后台直接提供，不用再单独跑一个服务。

```text
任务：在 ui/ 写一个单页网页，由后台直接提供，不要单独的 Node 服务。构建产物放进 server 的静态目录。
内容：
1. 顶部状态条：伙伴 在线/离线、当前任务（如“跟随中”）、生命值、所在维度；一个大的红色“停”按钮，点了直接发 stop，不经过模型。
2. 中间聊天记录，底部输入框，回车发送；模型回复是流式就逐字显示。
3. 一个“待确认的偏好”小面板，每条有“确认 / 忽略”。
4. 连接 wss://<当前域名>/ws/ui?token=...，token 第一次让我输入后存在浏览器本地；断线自动重连并在状态条显示“重连中”。
5. 手机宽度也能用。纯 HTML/CSS/JS 或一个轻量框架均可，但构建产物必须是静态文件。
验收：用它完成任务单 3 的 b–e；点“停”后 mock-executor 1 秒内打印“收到 stop”。
```

“停”按钮不经过模型是故意的：紧急停止不能等模型想几秒。

### 5.7 部署到 Mac mini

“部署”就是把在 MacBook 上打包好的程序拷到 Mac mini 上跑起来。先在 Mac mini 上（`ssh <你>@mini`）装 Java 21 并建目录：

```bash
$ brew install openjdk@21
$ sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-21.jdk
$ mkdir -p ~/companion/{data,logs}
```

在 MacBook 上打包并拷过去（以后每次改完代码都是这两行）：

```bash
$ cd ~/companion && ./gradlew :server:shadowJar
$ scp server/build/libs/server-all.jar mini:~/companion/server.jar
```

这两行在做什么：第 1 行让 Gradle 把后台编译并打成一个单文件（shadowJar 是“把所有依赖都塞进同一个 jar”）；第 2 行 `scp` 是“通过 SSH 拷文件”，把它拷到 Mac mini 的 `~/companion/server.jar`。jar 的实际文件名以助手告诉你的为准。

第一次还要把 `config.example.yaml` 拷成 `mini:~/companion/config.yaml`、`persona.md` 拷到同一目录（也用 `scp`），然后在 Mac mini 上用 nano 打开 `~/companion/config.yaml` 填好：端口 8787；token（自己生成一串长随机字符，存进密码管理器）；Ollama 地址 `http://<mbp的100.x>:11434`；模型 `companion-local`；伙伴和你的玩家名；数据目录 `data/`。先手动跑一次看有没有报错：

```bash
$ cd ~/companion && /Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin/java -jar server.jar --config config.yaml
```

然后让 Tailscale 把它包成 HTTPS。为什么要 HTTPS：现在纯打字用 http 也行，但以后加语音时浏览器要求“安全上下文”才允许用麦克风，现在就把路铺好。Tailscale 自带这个功能，一条命令就行。Mac mini 上先把 1.4 里的 `tailscale` 别名也做一遍，然后另开一个终端（刚才那个还在跑后台）：

```bash
$ tailscale serve --bg 8787
$ tailscale serve status
```

这两行在做什么：第 1 行告诉 Tailscale “把本机 8787 端口的服务，用 HTTPS 对 tailnet 内提供”，`--bg` 是后台常驻，重启后仍在；第 2 行看状态，它会打印一个 `https://mini.tailxxxx.ts.net` 地址。第一次签证书要等几十秒。在 Windows 的浏览器里打开这个地址，输入 token，这就是你和伙伴聊天的地方。开机常驻（launchd）在第 7 步统一做，现在先手动跑着。

### 5.8 确认这一步做对了

1. Windows 浏览器里和它聊 20 轮：人设稳定、不自称模型、能接上上一句（总览里的 A）。记下平均几秒开始回复。
2. 在 MacBook 上跑 mock-executor 连 Mac mini（地址 `ws://mini:8787/ws/mc`），网页状态条变“在线”；说“跟我来”“停”“把木头给我”，mock-executor 分别打印 follow / stop / deliver，其中 deliver 的参数是 oak\_log。
3. 闲聊“今天天气不错”“你喜欢什么”，mock-executor 什么都不打印（闲聊不被当成命令）。
4. 关掉 mock-executor，网页变“离线”，再说“过来”，它用人话说自己不在游戏里。
5. 第二天打开网页问“昨天我们聊了什么”，它能说出大概（总览里的 F）。

记到小本子：HTTPS 地址；token 存在哪；平均首字响应秒数；后台在 Mac mini 上的内存占用。

## 第 6 步 · 模组：让伙伴在游戏里动起来

**这一步在做什么**。把第 5 步的假执行器换成真的：一个装在伙伴游戏客户端里的模组。它做三件事：连上后台（link）；每秒把自己看到的世界报给后台（observer，“观察”）；收到命令就指挥身体动，走路交给 Baritone（executor，“执行”）。因为第 5 步已经用假执行器把后台测通了，这一步出问题一定在模组这边，排查范围小很多。

这是整个计划里最不确定的一步：模组跑在游戏进程里，调试比后台难。所以任务单拆得很细，一张一张来，每张都在真服务器上验过再下一张。

做完之后你能看到的：你在网页上说“跟我来”，它在世界里真的走到你身边。

### 6.1 把模组开发环境搭起来（MacBook）

先理解两个词。**MDK** 是 Forge 官方给模组作者的“项目模板”，里面已经配好了编译模组需要的一切；我们的模组就在这个模板里写。**ForgeGradle** 是它自带的构建工具，负责把 Minecraft 本体反编译出来让模组能引用。

1. 打开 [Forge 的 1.20.1 下载页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)，下载和第 3 步实例同一个版本号的 **MDK**（不是 Installer）。这个页面有广告，点版本号旁边的小字 MDK，跳转后右上角点 Skip。
2. 解压，把里面的全部文件放进 `~/companion/mod/`。
3. 终端里：

```bash
$ cd ~/companion/mod && JAVA_HOME=/Library/Java/JavaVirtualMachines/openjdk-17.jdk/Contents/Home ./gradlew build
```

这一行在做什么：进入模组目录；`JAVA_HOME=…` 是“这条命令用 Java 17”（模组必须用 17 编译，和游戏一致；系统里还装着 21，不指定可能用错）；`./gradlew build` 编译并打包。第一次会下载很多东西，十几分钟正常；结尾 `BUILD SUCCESSFUL` 即环境可用。以后每次打包都是这条命令，产物在 `mod/build/libs/`。

4. 把第 3 步下载的 `baritone-api-forge-1.10.5.jar` 复制一份到 `~/companion/mod/libs/`（文件夹不存在就建一个），给编译用。

测试方式始终是：把打包出的 jar 放进 Prism 实例的 `minecraft/mods/` 目录（和 Baritone 那个 jar 并排，3.4 里“打开文件夹”能找到），用 3.6 的命令启动。每次换新 jar 要先把旧的删掉。不用 MDK 自带的 `runClient`，它用的是另一个游戏目录和账号，容易混。

### 6.2 任务单 5 · 模组骨架和连接（link）

这张在做什么：一个“什么都不干、只连上后台”的模组。先把最难的“能不能装上、能不能进服、能不能连后台、能不能调到 Baritone”四件事验掉。

```text
任务：在 mod/ 的 Forge 1.20.1 MDK 里建一个纯客户端模组 companion，它连上后台的 /ws/mc，其余什么都不做。
1. mods.toml：modId companion，clientSideOnly=true，displayTest="IGNORE_SERVER_VERSION"（连原版服务器不显示红叉）。模组在服务器端不存在也能进。
2. 依赖：Baritone API 用 libs/baritone-api-forge-1.10.5.jar 作编译依赖（不打进我们的 jar，运行时由 mods 目录里的同一个 jar 提供）；
   WebSocket 客户端用 Java-WebSocket，通过 jarJar 打进模组；JSON 用游戏自带的 Gson；消息类复用 protocol 模块。
3. 配置文件 config/companion-client.toml：hub 地址（ws://mini:8787/ws/mc）、token、主人玩家名。
4. 进入世界后连接，发 hello；断线按 2/4/8/…/30 秒退避重连；与后台断开的瞬间立刻在本地调 Baritone 的 cancelEverything()，伙伴不能在没人管的情况下继续走。
5. 把所有 Baritone 设置在进入世界时写死：allowBreak/allowPlace/allowParkour/allowInventory = false，followRadius = 3。
6. 日志只写到游戏自己的 latest.log，前缀 [companion]。
验收：打包的 jar 放进 Prism 实例后游戏能启动、能进服；后台日志出现 executor online；网页状态条变“在线”；在 latest.log 里能看到 [companion] baritone provider ok。
```

这张单里你要知道的：第 4 条“断开就立刻停”是安全底线——后台挂了，伙伴不能在没人管的情况下继续走到岩浆里；第 5 条是因为 Baritone 默认会挖方块、搭路来寻路，我们不要它把你的房子挖穿。

你怎么验：打包 → 拷 jar → 3.6 启动 → 看 Windows 浏览器里的状态条。然后在 Mac mini 上把后台 Control + C 关掉、再启动，状态条应该先“离线”后自动恢复“在线”，不用重启游戏。

### 6.3 任务单 6 · 看周围（observer）

这张在做什么：让模组把它看到的世界定时报给后台（“快照”），有事发生时立刻报（“事件”）。后台把快照摘要给模型看，模型才知道“你在哪、它在哪、它背包里有什么”。

```text
任务：让模组每 20 tick（1 秒）发一条 snapshot，并在事情发生时发 event。
1. snapshot 字段按设计文档：坐标、维度、生命、饥饿、背包摘要（物品 id → 数量）、主人（在视野内则给距离和坐标，否则 unknown）、当前任务状态。
   主人通过 mc.level.players() 里名字等于配置的主人名来找。
2. event：游戏内聊天（ClientChatReceivedEvent.Player 和 .System），只转发主人说的，其他玩家的丢弃；死亡（isDeadOrDying 第一次为真）；重生（ClientPlayerNetworkEvent.Clone）；被踢出/断线；背包里某种物品数量变化（捡到了东西）。
3. 重生或重连之后，先发一条带完整字段的 snapshot，再允许接新命令；死亡时正在执行的任务返回 died。
验收：网页状态条显示生命值和维度；你在游戏里打它一下，生命值变化在 2 秒内反映到网页；你在游戏里说话，后台日志出现 event chat（不含原文）；你把它推进岩浆，网页很快显示“死了”，重生后恢复“在线”。
```

“主人不在视野内就报 unknown”这条很重要：客户端只能看到附近一小块世界，看不到你不等于你不在，所以要允许它说“不知道”，而不是瓜猜。

### 6.4 任务单 7 · 五个动作（executor）

这张在做什么：真正的动作。最长的一张，建议分两次交给助手：先 1–4，验过再 5–7。里面有一些代码级的要求（比如用哪个函数丢物品），那是写给助手看的，你不用懂，但不要删，它们是踩过坑的结论。

```text
任务：实现执行器。单任务槽：新 command 到达时先给旧任务发 result cancelled 再开始新的。每条 command 先回 ack。所有寻路走 Baritone API：
  BaritoneAPI.getProvider().getPrimaryBaritone() 拿到 IBaritone；跟随用 getFollowProcess().follow(谓词)；走到某点用 getCustomGoalProcess().setGoalAndPath(new GoalNear(pos, 2))；停用 getPathingBehavior().cancelEverything()。
1. follow：跟随主人实体；主人超过 10 秒不在视野内 → result target_lost；主人换了维度 → target_other_dim；距离每变化 8 格发一条 progress。跟随没有“完成”，只会被替换或停止。
2. come_here：走到主人当前位置 2 格内 → ok；Baritone 报无路可走 → path_failed；寻路中连续 5 秒位置没变 → cancelEverything 并 result stuck，不重试。
3. wait_here：cancelEverything 后留在原地 → ok。
4. stop：调 cancelEverything() 并检查返回值；之后每 tick 看自己是否已静止（水平速度近于 0 且 Baritone 没有活动过程），静止了才回 result ok；连续 20 tick 仍未静止则再调一次，最多三次，仍不行回 result 并标注未静止。“停”永远不走模型，收到即执行。
5. pick_up(item, radius)：在半径内找对应物品的 ItemEntity，没有 → item_not_found；走过去，用背包差异确认捡到 → ok；15 秒没捡到 → not_picked；背包满 → inventory_full。
6. deliver(item, count, to)：走到主人 3 格内并看向主人；把物品丢出必须用会发包给服务器的方式（gameMode.handleInventoryMouseClick(…ClickType.THROW…) 或选中快捷栏后 LocalPlayer.drop(true)），
   绝对不要用 Player.drop(ItemStack,…)，那只在本地画一个假物品。丢完立刻后退至少 3 格（丢出的物品有 40 tick 不能被捡，退开是为了不自己捡回去）。
   结果三态：收到 ClientboundTakeItemEntityPacket 且捡起者是主人 → “已确认接收”；已丢出但 10 秒内没收到确认 → “已丢出，结果未知”；没丢出去 → 对应原因。结果未知时绝对不要自动再丢一次。
   捡起确认用 mixin 拦截那个包；如果你选别的方法（比如只看 ItemEntity 消失），那只能报“结果未知”，不能报“已确认”。
7. rejoin：用 ConnectScreen.startConnecting 重新连接配置里的服务器地址；被踢或掉线后自动做一次，间隔 10 秒，最多 5 次。
验收：见 6.5。
```

这张单里你要知道的两件事。一是“停”不是收到命令就算停了，而是真的不动了才算；否则它会说“停了”人还在滑。二是“交付”有三种结果，不是成功/失败两种：它看不到你的背包，只能靠“有没有收到服务器说你捡起了”来判断；没收到就诚实说“丢在那了，不知道你捡没捡”，绝不自作主张再丢一次（那会把你的木头丢光）。

### 6.5 在真服务器上逐个验证

你在 Windows 里进游戏，伙伴在 MacBook 上进游戏，网页开在 Windows 的第二个屏幕或手机上。每个场景做 10 次，记录成功次数：

| 场景 | 你做什么 | 通过标准 |
| --- | --- | --- |
| 跟随 | 说“跟我来”，然后走 50 格、拐弯、上山 | 它始终在你 3–6 格内，不挖洞不搭路 |
| 停 | 跟随中点网页的“停” | 1 秒内定住，网页显示已停 |
| 过来 | 走到 30 格外说“过来” | 走到你身边后回一句话 |
| 捡 | 丢一组橡木原木在 5 格外，说“把木头捡起来” | 捡到后网页状态条背包里出现橡木原木 |
| 交付 | 说“木头给我” | 它走过来丢到你脚下、后退，你捡到，它说“给你了”；你故意不捡，它说“丢在那了”而不是再丢 |
| 卡住 | 把它关进栅栏再说“过来” | 5 秒内说卡住了，栅栏完好 |
| 丢目标 | 跟随中你传送走或进下界 | 10 秒内它说找不到你了，并停下 |
| 中途改口 | 跟随中说“等在这” | 立刻停下，网页显示跟随已取消、当前在等待 |
| 崩溃恢复 | 直接在 MacBook 上强制退出游戏 | 网页变离线（自动拉起是第 7 步的事） |

每一行达到 9/10 才算过。没过的，把游戏 `latest.log` 里带 `[companion]` 的行和后台日志一起贴给助手。`latest.log` 在实例目录的 `minecraft/logs/` 里。

### 6.6 确认这一步做对了

6.5 的表全部达标；跟随中和它连聊 5 轮闲话，跟随不断（总览里的 C、D、E）。把第 5 步的 mock-executor 收进抽屉，以后只在改后台时用。

记到小本子：模组版本号；每个场景的成功次数；最常见的失败原因码。

## 第 7 步 · 常驻与总验收

**这一步在做什么**。到现在为止，后台还是你在终端里手动跑的，游戏客户端也是你手动敲命令启动的。这一步把它们都变成“自动的”：后台交给 launchd（和第 2 步的服务器一样）；游戏客户端交给一个小程序 keeper，它听后台的话开关游戏，游戏崩了就重新拉起来。最后把总览里的 A–G 逐条过一遍。

为什么游戏不直接交给 launchd：游戏是有窗口的程序，而且你不希望它 24 小时开着（吃内存），只希望“我玩的时候它在”。所以需要一个懂“什么时候该开、什么时候该关”的小管家，这就是 keeper。

做完之后你能看到的：三台电脑重启后不用点任何东西就自己恢复；你平时只需要打开网页、点“上线”。

### 7.1 Mac mini：后台常驻

在 Mac mini 上，用 nano 新建 `~/companion/start-server.sh`：

```bash
#!/bin/zsh
TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
until "$TS" ip -4 2>/dev/null | grep -q '^100\.'; do sleep 2; done
cd ~/companion
exec /Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin/java -Xmx1G -jar server.jar --config config.yaml
```

和 2.5 的脚本是同一个套路：等 Tailscale、进目录、用 Java 21 启动后台，`-Xmx1G` 给它 1 GB 内存就够。

再做 `~/Library/LaunchAgents/com.companion.server.plist`：内容和 2.5 的那个一模一样，只改三处——`Label` 改成 `com.companion.server`，`ProgramArguments` 里的路径改成 `/Users/<你>/companion/start-server.sh`，两个日志路径改成 `/Users/<你>/companion/logs/server.log` 和 `/Users/<你>/companion/logs/server.err`。然后：

```bash
$ chmod +x ~/companion/start-server.sh
$ launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.companion.server.plist
$ tail -f ~/companion/logs/server.log
```

以后更新后台就是三步：MacBook 上打包（5.7 的第 1 行）、`scp` 拷过去（5.7 的第 2 行）、Mac mini 上 `launchctl kickstart -k gui/$(id -u)/com.companion.server` 重启。

**世界存档备份，强烈建议**。让 AI 助手写一个脚本 `~/mcserver/backup.sh`，把下面这句原样给它：“写一个 zsh 脚本：先用 mcrcon 发 save-off 和 save-all，然后 `tar czf ~/mcserver/backups/world-$(date +%F).tgz -C ~/mcserver world`，再发 save-on，最后删掉 14 天前的包；再配一个 launchd plist `com.companion.worldbackup`，用 StartCalendarInterval 每天 4 点 30 分跑一次，不要 KeepAlive。”这段话的意思：备份前让服务器暂停写盘并把内存里的世界写全，打包，恢复写盘，清理旧备份；每天凌晨自动跑。

### 7.2 MacBook：keeper 和游戏客户端的自动拉起

先让助手写 keeper（任务单 8）：

```text
任务：在 keeper/ 写一个 Java 21 小程序，跑在 MacBook，负责开关伙伴的游戏客户端。
1. 连接后台 /ws/keeper（地址、token 来自 keeper.yaml），断线退避重连。
2. 收到 start：执行
   caffeinate -ims "/Applications/Prism Launcher.app/Contents/MacOS/prismlauncher" --launch companion-vanilla-1.20.1 --profile "<伙伴玩家名>" --server mini:25565
   （命令行写在 keeper.yaml 里）。注意这条命令本身可能立刻退出（Prism 已开时会转发请求），所以“游戏是否在跑”不看这条命令的进程，
   而是每 10 秒 pgrep -f companion-vanilla-1.20.1 找游戏的 Java 进程。
3. 收到 stop：对那个 Java 进程发 SIGTERM，15 秒没退就 SIGKILL。
4. 期望状态是“应该在跑”而进程不在：重新执行 start；10 分钟内最多 3 次，超过就停止尝试并报告后台“拉不起来”。
5. 每 10 秒向后台报一次状态（进程在/不在、最近一次启动时间、重启次数）。后台连不上时保持最后的期望状态不变。
6. 后台那边：网页加“叫它上线 / 让它下线”两个按钮，对应发 start/stop 给 keeper；状态条显示 keeper 报的状态。
   可选：后台每 30 秒用 RCON 发 list，主人在线就自动 start，离线 10 分钟后自动 stop；网页上一个开关控制，默认关。
验收：网页点“上线”，30 秒内伙伴进服；在 MacBook 上强制退出游戏，1 分钟内它自己回来；点“下线”，游戏关闭且不再被拉起。
```

这张单里你要知道的：`caffeinate -ims` 是 macOS 自带的“这个程序跑着的时候不要睡”；“10 分钟最多 3 次”是防止游戏因为某个持续性问题一直崩、keeper 一直拉，把 MacBook 拖死。

keeper 用 launchd 常驻。用 nano 新建 `~/Library/LaunchAgents/com.companion.keeper.plist`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.companion.keeper</string>
  <key>ProgramArguments</key><array>
    <string>/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin/java</string>
    <string>-jar</string><string>/Users/<你>/companion/keeper.jar</string>
    <string>--config</string><string>/Users/<你>/companion/keeper.yaml</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ThrottleInterval</key><integer>10</integer>
  <key>LimitLoadToSessionType</key><string>Aqua</string>
  <key>StandardOutPath</key><string>/Users/<你>/companion/logs/keeper.log</string>
  <key>StandardErrorPath</key><string>/Users/<你>/companion/logs/keeper.err</string>
</dict></plist>
```

和前面的 plist 有两处不同：`ProgramArguments` 直接写了 Java 命令和参数，一项一行，不经过脚本（keeper 自己会等后台，不用等 Tailscale）；`LimitLoadToSessionType=Aqua` 的意思是“只在有桌面登录的会话里跑”，因为它要开一个有窗口的游戏。所以 MacBook 必须是登录状态（锁屏没关系，注销了就不行）。keeper 的 jar 和 `keeper.yaml`（后台地址 `ws://mini:8787/ws/keeper`、token、启动命令行）放在 `~/companion/` 下。加载：

```bash
$ launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.companion.keeper.plist
$ tail -5 ~/companion/logs/keeper.log
```

现在的全貌：MacBook 上常驻两个——`com.companion.ollama`（第 4 步）和 `com.companion.keeper`；Mac mini 上常驻两个——`com.companion.mcserver`（第 2 步）和 `com.companion.server`。游戏客户端本身不交给 launchd，由 keeper 管。

### 7.3 逐条验收

| 编号 | 怎么做 | 已在哪一步验过 |
| --- | --- | --- |
| A 聊 20 轮 | 重做一遍，这次是在它在线跟随你的时候聊 | 5.8 |
| B 独立玩家 | 网页点“上线”，Tab 列表里出现它 | 3.7、7.2 |
| C 五个动作 | 6.5 的表再跑一遍，这次全部通过 launchd 拉起的进程，不手动启动任何东西 | 6.5 |
| D 跟随中闲聊 | 跟随中连聊 5 轮 | 6.6 |
| E 卡住 | 栅栏测试 | 6.5 |
| F 第二天 | 隔天问“昨天我们干了什么”，它说出的内容要包含游戏里的事（不只是聊天） | 新 |
| G 重启 | 依次重启 Mac mini、MacBook、Windows，每次重启后只允许登录，不点别的；5 分钟内网页能开、点“上线”它能进服 | 新 |

G 还要加一条：让整套东西连续开 24 小时，第二天看四个日志文件的大小、两台 Mac 的内存占用有没有比第一天明显增长。有就把数字贴给助手查，这叫“内存泄漏”，早发现好修。

### 7.4 最后记下的数据

这些数字是以后加语音、换模型时的对照基线，全部写进小本子：

- 聊天首字响应时间（平均和最慢）
- “跟我来”从发出到它开始移动的秒数
- 每个动作的 10 次成功率
- Mac mini 空载 / 共玩时的内存；MacBook 模型 + 游戏同时跑时的内存压力颜色
- `tailscale status` 里三台机器是 direct 还是 relay

### 7.5 以后每次玩的流程

打开 Windows → 浏览器打开那个 https 地址 → 点“上线”→ 进游戏 → 玩 → 结束时点“下线”（或让自动下线管）。MacBook 只需要接电、开盖、登录着。

### 7.6 做完之后

先玩两周，什么都不加。这两周里改的只有 `persona.md`（它说话哪里不像就改哪里）和小本子里的数字。然后按这个顺序往下走，每一项都是《详细设计与实施手册》里已经设计好的：

1. **语音（耳机 + 按键说话）**。先做“听”：Windows 上的全局热键小程序 + MacBook 上的本地识别服务，识别出的文字走现在同一条 `/ws/ui`，后台一行不改。再做“说”（本地合成）。网页已经是 HTTPS，麦克风权限不会卡。
2. **换脑子：ChatGPT 订阅路径**。在 Gateway 里加第二个 ModelClient（Sign in with ChatGPT 的开源直连流程，只调 `/v1/responses`），Ollama 降为备用。动手那天先重新打开 OpenAI 的 SIWC 文档和条款页核对一遍，设计方案里的结论是 2026-10-15 的，预览期产品会变。
3. **更多动作**。按手册的路线图加，每次加一个，每个都走 6.5 那样的 10 次验收。

## 出问题时查这里

先记住三个万能命令，九成的问题它们能定位：

- `launchctl print gui/$(id -u)/<Label>`：看一个常驻服务的状态和最近退出码。`state = running` 是在跑；`last exit code` 不是 0 说明它崩过。
- `tail -50 <日志文件>`：看它最后说了什么。报错几乎总在最后几十行。
- `tailscale status`：看三台机器是否都在。

把报错原文贴给 AI 编程助手时，连同“我在做第几步、前一步的确认是否通过”一起说。

| 症状 | 先看什么 | 常见原因和处理 |
| --- | --- | --- |
| Windows 连不上 `mini` | `tailscale status` 里 mini 是否在线；Mac mini 上 `tail ~/mcserver/logs/latest.log` | 日志有 Failed to bind → Tailscale 地址还没就绪就启动了，检查 start.sh 第三行；`server-ip` 写错；Windows 的 Tailscale 没连 |
| 开机后服务器 / 后台没起来 | `launchctl print gui/$(id -u)/com.companion.mcserver`；`logs/launchd.err` | 自动登录没开（LaunchAgent 要登录后才跑）；脚本没加执行权限（`chmod +x`）；plist 路径里的 `<你>` 没换 |
| `launchctl bootstrap` 报 Input/output error | — | 已经加载过了：先 `launchctl bootout gui/$(id -u)/<Label>` 再 bootstrap；或 plist 格式错，`plutil -lint <plist>` 检查 |
| 伙伴进服被拒 | 游戏窗口里的拒绝原因 | “not white-listed”→ 白名单里的名字和 Prism 离线账号不一致（大小写也算）；“Failed to verify username”→ `online-mode` 没改成 false 或改完没重启 |
| Prism 不让添加离线账号 | 账户列表 | 先添加一个拥有 Minecraft 的 Microsoft 账号 |
| 游戏窗口一切走就暂停 | `options.txt` | `pauseOnLostFocus:false` 没生效（要在游戏关着时改） |
| `#goto` 没反应 | 实例的 `minecraft/mods/`；`latest.log` 搜 baritone | 放成了 standalone 版或别的游戏版本的 jar；Forge 没装上（主菜单左下角没有 Forge 字样） |
| Mac mini 上 curl 11434 连不通 | MacBook 上 `tail ~/companion/logs/ollama.err` | 日志里 Listening 的地址是 127.0.0.1 → 脚本没生效或还有另一个 Ollama 在跑（`pgrep -fl ollama`，多余的杀掉）；ACL 没有 11434 那行；MacBook 合盖睡了 |
| 模型只回话不调工具 | 4.5 的第二条命令 | 模型不支持 tools → 换；支持但不稳 → 在 persona.md 里加一句“玩家要你做动作时必须调用工具”，并把工具描述写得更具体 |
| 回复很慢（首字超过 15 秒） | MacBook 上 `ollama ps`；活动监视器内存压力 | 模型每次重新加载 → `OLLAMA_KEEP_ALIVE` 没生效；内存压力黄/红 → 换小模型；上下文填得太满 → 把 config 里的对话条数 N 调小 |
| https 网页打不开 | Mac mini 上 `tailscale serve status`；`curl -s http://127.0.0.1:8787` | 后台没跑；HTTPS Certificates 没在管理页打开；第一次签证书要等几十秒；Windows 没开 Tailscale |
| 网页显示离线但游戏在跑 | 后台 `server.log`；游戏 `latest.log` 搜 `[companion]` | token 不一致；模组配置里 hub 地址写成了 https（要 `ws://mini:8787`）；ACL 缺 `tag:workstation → 8787` |
| 跟随时它挖洞、搭路 | `latest.log` 里模组打印的 Baritone 设置值 | allowBreak/allowPlace 没在进入世界时写死，或被 Baritone 自己的设置文件覆盖了 |
| 交付时你脚下没有东西 | 服务器 `latest.log`；伙伴背包是否真的少了 | 用了只在本地画假物品的 drop 方法，按 6.4 第 6 条改；或它自己又捡回去了（没后退） |
| 卡住后一直重试 | 后台日志里同一任务的 progress 条数 | stuck 检测没生效；或后台收到 stuck 后模型又发了一次命令 → 结果后的“说一句话”请求带了动作工具，按 5.5 第 3 条改 |
| 死了之后不听指挥 | 网页状态条生命值 | 重生后没有重新同步快照，按 6.3 第 3 条改 |
| 重启 MacBook 后游戏没被拉起 | `~/companion/logs/keeper.log` | 没登录桌面；keeper 里的期望状态是“不应该在跑”（网页点一次“上线”）；10 分钟 3 次到顶了 |
| `tailscale status` 显示 relay | 两台机器是否同一个路由器 | 路由器关了 UPnP；Mac 防火墙开了“阻止所有传入连接”；手机热点之类的网络天生只能 relay |
| 日志里出现了聊天原文 | grep 你说过的一句话 | 这是缺陷，贴给助手按 5.2 的约束改 |

## 术语表

按第一次出现的顺序。读到一个词想不起来是什么，回这里查。

| 词 | 意思 |
| --- | --- |
| 终端 | Mac 自带的应用，用打字指挥电脑。手册里以 `$` 开头的行都在它里输入 |
| Homebrew / brew | Mac 上的软件包管理器，`brew install xxx` 一句装软件 |
| nano | 终端里的简单文本编辑器；Control + O 回车保存，Control + X 退出 |
| `~` | 你的个人文件夹 `/Users/<你>` |
| sudo | 用管理员身份执行，要开机密码 |
| Tailscale / tailnet | 给你建一个私有网的软件 / 那个私有网本身。每台机器有一个 `100.x.x.x` 地址和一个名字 |
| MagicDNS | Tailscale 的功能，让 `mini` 这种名字能当地址用 |
| 端口 | 一台电脑上多个服务的“门牌号”。25565 游戏、8787 后台、11434 Ollama、443 HTTPS、22 SSH、25575 RCON |
| 标签（tag）/ grants | Tailscale 里给机器分类的方式 / 谁能连谁的哪个端口的规则 |
| SSH | 在一台电脑的终端里操作另一台电脑；`ssh <你>@mini` |
| scp | 通过 SSH 拷文件到另一台电脑 |
| 专用服务器 | 没有画面、只负责“开着世界”的 Minecraft 程序；你和伙伴都用客户端连它 |
| Java 17 / 21 | 跑 Java 程序需要的运行环境；Minecraft 1.20.1 和模组用 17，后台和 keeper 用 21，两个同时装 |
| 离线账号 / online-mode | 只有名字、没买过游戏的账号 / 服务器是否向 Mojang 核验登录者 |
| 白名单 | 服务器只让这些名字进 |
| RCON | 给服务器发命令的远程控制口；mcrcon 是客户端 |
| launchd / plist / Label | macOS 的“开机自启 + 挂了自动重启”机制 / 它的配置文件 / 配置里那个服务的名字 |
| pmset / caffeinate | 电源管理设置 / “这个程序跑着时不要睡” |
| Forge | 给 Minecraft 加模组的框架；伙伴的客户端装，服务器不装 |
| Prism Launcher / 实例 | 开源启动器 / 它里面一套独立的游戏版本 + 模组 + 设置 |
| Baritone | 现成的寻路模组，负责“怎么走到那”；必须用 api 版 jar |
| 控制模组 | 我们自己写的模组，负责“看周围”和“按命令动”；三部分 link / observer / executor |
| MDK / ForgeGradle | Forge 给模组作者的项目模板 / 它自带的构建工具 |
| Gradle / `./gradlew` | Java 的编译打包工具 / 仓库自带的启动它的命令，不用另装 |
| Ollama | 在本机跑大模型的程序，对外提供和 OpenAI 一样的接口 |
| 工具调用 | 模型不回文字而是回“请调用某个工具”的能力；伙伴听懂命令全靠它 |
| 上下文长度 | 模型一次能看多少字 |
| 后台 / Hub / Brain / Gateway / Memory | 我们自己写的中枢程序 / 它的四个部分：管连接、管思考、管和模型说话、管记忆 |
| WebSocket / token | 长连接、双向发消息的方式 / 当口令用的长随机字符串 |
| 假执行器（mock-executor） | 假装是游戏客户端的小程序，用来在不开游戏的情况下测后台 |
| 快照（snapshot）/ 事件（event） | 模组定时报的“我看到什么” / 有事发生时立刻报的“发生了什么” |
| ack / progress / result | 模组对一条命令的三次回复：收到了 / 做到哪了 / 做完了（成功或原因码） |
| 原因码 | 失败时的固定理由，比如 stuck（卡住）、target\_lost（找不到你） |
| keeper | MacBook 上负责开关游戏客户端的小程序 |
| persona.md | 伙伴的人设文件，你自己写，改它不用改代码 |
| SQLite | 单文件的小数据库，记忆存在这里 |

## 资料来源

本手册里的命令、版本号和配置项名称依据下面这些页面。版本号以你打开页面当天为准。

- 组网：[Tailscale macOS 三种安装变体](https://tailscale.com/docs/concepts/macos-variants)、[CLI 参考](https://tailscale.com/docs/reference/tailscale-cli?tab=macos)、[Grants 访问规则](https://tailscale.com/docs/features/access-control/grants)、[Grant 示例](https://tailscale.com/docs/reference/examples/grants)、[MagicDNS](https://tailscale.com/docs/features/magicdns)、[direct / relay 连接类型](https://tailscale.com/docs/reference/connection-types)、[Homebrew cask tailscale-app](https://formulae.brew.sh/cask/tailscale-app)
- 服务器：[Mojang 1.20.1 发布文章（含 server.jar 链接）](https://www.minecraft.net/en-us/article/minecraft--java-edition-1-20-1)、[minecraft.wiki：server.properties](https://minecraft.wiki/w/Server.properties)、[PaperMC：Aikar 参数](https://docs.papermc.io/paper/aikars-flags/)、[Homebrew openjdk@17](https://formulae.brew.sh/formula/openjdk@17)、[Homebrew openjdk@21](https://formulae.brew.sh/formula/openjdk@21)
- 客户端：[Prism Launcher 命令行接口](https://prismlauncher.org/wiki/getting-started/command-line-interface)、[Prism 发布页](https://github.com/PrismLauncher/PrismLauncher/releases)、[Prism 离线账号前置条件（源码）](https://github.com/PrismLauncher/PrismLauncher/blob/develop/launcher/ui/pages/global/AccountListPage.cpp)、[Homebrew cask prismlauncher](https://formulae.brew.sh/cask/prismlauncher)、[minecraft.wiki：options.txt](https://minecraft.wiki/w/Options.txt)
- Baritone 与 Forge：[Baritone v1.10.5 发布页](https://github.com/cabaletta/baritone/releases/tag/v1.10.5)、[Baritone SETUP.md（api 与 standalone 的区别）](https://github.com/cabaletta/baritone/blob/master/SETUP.md)、[IBaritone Javadoc](https://baritone.leijurv.com/baritone/api/IBaritone.html)、[IFollowProcess](https://baritone.leijurv.com/baritone/api/process/IFollowProcess.html)、[IPathingBehavior](https://baritone.leijurv.com/baritone/api/behavior/IPathingBehavior.html)、[Forge 1.20.1 下载页](https://files.minecraftforge.net/net/minecraftforge/forge/index_1.20.1.html)、[Forge 1.20.1 入门](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/)
- 本地大模型：[Ollama 发布页](https://github.com/ollama/ollama/releases)、[v0.40.0 说明（MLX 默认）](https://github.com/ollama/ollama/releases/tag/v0.40.0)、[OpenAI 兼容接口](https://docs.ollama.com/api/openai-compatibility)、[工具调用](https://docs.ollama.com/capabilities/tool-calling)、[上下文长度](https://docs.ollama.com/context-length)、[FAQ（OLLAMA\_HOST、keep\_alive）](https://docs.ollama.com/faq)、[macOS 说明](https://docs.ollama.com/macos)、[带 tools 标签的模型](https://ollama.com/search?c=tools)、[ollama qwen3.8 标签](https://ollama.com/library/qwen3.8/tags)、[ollama qwen3.6 标签](https://ollama.com/library/qwen3.6/tags)、[ollama gemma4](https://ollama.com/library/gemma4)、[Homebrew ollama](https://formulae.brew.sh/formula/ollama)
- macOS 常驻：[Apple：Creating launchd Jobs](https://developer.apple.com/library/archive/documentation/MacOSX/Conceptual/BPSystemStartup/Chapters/CreatinglaunchdJobs.html)、[launchd.plist(5)](https://leancrew.com/all-this/man/man5/launchd.plist.html)、[launchctl(1)](https://leancrew.com/all-this/man/man1/launchctl.html)、[pmset 手册](https://www.sudo.is/docs/macos/man_pmset.html)、[caffeinate 手册](https://www.sudo.is/docs/macos/man_caffeinate.html)、[Apple：设置 Mac 的睡眠与唤醒](https://support.apple.com/guide/mac-help/set-sleep-and-wake-settings-mchle41a6ccd/mac)、[Homebrew 安装说明](https://brew.sh/)

标注说明：具体的命令行参数、版本号、配置项名称来自上面的官方页面；任务单里对模组和后台行为的规定是工程推断和产品建议，没有在你的机器上实测过；所有“几秒内”“多少 GB”是验收阈值或页面标注，不是测量结果。底层设计（消息格式、状态机、表结构）在《详细设计与实施手册》，整体思路在《设计方案》，这两份是给 AI 编程助手看的，你不必读。
