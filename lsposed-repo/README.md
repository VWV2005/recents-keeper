# 卡片守护 / RecentsKeeper

在 ColorOS / realme UI 上按应用决定：**最近任务里划掉卡片、或点一键清理之后，进程要不要被结束**。

卡片本身照常消失，只有「结束进程」这一步被抑制——也就是把系统的清理行为退回到「只是关掉卡片」。

LSPosed 模块。已在 ColorOS 16（Android 16）上实测。

---

## 安装

1. 安装 APK（见本仓库 Releases）。
2. 在 LSPosed 中启用本模块。**作用域里必须出现「系统框架」**（标识为 `system`），否则不生效；建议把桌面 `com.android.launcher` 一并勾上，卡片才会正常消失。
3. 重启设备（system_server 只在开机时被注入）。
4. 授予一次「修改系统设置」权限：

   ```bash
   adb shell appops set io.github.vwv2005.recentskeeper WRITE_SETTINGS allow
   ```

   也可以在「设置 → 应用 → 卡片守护 → 权限」里打开对应开关。
5. 打开「卡片守护」，勾选要保留后台的应用并保存。配置即时生效，无需重启（最多 3 秒后被读取）。

## 使用

界面上勾选要保留后台的应用，点保存即可。还有一个「反向模式」，勾上后改为保护**未**勾选的应用。

「系统层抑制」默认开启，这是推荐模式（卡片正常消失）。取消勾选会退回较保守的拦截方式：进程同样保留，但卡片会在下次打开最近任务时重新出现。

## 工作原理

划卡到进程结束之间，ColorOS 上有两条互不相干的链路，任一放行都不够：

```
链路一（框架）
  桌面 → ActivityManagerWrapper.removeTask(taskId) → ATMS
       → ActivityTaskSupervisor.removeTask(Task, killProcess, removeFromRecents, reason)
       → cleanUpRemovedTask(...) 决定是否结束进程

链路二（清理器）
  桌面 → KillAppWrapper.forceStopTasks(Task[]) → OSense / Athena 请求
       → AMS.forceStopPackage(pkg, userId)
```

模块在桌面进程剔除待清理名单、在 system_server 抑制 killProcess 标志，并按调用方 uid 拦截清理器发起的 force-stop。两条链路都被抑制后，桌面侧才主动移除任务让卡片正常消失；这个动作本身会触发链路一，所以用一道**心跳门控**兜底：system_server 的 Hook 装好后会持续写入一个带开机时间戳的心跳，桌面侧只在心跳新鲜时才移除任务。任何异常情况下最坏的表现是「卡片划不掉」，而不是「应用被杀」。

## 已知限制

- 拦截集合包含 uid 1000（清理器部分组件运行在系统身份下），因此由系统身份对受保护应用发起的 force-stop 也会被抑制。设置里的「强行停止」与 `am force-stop` 不受影响。
- 系统应用走另外的判定分支，无法通过名单控制。
- 最近任务里手动锁定过的卡片由系统自身机制保护。
- 命中名单的应用同时不会被清理器的后台内存回收处理（两者共用同一判定入口）。
- 只保证 ColorOS 16 上的行为；其它版本若类名或判定语义变化，模块会退化为「不改变系统行为」并在日志里记录。

## 权限与隐私

- **不申请 INTERNET 权限**，没有网络代码，不采集也不上传任何数据。
- 配置只存本机：一份在应用私有目录，一份在 `Settings.System` 的 `rk_config` 键（供注入进程读取）。`Settings.System` 对设备上任何应用可读，所以别的应用可以看出你保护了哪些应用——这是该通道本身的性质。

## 停用

```bash
su -c setprop persist.recentskeeper.disable 1    # 重启后模块不安装任何 Hook
su -c setprop persist.recentskeeper.disable ''
```

## 链接

- 源码、问题反馈与构建说明：<https://github.com/VWV2005/recents-keeper>
- 许可：GPL-3.0
