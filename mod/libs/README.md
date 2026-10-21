# mod/libs

放 `baritone-api-forge-1.10.5.jar`（从 https://github.com/cabaletta/baritone/releases/tag/v1.10.5 下载，必须是 **api + forge** 那个），
给模组编译用（compileOnly，不打进我们的 jar；运行时由 Prism 实例 mods 目录里的同一个 jar 提供）。
jar 文件被 .gitignore 排除，clone 之后要自己放一份。
