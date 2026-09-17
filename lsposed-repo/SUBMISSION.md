# 提交到 LSPosed 模块仓库

本目录里的 `SUMMARY` 和 `README.md` 是给 `Xposed-Modules-Repo` 那个仓库用的，**不是**给本项目源码仓库用的。提交时把它们原样放进模块仓库根目录。

官方要求（摘自 `Xposed-Modules-Repo/.github` 的说明）：

| 要求 | 我们的取值 |
| --- | --- |
| 仓库名 = 模块包名 | `io.github.vwv2005.recentskeeper` |
| 仓库 description = 模块名 | `卡片守护` |
| 至少一个有效 release | 见下 |
| release 的 tag 必须是 `VersionCode-VersionName` | **`2-1.1`** |
| release 至少一个 APK 资产 | `RecentsKeeper-1.1.apk` |
| 包名前缀须为你拥有的命名空间 | `io.github.vwv2005` = 你的 GitHub 用户名 ✓ |
| 仓库内容 | `SUMMARY` + `README.md`（**不要**放源码） |

## 步骤

### 1. 开 issue 申请仓库

`Xposed-Modules-Repo` 不接受 PR，而是通过 issue 申请，机器人会建仓库并邀请你作为 maintainer。用你自己的 GitHub 账号开一个 issue，标题与内容：

```
标题：[submission] io.github.vwv2005.recentskeeper

内容：
Package name: io.github.vwv2005.recentskeeper
Module name: 卡片守护 (RecentsKeeper)
Description: 在 ColorOS / realme UI 上按应用决定，最近任务划卡与一键清理后是否结束进程；
             卡片仍正常消失，只抑制杀进程这一步。已在 ColorOS 16 / Android 16 实测。
Source: https://github.com/VWV2005/recents-keeper
License: GPL-3.0
```

也可以走网页表单 <https://modules.lsposed.org/submission> 生成预填 issue（选择 "Submit a new package"）。

> 若 appId 用的是你自己拥有的域名（反向域名写法），官方要求给根域名加一条 TXT 记录
> `lsposed-modules-repo-verification=VWV2005` 以便快速通过。我们用 `io.github.{用户名}`
> 形式，不需要 TXT 记录。若是组织名，需要先把组织成员身份设为 public。

### 2. 等仓库创建

机器人会创建 `Xposed-Modules-Repo/io.github.vwv2005.recentskeeper` 并邀请你。接受邀请后，把本目录的
`SUMMARY` 和 `README.md` 提交进去，并把仓库 description 设为 `卡片守护`。

### 3. 发布 release

在该仓库新建 release：

- **Tag**：`2-1.1`（本项目的 versionCode 是 2、versionName 是 1.1）
- **Title**：`1.1`
- **Content**：更新日志，可直接用源码仓库 v1.1 的 release 说明
- **Asset**：`RecentsKeeper-1.1.apk`

> 官方说明：如果 tag 名写错，机器人会自动纠正；但**只改 APK 资产而不改 release 内容不会触发机器人**，
> 所以每次更新都新建一个带 APK 的 release。

### 4. 生效

提交后仓库更新会触发官方构建，约 5 分钟内在 LSPosed Manager 的「仓库」页出现。若仓库内容不完整（缺 `SUMMARY` 或 `README.md`）则不会展示。

## 发新版时的流程

1. 改 `AndroidManifest.xml` 的 `versionCode` / `versionName`。
2. 在源码仓库目录运行 `bash build.sh`（需本机的 `keystore.properties`，见桌面备份目录的说明）。
3. 源码仓库发新 release，同时在 `Xposed-Modules-Repo/io.github.vwv2005.recentskeeper` 发一个 tag 为
   `<versionCode>-<versionName>` 的 release，附上 APK。

源码仓库的 tag 命名随意（我们现在用 `v1.1`）；模块仓库的 tag 必须符合 `<versionCode>-<versionName>`。
