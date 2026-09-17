# 卡片守护 / RecentsKeeper

在 ColorOS / realme UI 上按应用决定：**最近任务里划掉卡片、或点一键清理之后，进程要不要被结束**。

LSPosed 模块。已在 ColorOS 16（Android 16）上实测。

## 它做什么

在最近任务里划掉一张卡片时，ColorOS 会先移除任务，再决定是否结束该应用的进程；系统自带一份白名单决定这件事，用户改不了。本模块在判定处做运行期 Hook，让你按应用选择：

| 选择 | 结果 |
|---|---|
| 未勾选 | 完全维持系统原本的行为 |
| 勾选保护 | 划卡 / 一键清理后进程继续运行，**卡片本身照常消失** |

界面里还有一个「反向模式」，勾上后改为保护**未**勾选的应用。

## 安装

先把 `RecentsKeeper.apk` 装到设备上，然后：

- 在 LSPosed 中启用本模块。**作用域里必须出现「系统框架」**（LSPosed 中标识为 `system`）——核心判定位于 system_server，仅勾选应用不会生效；建议把 `com.android.launcher`（桌面）也勾上，否则卡片不会消失。
- 重启设备：system_server 只在开机时被注入。
- 授予一次「修改系统设置」权限，否则配置写不进系统设置：

```bash
adb shell appops set io.github.recentskeeper WRITE_SETTINGS allow
```

也可以在「设置 → 应用 → 卡片守护 → 权限」里打开对应开关。

## 使用

打开「卡片守护」，勾选要保留后台的应用，点保存。配置**即时生效、无需重启**——最多 3 秒后被读取。

界面里的「系统层抑制」默认开启，这是推荐模式（卡片正常消失）。取消勾选会退回较保守的拦截方式：进程同样保留，但卡片会在下次打开最近任务时重新出现。

## 工作原理

划卡到进程结束之间，ColorOS 上有**两条互不相干的链路**，任一放行都不够：

```
链路一（框架）
  桌面 → ActivityManagerWrapper.removeTask(taskId) → ATMS
       → ActivityTaskSupervisor.removeTask(Task, killProcess, removeFromRecents, reason)
       → cleanUpRemovedTask(...) 决定是否结束进程

链路二（清理器）
  桌面 → KillAppWrapper.forceStopTasks(Task[]) → OSense / Athena 请求
       → AMS.forceStopPackage(pkg, userId)
```

模块的拦截点：

| 位置 | 做法 |
|---|---|
| 桌面 `KillAppWrapper.forceStopTasks` | 把受保护应用从**待清理名单**里剔除，链路二从源头不成立 |
| 桌面 `ActivityManagerWrapper.removeTask` | 受保护任务由模块主动调用一次，让卡片真正消失 |
| system_server `ActivityTaskSupervisor.removeTask` | 仅当 `reason == "remove-task"` 且受保护时，把 `killProcess` 置 false |
| system_server `ActivityTaskSupervisor.cleanUpRemovedTask` | 兜底同上。反汇编确认「移除任务记录」发生在该判断之前，所以卡片照常消失、进程得以保留 |
| system_server `AMS.forceStopPackage` | **按调用方 uid 拦截**，只拦清理器组件发起的调用 |

两条链路都在 system_server 内被抑制后，桌面侧才敢主动移除任务——而这个动作本身会触发链路一。因此模块用一道**心跳门控**兜底：system_server 的 Hook 装好后，会通过 `Settings.System` 持续写入一个带开机时间戳的心跳；桌面侧只在心跳新鲜时才移除任务，否则宁可把卡片留在原地。任何异常情况下最坏的表现是「卡片划不掉」，而不是「应用被杀」。

## 权限与隐私

