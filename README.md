```
  ███╗   ██╗ ██████╗ ██╗██████╗
  ████╗  ██║██╔═══██╗██║██╔══██╗
  ██╔██╗ ██║██║   ██║██║██████╔╝
  ██║╚██╗██║██║   ██║██║██╔══██╗
  ██║ ╚████║╚██████╔╝██║██║  ██║
  ╚═╝  ╚═══╝ ╚═════╝ ╚═╝╚═╝  ╚═╝
```

# NOIR

[![build](https://github.com/Verlintas/NOIR/actions/workflows/build.yml/badge.svg)](https://github.com/Verlintas/NOIR/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Verlintas/NOIR)](https://github.com/Verlintas/NOIR/releases)
[![license](https://img.shields.io/github/license/Verlintas/NOIR)](LICENSE)
![platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Windows%20%7C%20Linux-2ea44f)
[![AtomGit](https://img.shields.io/badge/AtomGit-Verlintas%2FNOIR-2f81f7)](https://atomgit.com/Verlintas/NOIR)

**把「这台手机到底能泄露什么」变成一份可复现的报告。**

NOIR 是一个跑在电脑上的审计工具，通过 `adb` 读取已连接 Android 设备的应用清单、权限和 APK 里的网络端点，给出分类、风险评分和可执行的处置建议。它也可以在需要时调用 `jadx` 对单个应用做反编译和关键字检索。

> 定位：**审计与自我防护**。面向自己的设备、自己的应用、已授权的安全研究。

**官网 / Website:** https://verlintas.dev/noir/ · [中文](https://verlintas.dev/noir/zh/)

**AtomGit 镜像 / Mirror:** https://atomgit.com/Verlintas/NOIR

---

## 它做什么

1. **盘点**：列出所有「有网络权限 + 敏感权限」的应用，以及它们各自拿到了什么。
2. **抽取端点**：在设备端用 `unzip + strings` 从每个 APK 的 dex 里提取 URL，只回传几 KB，不把整包拉回电脑。
3. **分类与打分**：按包名和域名识别厂商（Xiaomi / Google / 腾讯 / 高德 …）与类别（遥测、广告、定位、输入法 …），结合权限和上报端点计算风险分。
4. **数据关联**：把权限翻译成"能拿到什么数据"（位置/通讯录/短信/通话/麦克风/相机/屏幕/应用列表/设备标识/文件/日历/账号），再和上报域名配对，得出"哪类数据可能流向哪里"。
5. **共享 UID**：识别 `android.uid.system` 这类共享用户，列出哪些包实际上共享同一套权限并集。
6. **清单层**：解析 `AndroidManifest.xml` 二进制 AXML，找出没有权限保护的导出组件、明文流量、备份、debuggable、targetSdk。
4. **给建议**：区分「可安全自动执行」「需人工确认」「不建议动」，并为每条动作生成回滚命令。
5. **必要时深挖**：`deep` 会把单个 APK 拉到本地，用 `jadx` 反编译后检索 `Runtime.exec`、`setApplicationEnabledSetting`、`DexClassLoader`、`MediaProjection` 等高风险调用。
6. **识别 ROM**：从系统属性判断 ColorOS / HyperOS / MIUI / OriginOS / HarmonyOS / MagicOS / One UI，规则和报告都按 ROM 分支。
7. **执行后复核**：不信任退出码，改完再读一次设备当前状态，分「命令级」和「复扫级」两层给出是否真的生效。
8. **去广告**：`adblock` 按 ROM 找出系统里负责广告的组件，分档确认后 `pm disable-user` 停用，随时可恢复。

## 它不做什么

- 不联网、不上传任何数据、不需要账号。
- 不修改系统分区，不需要 root。
- 不会自动卸载任何东西，除非你显式加 `--aggressive`。
- 不会把「别碰」一档的组件（更新 / 支付 / 激活 / 推送底层）交给任何命令，
  `adblock` 对这一档只展示、不执行。

---

## 第一次运行

```bash
noir
```

不带参数会进入向导：

1. 检查 `adb`，没有就**问你要不要自动下载**（`~/.noir/tools`）
2. 检查设备，没有就打印**开发者模式 / USB 调试的开通步骤**，然后**等你插线**（最多 180 秒）
3. 问一句「开始扫描?」，扫完直接出仪表盘
4. 问一句「执行这 19 条安全处置?」，`y` 就执行，并写好回滚文件

全程只会问两三个 y/n，其余都是自动的。

## 环境自检

```bash
noir doctor
```

```
NOIR doctor
  platform  Mac OS X aarch64
  java      17.0.19
  adb       /opt/homebrew/.../platform-tools/adb
            Version 37.0.1-15733141
  device    178b0d2f  device  2509FPN0BC
  jadx      /opt/homebrew/bin/jadx
```

```bash
noir setup          # 缺什么下什么
noir setup --yes    # 不询问，适合脚本
```

## 出现问题时

所有错误都会带「怎么办」，而不是抛异常栈：

```
╭─ ERROR ──────────────────────────────────────────────────────────╮
│ 指定的 adb 不存在：/nonexistent/adb                              │
├──────────────────────────────────────────────────────────────────┤
│ 检查路径，或运行 noir setup 自动安装。                           │
╰──────────────────────────────────────────────────────────────────╯
```

没有设备时给出的是可执行步骤：

```
╭─ 没有检测到设备 ───────────────────────────────────────────────────────────╮
│   1. 打开开发者模式                                                        │
│     小米 / HyperOS：设置 → 我的设备 → 全部参数 → 连点「MIUI 版本」7 次     │
│     OPPO / 一加 / 真我：设置 → 关于本机 → 版本信息 → 连点「版本号」7 次    │
│     原生 Android：设置 → 关于手机 → 连点「版本号」7 次                     │
│                                                                            │
│   2. 开启 USB 调试                                                         │
│     设置 → 系统 → 开发者选项 → USB 调试                                    │
│     ColorOS：设置 → 系统设置 → 开发者选项（要先登录 OPPO 账号）            │
│     Xiaomi 额外开启：USB 调试（安全设置）                                  │
│                                                                            │
│   3. 插线后手机会弹「允许 USB 调试吗？」                                   │
│     勾选「一律允许使用这台计算机进行调试」→ 允许                           │
│                                                                            │
│   4. 还不行就换一根数据线（很多线只能充电）                                │
╰────────────────────────────────────────────────────────────────────────────╯
```

已连接但没授权时，会单独提示「撤销 USB 调试授权后重插」。

## 支持平台

| 系统 | 状态 |
|---|---|
| macOS (Intel / Apple Silicon) | ✅ |
| Windows 10 / 11 | ✅ |
| Debian / Ubuntu | ✅ |

依赖：

- **JDK 17+**
- **Android platform-tools**（`adb`）
- **jadx**（仅 `deep` 命令需要）

程序会自动在常见安装位置查找 `adb` 和 `jadx`；找不到时可以用 `--adb` / `--jadx` 指定。

```bash
# macOS
brew install --cask android-platform-tools jadx

# Debian / Ubuntu
sudo apt install adb
# jadx: https://github.com/skylot/jadx/releases

# Windows (scoop)
scoop install adb jadx
```

---

## 安装

**方式一：下载即用（推荐）**

到 [Releases](https://github.com/Verlintas/NOIR/releases) 下载对应平台的 portable 包：

```
noir-v0.1.1-macOS-portable.zip
noir-v0.1.1-Windows-portable.zip
noir-v0.1.1-Linux-portable.zip
SHA256SUMS.txt
```

解压后**双击启动器**即可，包内自带运行时，**不需要安装 Java**：

| 系统 | 双击这个 | 如果被拦 |
|---|---|---|
| macOS | `Start NOIR.command` | 右键 → 打开；或 `xattr -dr com.apple.quarantine "Start NOIR.command" NOIR.app` |
| Windows | `Start NOIR.bat` | SmartScreen → 更多信息 → 仍要运行（批处理会切到 UTF-8 并调用包内 JRE） |
| Linux | `Start-NOIR.sh` | 先 `chmod +x Start-NOIR.sh`，或在终端执行 |

启动器会打开一个终端窗口跑完向导，并在结束时停住（不会一闪而过）。

`adb` 和 `jadx` 不在包里——首次运行会询问是否自动下载到 `~/.noir/tools`。

**方式二：从源码构建**

```bash
git clone https://github.com/Verlintas/NOIR
cd NOIR
./gradlew :cli:installDist
./cli/build/install/noir/bin/noir --about
```

## 构建

```bash
git clone <repo> && cd NOIR
./gradlew :cli:installDist
```

产物：

```
cli/build/install/noir/bin/noir        # macOS / Linux
cli/build/install/noir/bin/noir.bat    # Windows
```

也可以直接：

```bash
./gradlew :cli:run --args="devices"
```

---

## 界面

![NOIR dashboard on a Xiaomi 17 Pro Max](docs/screenshot.png)

真机输出（macOS Terminal）：渐变 logo、CJK 对齐的表格、按风险着色的分数、
每处理完一个 APK 就刷新一次的进度条。

`noir ui` 会把结果渲染成一块等宽仪表盘；颜色和边框都会按终端能力自动降级：

- `--ascii`：纯 ASCII 边框，给旧版 Windows 控制台
- `--no-color` / `--color`：关闭或强制 ANSI 颜色（默认按是否为 TTY 自动判断）
- `--quiet`：只留仪表盘，不打印扫描过程
- `--no-clear`：不清屏（方便把输出重定向到文件）

## 用法

```bash
noir --about              # 它是什么、会写什么、许可
noir --version
noir devices
noir scan                 # 全量扫描，输出 JSON / Markdown / 域名清单
noir ui                   # 扫描并渲染仪表盘
noir auto                 # 扫描 + 分类 + 生成处置计划（dry-run）
noir auto --apply         # 执行安全的 appops 动作（可回滚）
noir auto --apply --aggressive   # 额外卸载被标记的用户应用
noir deep com.example.app # 拉包 + jadx + 关键字检索
noir block com.example.app        # 打印处置命令
noir block com.example.app --apply
noir adblock                 # 找出系统广告组件（dry-run）
noir adblock --apply         # 逐条确认后停用，全部可恢复
noir adblock --revert        # 恢复之前停用的组件
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--serial <id>` | 指定设备 |
| `--out <dir>` | 输出目录（默认 `noir-out`） |
| `--min <score>` | 只保留分数 ≥ N 的应用 |
| `--limit <n>` | 最多输出 N 条 |
| `--only <regex>` | 只扫描匹配的包名，例如 `--only 'com\.miui\.'` |
| `--adb <path>` / `--jadx <path>` | 手动指定工具路径 |
| `--yes` | 自动化场景下跳过确认 |

## DeepScan：一条命令反编译 + 处置

```bash
noir deepscan                  # 反编译风险最高的 15 个应用
noir deepscan --top 30         # 更多
noir deepscan --all            # 全部被标记应用（慢，占磁盘）
noir deepscan --jobs 4         # 并行 jadx 数量
```

流程是：扫描 → 按风险排序 → 逐个 `adb pull` + `jadx` → 统计代码级信号
（`Runtime.exec`、`DexClassLoader`、`setApplicationEnabledSetting`、
`enabled_accessibility_services`、`MediaProjection`、`SystemProperties.set`、
短信/设备标识、WebView 桥、无障碍/通知监听/设备管理员 …）→ 解析清单
（导出组件、明文流量、备份、debuggable）→ 识别 SDK（Umeng/Firebase/AppsFlyer…）
→ 输出 `deepscan.md` / `deepscan.tsv` → 询问是否清理。

```bash
noir deepscan --apply          # 分析后执行安全 appops
noir deepscan --apply --purge  # 再逐个确认卸载高风险用户应用
```

反编译结果按 `包名 + APK 路径` 缓存，第二次跑同一个应用会直接复用。

## 去广告：`noir adblock`

系统广告不在某个 App 里，而在 ROM 自带的组件里（商店推荐位、快应用、资讯流、
OAID 设备标识、营销推送通道）。`noir adblock` 扫描设备后按 ROM 给出这些组件，
并区分三档：

| 档位 | 含义 | NOIR 会不会执行 |
|---|---|---|
| 可停用 | 关掉只损失推荐位，不损失功能 | 会（确认后） |
| 谨慎 | 带广告，但也带着你会用到的功能 | 会（确认后，逐条判断） |
| 别碰 | 更新 / 支付 / 激活 / 推送底层 | 不执行，只用来说明链路 |

```bash
noir adblock                 # 扫描 + 分级 + 导出计划
noir adblock --apply         # 逐条确认，执行 pm disable-user
noir adblock --apply --yes   # 全部执行（自动化）
noir adblock --revert        # 按 adblock-rollback.txt 恢复
```

执行方式是 `pm disable-user`，不是卸载：应用仍在分区里，随时 `pm enable` 恢复，
OTA 也不会因为缺包而报错。**这套动作不进 `auto --apply`**——`auto --apply` 的语义
保持为「只改可回滚的 AppOps」，去广告需要你显式地运行 `adblock`。

清单来源要说清楚：ColorOS / MIUI 的表格来自公开的机型精简笔记，NOIR 没有在真机
上逐项验证，所以它们只作为**建议**出现；同时设备上的每个包都会被包名模式再匹配一遍
（结论标为「推断」），清单漏项不会导致漏报。

`adblock-domains.txt` 只导出这些组件实际上报的域名，NOIR 不改 DNS。

## 执行后检测

`--apply` 的退出码不等于生效。OPPO / vivo 一类 ROM 会让 `cmd appops set` 返回 0
但模式纹丝不动，所以 NOIR 在两个层面读回设备状态：

- **命令级**：每条动作执行前后各读一次设备当前状态，比对期望值，结论直接打在终端上
- **复扫级**：整批执行完再统一读一遍（后一条命令可能撤销前一条），输出 `verify.txt`

结论只有四种：`已生效` / `未生效` / `被拒绝` / `无法确认`。只有标记 `已生效` 的动作
才写入 `rollback.txt`，避免回滚文件里躺着一堆没真正改过的状态。
`noir block --apply` 与 `noir adblock --apply` 走同一套检测。

## ROM 适配

`noir scan` / `noir ui` 会识别厂商定制系统，结论写进报告的 `rom` 字段：

| ROM | 识别属性 | 品牌覆盖 |
|---|---|---|
| ColorOS | `ro.build.version.opporom` / `oplusrom` / `realmeui` | OPPO / 一加 / 真我 |
| HyperOS · MIUI | `ro.miui.ui.version.name` + `ro.build.version.full` | 小米 / Redmi / POCO |
| OriginOS | `ro.vivo.os.version` | vivo / iQOO |
| HarmonyOS · EMUI | `ro.build.version.emui` | 华为 |
| MagicOS | `ro.build.version.magic` | 荣耀 |
| One UI | `ro.build.version.oneui` | 三星 |

ColorOS 17（基于 Android 17，2026-09 发布、11 月起全量推送）是本轮适配的重点：
OPPO 系包名前缀跨了三次迁移（`com.oppo.` / `com.coloros.` / `com.nearme.` /
`com.heytap.` / `com.oplus.`），规则表按这五个前缀匹配；`ads.oppomobile.com`
这类广告子域不再被厂商基础设施域名豁免吞掉；OAID 服务、快应用平台、游戏中心等
广告组件进入 `adblock` 清单。

**目前没有用 ColorOS 17 真机验证过**：清单和分级按公开资料整理，接入实机后需要
再校准一轮。检测层对这个缺口是友好的——即使规则表完全没命中，命令级复核也会把
「设备说成功但状态没变」如实报成未生效。

## 输出

```
noir-out/
├── report.json               # 结构化结果（应用、权限、端点、建议）
├── report.md                 # 人类可读报告
├── domains-trackers.txt      # 统计/广告/崩溃上报，可整份导入
├── domains-other.txt         # 用途不明，导入前逐条判断
├── domains-functional.txt    # 推送/登录/更新/支付/商店/云同步，禁止拦截
├── domains.txt               # 等于 trackers（兼容旧脚本）
├── plan.txt                  # 本次可执行的 appops
├── applied.txt               # 实际执行的命令、退出码、完整 stdout/stderr
├── verify.txt                # 复扫复核结论（按设备当前状态，不按退出码）
└── rollback.txt              # 回滚命令（只包含复核确认已生效的动作）

# noir adblock 另外写入
├── adblock-plan.txt          # 分级结果 + 每条 disable 命令
├── adblock.txt               # 实际执行的命令与即时回执
├── adblock-verify.txt        # 复扫复核：组件是否真的处于停用状态
├── adblock-rollback.txt      # pm enable 恢复命令（--revert 读它）
└── adblock-domains.txt       # 这些组件上报的域名，仅导出
```

## 隐私

- 工具本身**不联网**：没有遥测、没有版本检查、没有崩溃上报
- 扫描结果只写在你指定的 `--out` 目录
- 唯一的外部请求发生在 `noir setup`（下载 adb/jadx）和 `noir deep`（jadx 是本地进程）
- 报告里可能包含你设备上的包名和端点，分享前自行检查

## 缓存

扫描结果按 `包名 + APK 路径` 缓存在 `~/.noir/endpoints.tsv`。应用更新后路径变化会自动重新分析，重复扫描通常只需几秒。

---

## 架构

```
adb ──▶ pm / dumpsys ──▶ Dumpsys parser ──┐
                                          ├─▶ cache (pkg + apk path)
adb ──▶ unzip/strings ──▶ endpoints ──────┘        │
                                                   ▼
                                          Classifier ─▶ score ─▶ Advisor
                                                   │              │
                                                   ▼              ▼
                                              report.json    plan.txt
                                              report.md      rollback.txt
                                                   │
                                                   ▼
                                                 Tui / CLI
```

`deep` 走另一条支线：`adb pull` → `jadx` → 源码 grep。

## 设计取舍

- **端点抽取在设备端完成**：`adb shell` 内跑 `unzip | strings | grep`，避免把几百个 APK 拉回电脑。设备缺少 `unzip`/`strings` 时自动退化为本地解析。
- **共享 UID 特殊处理**：Android 上 `android.uid.system` 下的应用（常见 50+ 个）权限记录在 `dumpsys package` 尾部的 `Shared users` 段。解析器会把这些权限合并回每个包，否则最该被看见的应用反而会消失。
- **建议分级**：只有 appops 级别的动作标记为可自动执行；卸载必须显式开启。
- **无遥测**：程序不包含任何上报逻辑。

## 常见问题

**需要 root 吗？**
不需要。全部通过 `adb` 的 shell 权限完成；卸载只是 `--user 0` 级别，APK 仍在系统分区。

**会改坏手机吗？**
默认完全只读。`--apply` 只改 app-ops；执行前会查询原模式，成功执行的动作会写入
`rollback.txt`，按记录恢复。完整 stdout/stderr 和退出码写入 `applied.txt`。
`noir block` 的卸载也会先打印 `pm install-existing` 的恢复命令。

**命令返回 0，就一定改成功了吗？**
不一定，尤其 OPPO / vivo 会这样。所以 NOIR 把「执行」和「生效」分开报：
每条命令后立刻读回设备状态，整批跑完再复扫一次，结论写 `verify.txt`。
只有确认生效的动作才进 `rollback.txt`；判为「未生效」时终端会给出对应 ROM 的开关
（ColorOS 的「权限监控」/ MIUI 的「USB 调试（安全设置）」）。

**去广告会不会把系统搞坏？**
`adblock` 用的是 `pm disable-user`，不卸载、不删文件，`pm enable` 或
`noir adblock --revert` 即可恢复；「别碰」一档（更新、支付、激活、推送底层）
根本不会执行。要注意清单本身是按公开资料整理的，尚未在 ColorOS 17 真机上逐项验证，
所以每条都要你确认。

**为什么有些应用只给"人工复核"？**
系统应用不能真正卸载（`--user 0` 卸载也会被 OTA 或恢复出厂装回来），所以只建议
app-ops 或 DNS 层拦截。

**DNS 拦截为什么只导出不执行？**
跨平台改 DNS 很容易把设备网断掉（我们在开发中踩过），所以只导出清单：

- `domains-trackers.txt` —— 统计/广告/崩溃上报，可以整份导入
- `domains-other.txt` —— 用途不明，导入前逐条判断
- `domains-functional.txt` —— 推送、登录、更新、支付、应用商店、云同步，**拦了会掉功能**
- `domains.txt` —— 现在只包含 trackers，兼容旧脚本

IP、`localhost` 和带 `test`/`staging` 的域名会被自动剔除。

**支持 iOS 吗？**
不支持。`core` 是按 Kotlin Multiplatform 组织的，但审计逻辑依赖 Android 的
`dumpsys` 和 APK 格式。

## 参与

- [CONTRIBUTING.md](CONTRIBUTING.md) — 构建、测试、规则放在哪
- [SECURITY.md](SECURITY.md) — 漏洞报告方式（请不要开公开 issue）
- [Issues](https://github.com/Verlintas/NOIR/issues) — bug 请附上 `noir doctor` 输出

## 路线图

- [x] 等宽仪表盘（`noir ui`）
- [x] DeepScan（批量反编译 + 代码级信号）
- [x] 自带运行时的双击即用包（jpackage）
- [ ] 图形界面（Compose Multiplatform）
- [ ] 规则文件可配置（YAML），支持社区规则包
- [ ] 与 `apktool` / `jadx` 的批量流水线
- [ ] 设备端 DNS/VPN 拦截方案导出
- [ ] 扫描结果签名与版本对比

## 免责声明

NOIR 只应用于**你自己的设备**，或你获得明确授权的设备。它给出的是静态证据
（"这个应用具备这个能力，并且代码里有这个端点"），**不能证明**某次具体传输发生过。
请结合网络层证据自行判断。

## 许可

MIT © 2026 Verlintas