- **不申请 INTERNET 权限**，没有网络代码，不采集也不上传任何数据。
- 配置只存本机：一份在应用私有目录（界面记忆用），一份在 `Settings.System` 的 `rk_config` 键（供注入进程读取）。
- 一个需要说明的固有属性：`Settings.System` 对设备上任何应用可读，所以**别的应用可以看出你保护了哪些应用**；持有 `WRITE_SETTINGS` 的应用也能改写这个键。这是该通道本身的性质，不是本模块额外增加的暴露面。除此之外模块不需要任何权限。

## 出问题时

- **一键停用**（效果等同卸载，用于排查）：

  ```bash
  su -c setprop persist.recentskeeper.disable 1     # 之后重启，模块不安装任何 Hook
  su -c setprop persist.recentskeeper.disable ''    # 清除
  ```

- 日志：LSPosed 会把模块日志写在 `/data/adb/lspd/log/` 下，在其中查找 `RecentsKeeper`。正常应出现 `installed in com.android.launcher`、`system side ready`；划卡时出现 `dropped from forceStop list`。

## 已知限制

- 拦截集合中包含 uid 1000（清理器的部分组件跑在系统身份下），因此**由系统身份对受保护应用发起的 force-stop 也会被抑制**。设置里的「强行停止」与 `am force-stop` 不受影响（已实测），它们的调用方 uid 不在集合内。
- 系统应用无法通过名单控制：ColorOS 对系统应用走另外的分支。
- 最近任务里手动锁定过的卡片由系统自身机制保护，本模块不介入。
- 命中名单的应用同时也不会被清理器的后台内存回收处理——两者共用同一个判定入口。
- 只保证 ColorOS 16 上的行为。其它版本若类名或判定语义变化，模块会退化为「不改变系统行为」，并在日志里留下记录。

## 构建

需要 JDK（`javac` / `jar` / `keytool`）、Android SDK（build-tools + 任一 platform）与 Python 3：

```bash
./build.sh          # 产物：RecentsKeeper.apk
```

SDK 位置按 `ANDROID_HOME` → `ANDROID_SDK_ROOT` → `local.properties` 的顺序查找，可用 `BUILD_TOOLS`、`ANDROID_JAR`、`XPOSED_JAR` 覆盖。签名凭据从环境变量（`RK_KEYSTORE` / `RK_KEYSTORE_PASS` / `RK_KEY_ALIAS` / `RK_KEY_PASS`）或 `keystore.properties` 读取（见 `.example`，该文件不入库）；**没有凭据时会用一次性 debug 密钥签名**，保证克隆下来就能编译安装。

不需要 Gradle。

## 仓库结构

```
src/io/github/recentskeeper/   模块与界面源码（Java）
res/  assets/                  资源与 Xposed 入口声明
tools/                         逆向辅助脚本（见下）
libs/                          Xposed API 编译期 stub
build.sh  mkapk.py             构建脚本
```

`tools/` 里是四个独立的只读分析脚本，用来在设备上定位 Hook 点，可单独使用：

| 脚本 | 用途 |
|---|---|
| `dexparse.py` | 解析 dex 的类/方法符号表，并标出哪些方法带方法体 |
| `dexdump2sym.py` | 把 `dexdump` 的输出压平成一类一行的符号表 |
| `extract_class.py` | 从 `dexdump -d` 输出里抽出指定类的反汇编 |
| `findrefs.py` | 流式扫描反汇编，输出「哪个类的哪个方法引用了某符号」 |

## 许可

GPL-3.0，见 [LICENSE](LICENSE)。

```
Copyright (C) 2026 <your name>
```

---

## English summary

RecentsKeeper is an LSPosed module for ColorOS / realme UI that decides, per
app, whether swiping its card away in Recents (or tapping the one-key cleaner)
kills the process. The card still disappears as usual — only the process kill is
suppressed. The decision points live in `system_server`, so the module scope must
include the system framework, and the configuration is published through
`Settings.System`, which needs a one-time `WRITE_SETTINGS` grant. No network
permission, no telemetry. GPL-3.0.
