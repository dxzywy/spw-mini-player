# 开发文档（DEVELOPMENT）

本文面向前两类读者：**想改这个插件的开发者**，以及**想复核构建与发布过程的维护者**。

普通使用者请直接看仓库根目录的 [`README.md`](../README.md)。README 只保留最基本的安装、
设置与构建信息，凡是偏内部的细节——构建系统全貌、JitPack 实验记录、完整验证命令、压力与
死锁回归、项目结构、内部实现——都收在这里。

- 插件 ID：`com.spw.miniplayer`
- 主类：`com.spw.miniplayer.MiniPlayerPlugin`（继承 `com.xuncorp.spw.workshop.api.SpwPlugin`）
- 许可：[Apache-2.0](../LICENSE)

---

## 一、项目结构

```
src/main/java/com/spw/miniplayer/   插件本体（17 个源文件）
plugin/preference_config.json       宿主设置页声明（设置项与按钮入口）
plugin/META-INF/                    扩展点注册与 PF4J 元数据
plugin/LICENSES/                    随发行包分发的许可声明副本
tools/build.py                      构建脚本（无 Gradle 依赖）
tools/harness.py                    验证脚本入口
tools/harness/src/                  验证代码（PF4J 加载链路、压测、死锁复现等）
docs/images/                        README 配图
docs/DEVELOPMENT.md                 本文
build/                              构建产物（不入库）
```

源码构成：

| 文件 | 职责 |
|---|---|
| `MiniPlayerPlugin` | 插件入口：生命周期（`start` / `stop` / `update` / `delete`）与配置页按钮入口 |
| `MiniPlayerController` | 控制器：装配窗口、订阅宿主播放状态、把宿主回调转成界面更新 |
| `MiniWindow` | 无边框置顶窗口、贴边吸附、收起为封面悬浮窗 |
| `MiniPlayerView` | 自绘界面（封面、标题、进度、按钮），负责命中区域判断 |
| `Theme` | 深 / 浅色调色板 |
| `PluginConfig` | 插件配置读写（落地为宿主数据目录下的 `config.json`） |
| `HostBridgeWorker` | 宿主交互的专用守护线程与桥接重试 |
| `HostReflection` | 宿主类的加载与反射工具 |
| `DesktopLyricsBridge` | 桌面歌词开关桥接 |
| `HostThemeBridge` | 主题跟随桥接 |
| `MainWindowBridge` | 唤起宿主主窗口（`AppConfig.updateMainWindowVisible` + AWT 前置兜底） |
| `CoverTheme` | 从封面提取主题色，派生面板底色与进度条强调色 |
| `MediaProbe` | 自行解析音频时长（FLAC / M4A/MP4 / MP3 / WAV / OGG） |
| `CoverArtLoader` | 自行解析内嵌封面，失败时回退同目录图片 |
| `Icons` | 矢量图标 |
| `PluginLog` | 日志（写入宿主工坊数据目录） |
| `MiniPlayerExtension` | 播放扩展点实现 |

---

## 二、构建系统

构建**不使用 Gradle**：`tools/build.py` 直接用 `javac` 编译，再用 Python 打包成 `.spmod` 与 `.zip`。
好处是零构建工具依赖，代价是依赖解析与校验要自己维护（见下）。

### 2.1 前置条件

- **JDK 21 及以上**。脚本使用 `javac --release 21`；可通过 `JDK_HOME` 指定 JDK 根目录，
  否则在常见位置探测。
- **`SPW_INSTALL`**：指向 Salt Player for Windows 的安装目录。**`--api=host` 与 `--api=auto`
  模式需要它**（`host` 模式未设置或无效即报错退出、不回退）；`jitpack` 模式不依赖本机宿主。
  由于正式发布必须使用 `--api=host`，发布前请务必确认 `SPW_INSTALL` 有效。

```powershell
# PowerShell
$env:SPW_INSTALL = "C:\Program Files\Salt Player for Windows"
```

```bash
# Git Bash
export SPW_INSTALL="/path/to/Salt Player for Windows"
test -f "$SPW_INSTALL/app/ffmpeg-x64.dll" && echo SPW_INSTALL_OK   # 验证配置是否生效
```

> 提示：宿主的主程序代码打包在 `app/ffmpeg-x64.dll` 里（它其实是一个改了扩展名的 jar，
> `app/*.cfg` 的 `app.classpath` 指向它）。`host` 模式即从这个文件抽取工坊 API 类。

### 2.2 编译期 API 来源（`--api`）

| 取值 | 是否要求本机装有 SPW | 行为 |
|---|---|---|
| `host` | **要求**：`SPW_INSTALL` 有效 | 从宿主程序包中抽取 workshop API 类作为编译期 API；未设置或无效即报错退出，不回退 |
| `jitpack` | 不要求 | 从 JitPack 下载子模块产物并校验 SHA-256；**标注为实验性**，见 2.3 |
| `auto`（默认） | 不要求 | 先试 `host`，失败再试 `jitpack`；两者都失败则列出各自原因后退出 |

```bash
python tools/build.py --api=host      # 发布构建一律使用这条
python tools/build.py --api=jitpack   # 实验性：无宿主环境时的备用路径，见 2.3
```

抽取出的 API jar 按来源分文件保存，互不覆盖：

```
build/api/spw-workshop-api-host.jar
build/api/spw-workshop-api-jitpack.jar
```

### 2.3 JitPack 路径的实测结论（实验性）

JitPack 上的 API 面比宿主运行时更新：**25 个类 vs 宿主 19 个类**，多出
`KeyBindingManager`、`PluginPermission`、`WorkshopApi$Library`、`ActionShortcut` 等。实测结果：

- 用 `--api=jitpack` 可以**编译成功**，且编译产物与 `--api=host` 的产物**逐文件 SHA-256 完全相同**
  （22/22 个 class 字节一致）——说明本插件只使用了两种 API 面共有的成员。
- 但验证代码 `tools/harness/src/harness/PluginLoadTest.java` 中的 API 桩是按**宿主 19 类 API 面**
  编写的，缺少 JitPack 新增接口方法的实现，因此在该 API 面下**无法编译**，导致
  `python tools/harness.py all` / `deadlock` **不能**直接用于 JitPack 构建的产物。

因此按门槛规则，JitPack **未取得「正式备用路径」资格，标注为实验性**。

**发行包（Release 附件）必须使用显式 `--api=host` 构建**，产物文件名为
`plugin-com.spw.miniplayer-<版本>.spmod` 与同名 `.zip`，输出在 `build/dist/`。

`auto` 模式保留回退能力（属构建便利），但每次回退都会打印醒目警告。

> 以上数据基于当前测试的宿主版本（1.18.5）与依赖版本（`spw-workshop-api` `0.1.0-dev21`、
> `pf4j` `3.12.0`、`kotlin-stdlib` `2.0.21`）。更换版本后应重新验证。

### 2.4 依赖与哈希校验

构建期依赖（`pf4j-3.12.0`、`kotlin-stdlib-2.0.21`、`spw-workshop-api` 子模块坐标）由脚本按需
下载，并做 **SHA-256 强制校验**；校验不通过即中止构建。确需接受新哈希时必须人工确认：

```bash
python tools/build.py --accept-new-hash
```

各依赖的具体版本、用途、是否随包分发，见 [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)。

### 2.5 版本号治理

版本号只有一个来源：仓库根目录的 `VERSION` 文件。MANIFEST、产物文件名与宿主工坊显示的版本
全部由它派生，因此不存在需要手工同步第二处的地方。插件在运行时也是从**自身包内的
`META-INF/MANIFEST.MF`** 读取 `Plugin-Version`（见 `MiniPlayerPlugin#pluginVersion`），刻意不写死。

```bash
python tools/build.py --bump patch    # 1.0.0 -> 1.0.1
python tools/build.py --bump minor
python tools/build.py --bump major
python tools/build.py --set 1.2.3
```

插件源码与验证代码中**禁止**出现 `x.y.z` 形式的字面量（构建时会强制校验并中止）。

> ⚠️ `--bump` 是**非幂等**的：如果同一条命令被外部重跑（例如沙箱/CI 重试），版本会连升两级。
> 需要确定版本时请用幂等的 `--set x.y.z`。

### 2.6 发行包结构

`.spmod` 与同名 `.zip` 是**字节完全相同**的 ZIP（宿主两种导入方式都接受）：

```
META-INF/MANIFEST.MF                    宿主「目录式描述符查找器」读的完整 Plugin-* 描述符
classes/                                插件类路径（PF4J DefaultPluginClasspath）
  META-INF/MANIFEST.MF                  插件描述符（第二份，双保险）
  META-INF/extensions.idx               LegacyExtensionFinder 用
  META-INF/services/<拓展点全限定名>     ServiceProviderExtensionFinder 用
  preference_config.json               宿主设置页声明
  LICENSES/                            随包分发的第三方许可副本
lib/kotlin-stdlib-<版本>.jar           运行期依赖
```

两种拓展点发现机制都写，避免宿主版本差异导致找不到拓展。

检查发行包**是否混入宿主代码必须解压后逐层做**：外层 ZIP 的文本搜索必然 0 命中（条目经 deflate
压缩），且 `lib/` 下是**嵌套 JAR**，需要再解压一层看条目名。

### 2.7 沙箱/环境注意事项

- 本机 `rm` 被安全删除层拦截（`trash-failed`）；删除或覆盖文件失败时先怀疑「被占用」。
- `grep … | head` 的 `$?` 取自 `head`（恒 0）；判「无匹配」必须用 grep 自身的退出码或捕获输出判空。

---

## 三、验证与测试

验证代码位于 `tools/harness/src/`，**不属于插件本体，不随包分发**。脚本会先从宿主安装包抽取
PF4J / spw API / kotlin 运行时组成测试 classpath，再编译并运行。

### 3.1 命令一览

```bash
python tools/harness.py config      # 配置读取语义（用宿主真实 ConfigHelper）
python tools/harness.py delay       # 自动收起延迟的行为计时（会短暂显示窗口）
python tools/harness.py preview     # 界面渲染与封面 / 时长解析自检
python tools/harness.py features    # 打开播放器 / 进度条颜色 / 封面主题色自检
python tools/harness.py load        # PF4J 加载链路 + 版本号一致性
python tools/harness.py live        # 真机窗口截屏（会短暂显示窗口）
python tools/harness.py stress [N]  # 展开 / 收起压力测试（会反复显示窗口）
python tools/harness.py deadlock    # 卡死根因复现与修复验证
python tools/harness.py all         # config + features + preview + load
```

注意 `all` 的组合是 **config + features + preview + load**，不含 `delay` / `stress` / `deadlock` / `live`。

对应源码：`HostConfigProbe`（config）、`HideDelayTest`（delay）、`DevPreview` / `LivePreview`
（preview / live）、`FeatureTest`（features）、`PluginLoadTest`（load）、`StressTest`（stress）、
`DeadlockRepro`（deadlock）。

`features` 的断言要点：

1. **主窗口开关式切换**：命中测试能点到新按钮；四种场景（托盘策略的打开 / 收起、退出策略的收起 /
   再打开）都会检查替身 `AppConfig` 的 `mainWindowVisible` 是否被改成了预期值 —— 尤其验证
   「退出策略下收起时宿主状态**保持可见**」，即插件确实绕开了会退出应用的路径；同时确认宿主调用
   落在 `spw-mini-host` 线程上。
2. **进度条像素**等于配置的颜色。
3. **面板底色像素**等于「封面主题色的 RGB + 用户设置的不透明度 alpha」，这是「主题色与不透明度
   叠加」的机器可读证据。
4. **配置项清理**：`preference_config.json` 里不再出现 `toggleMiniPlayer` / `collapseMiniPlayer`，
   源码里不再出现「点击展开」与 `Theme.hintText` —— 防止旧文案 / 失效引用被改回来。

### 3.2 压力测试（stress）

反复执行「展开 / 收起」并统计异常；内部带 **EDT 心跳看门狗**——若事件分发线程在阈值内没有
心跳，即判定界面卡死并让用例如实失败，而不是静默挂起。

### 3.3 启动期死锁的复现与回归（deadlock）

`DeadlockRepro` 用一个专门的线程（名为 `plugin-start`）模拟插件在**持锁状态下**解析宿主类，
再 `join` 等待；在修复前的代码上可稳定复现卡死，修复后应正常返回。根因与修法见第四节。

### 3.4 升级场景下的「旧版本遮蔽」预检（load）

`PluginLoadTest` 会模拟宿主导入后的目录结构 `plugin-<id>-<version>` 并断言：

- PF4J 描述符版本与 MANIFEST 一致；
- 安装目录名带正确版本号；
- 插件状态为 `STARTED`；
- 插件目录中**只保留当前版本**（`findStaleVersionDirs`）。

最后一条是**测试脚本自设的升级场景预检**：PF4J 以插件 ID 为键，同一 ID 只加载**先被扫描到**
的那个目录，因此「新旧目录共存」会让新版本不生效。关于宿主在真实升级时如何清理旧版本，
见第 3.5 节。

### 3.5 宿主在真实升级时的旧版本处理（实测）

对宿主 `app/ffmpeg-x64.dll`（jar）反编译得到的结论：

- 宿主工坊的**更新/导入**实现（`com.xuncorp.voxzen.workshop.WorkshopApiInstance`）在导入新版时，
  会先通知旧插件、停用它，再用 PF4J `FileUtils.optimisticDelete` **删除旧插件目录与旧 zip**，
  然后复制导入新包；导入若未能在运行中加载，会提示「需要重启应用以加载插件」。
- 宿主另有一个启动期清理例程：扫描插件目录下以 **`.oldPlugin`** 结尾的条目，递归删除其对应的
  原目录后，再执行 `loadPlugins()` / `startPlugins()`。`.oldPlugin` 是文件被占用（Windows 锁）
  时「改名待删」的兜底机制，正常情况下对使用者透明。

因此：**通过工坊正常升级不需要手动删除旧目录**；只有手动解压覆盖、导入中断等异常情况下才可能
出现同 ID 多目录，此时按 README「升级」一节的说明处理即可。

> 以上结论基于当前测试的宿主版本（1.18.5）；更换宿主版本后建议重新复核本节。

---

## 四、内部实现说明

### 4.1 线程模型与启动期死锁的根因

宿主启动时会**同步初始化** `com.xuncorp.voxzen.util.AppConfig`（Kotlin object，其静态初始化里
会创建大量播放器状态）。而插件**反射读取它的静态字段会触发/等待该类的初始化**。

历史缺陷：插件在持有自身锁的情况下做这件事，同时宿主又在 `AppConfig.<clinit>` 内回调插件
（配置变更通知、媒体回调），双方交叉等待 → 永久死锁；即「插件跟随播放器一起启动才卡死」。
在 EDT 上做这件事同样危险（EDT 被初始化流程卡住，整个界面冻结）。

**约定（不可违反）**：

- 插件对宿主的所有「重」交互都在 `HostBridgeWorker` 的**唯一守护线程**（`spw-mini-host`）上
  串行执行：宿主类解析（`Class.forName` + 读取 `AppConfig.INSTANCE`）与宿主 API 调用。
- **绝不在 EDT 或宿主回调线程上触发宿主类初始化，也绝不在持锁状态下加载宿主类。**
- 首次对接失败不是永久降级：`HostBridgeWorker` 会以 15 秒间隔、最长 120 秒的窗口重试。

### 4.2 反射桥接

`HostReflection` 会依次尝试线程上下文类加载器、插件自身加载器链、系统加载器等，取到宿主类；
随后用结构化方式读静态字段、按名称 + 参数兼容性查找方法（先 public，再逐级 declared），
并在可用时 `setAccessible(true)`。

- **桌面歌词**（`DesktopLyricsBridge`）：开关保存在 `com.xuncorp.voxzen.util.AppConfig`。宿主自身的
  「桌面歌词」热键也调用 `setDesktopLyrics(boolean)`，插件调用**同一入口**，因此宿主界面与歌词
  窗口会立即同步。访问器命名按 `getDesktopLyrics` / `isDesktopLyrics` / `getDesktopLyric` 依次探测，
  写入同理。
- **主题跟随**（`HostThemeBridge`）：读取宿主主题设置；宿主设为「跟随系统」时读取系统应用主题。
- **主窗口打开 / 收起**（`MainWindowBridge`）：宿主把「主窗口是否可见」放在 `AppConfig` 的
  `mainWindowVisible` 状态上，写入入口是 `updateMainWindowVisible(boolean)`（注意同名 `setXxx`
  在宿主里是**私有**的，只探测 getter / update 命名）。宿主自身的全局热键与播放服务走的正是这个
  入口，所以调用后主窗口会真的恢复（包括最小化到托盘的场景）。

  **收起方向必须先读宿主的「关闭主窗口」策略**：`getCloseMainWindowStrategy()` 返回枚举
  `com.xuncorp.voxzen.ui.screen.appearance.CloseMainWindowStrategy`，常量为 `SystemTray` /
  `ExitProgress`（该枚举类的常量名在包内是明文，可用「改 major 为 55 再 javap」的办法确认）：

  | 策略（按枚举名判定） | 插件的动作 |
  |---|---|
  | 名字含 `tray` | `updateMainWindowVisible(false)`，交回宿主自己的关闭流程 |
  | 名字含 `exit` / `quit` / `close` | **绝不**调用宿主入口，只做 AWT `setExtendedState(ICONIFIED)` |
  | 识别不出 | 按上一行处理（最保守） |

  之所以不能用宿主入口兜底：宿主设置为「退出应用」时，关闭主窗口会结束整个进程。判定用反射读
  `name()` 而不是依赖具体类型，宿主改包名/换枚举类也不会漏判。

  另外 AWT 兜底这一层：从 `Frame.getFrames()` 里挑面积最大的可见窗口做 `toFront()`，
  迷你小窗自身是 `JWindow`，天然排除。

  由于「退出」策略下不能改宿主状态，插件用一个 `minimizedToTaskbar` 标记记录「上一次是我把窗口
  最小化到任务栏的」，这样第三次点击仍能正确切回打开状态。

三处桥接在解析前都会先判断 `isAvailable()`，避免重复解析；任何一处没对接上，都会由
`HostBridgeWorker.prepare` 的定时重试兜底（2 分钟窗口内每 15 秒一次）。

### 4.3 元数据与时长解析（不依赖宿主接口）

`MediaProbe` 与 `CoverArtLoader` 自行解析音频文件，不经过宿主 API，因此不受宿主内部实现变动影响。

- **时长**：覆盖 FLAC / M4A/MP4 / MP3 / WAV / OGG；无法解析时返回 `-1`，界面会隐藏进度条。
- **封面**：优先读取**内嵌封面**（ID3v2 `APIC` / FLAC `PICTURE` / MP4 `covr`）；MP3 无 ID3v2 头时
  会做一次文件尾部兜底；仍失败则回退同目录图片，文件名主干按
  `cover` / `folder` / `front` / `album` / `albumart` / `thumb` / `artwork` 依次尝试，
  扩展名为 `.jpg` / `.jpeg` / `.png` / `.bmp` / `.gif` / `.webp`。
- 封面加载在独立线程（`spw-mini-cover`）中进行，避免阻塞界面。

### 4.4 界面

界面基于 AWT / Swing 自绘：`MiniWindow` 负责无边框置顶窗口与贴边吸附；`MiniPlayerView` 负责
绘制封面、标题、进度与按钮并做命中判断；`Theme` 提供深 / 浅色调色板，随宿主主题切换。

悬浮层（收起态）只画两枚按钮——左上「打开 / 收起主播放器」、右上「展开面板」，**刻意不写文字提示**：
小窗本身只有 76px 见方，压在封面上的说明文字既挤又会常年遮住画面，图标 + 悬停高亮已足够表达。

### 4.5 封面主题色与进度条颜色

`CoverTheme` 从当前封面提取主色，供两处使用：

- **面板背景**（`panelTint`）：把封面缩到 32×32 后按 4bit/通道做直方图，用
  「像素数 × 饱和度权重 × 中间亮度权重」挑出主色色桶并取均值（比简单平均更接近人眼感知）。
  随后把亮度压进「能衬住文字」的区间（深色主题 0.13–0.30、浅色主题 0.72–0.93）、
  给饱和度设上限（深色 0.58 / 浅色 0.45），再与主题面板原色混合 22%。
- **进度条强调色**（`progressAccent`）：同一色相上提亮提饱和，保证压在面板上仍然清楚。

**主题色与不透明度是叠加关系**：`panelTint` 返回的颜色**不带 alpha**，绘制时才由
`MiniPlayerView.panelFillColor()` 取 RGB、再套上用户设置的 `panelAlpha`（悬停时 +10）。
因此改不透明度不会冲掉主题色，改封面也不会冲掉不透明度——`features` 自检对这两点都有像素级断言。

提取过程（逐像素扫描）放在专用线程 `spw-mini-theme` 上，EDT 只接收算好的颜色；
深浅色主题切换时会用缓存的主色重新派生，不重新扫图。

进度条颜色的配置解析（`MiniPlayerController.parseColor`）支持 `auto` / `#RGB` / `#RRGGBB` /
`#AARRGGBB`，解析失败退化为「跟随封面」，再退化为主题强调色。

### 4.6 配置

`PluginConfig` 把设置项落地为宿主工坊数据目录下的 `config.json`；设置项全部声明在
`plugin/preference_config.json`。

- **小窗显示 / 隐藏**用 `mini_player_visible`（`switch`，默认开）而不是按钮：开关是**持久状态**，
  关掉后小窗保持隐藏，重启插件也不会自己冒出来，语义比一次性按钮明确，控件风格也和其余设置项一致。
  它由 `MiniPlayerController.applyVisibility()` 在 `createUi()` 与每次 `applyConfig()` 时统一兑现。
- 进度条颜色用的是 `edittext` 类型（宿主支持的自由文本输入；同机的 Steam Rich Presence 插件也在用），
  这样用户可以填任意颜色值，不受下拉选项数量限制。

> 宿主也支持 `button` 类型：`on_click` 指向插件的 **`public static` 无参方法**（宿主以反射调用）。
> 当前配置项已全部改为开关 / 下拉 / 文本，因此插件主类里不再有这类入口；若将来要加回按钮，
> 注意方法必须是 `public static` 且无参。

> 「自动收起延迟」与「面板不透明度」使用下拉而非滑条，是因为宿主设置页的滑条不提供精度 /
> 步进控制，取值会出现长串小数。

配置变更走 `ConfigManager` 的变更监听 → EDT 上 200ms 去抖 → `applyConfig()`，因此
`cover_tint` / `progress_color` 保存后立即生效，无需重启。

#### ⚠️ 读配置的陷阱：默认值不能传 `null`

宿主的 `ConfigHelper.get(key, defaultValue)` 会按 **defaultValue 的运行时类型**做分支来决定怎么解析
配置里的 JSON 值；**传入 `null` 会直接命中 default 分支返回 `null`**，等价于「任何配置都读不到」，
每个设置项都会静默退化成代码里的兜底值（早期版本「自动收起延迟永远是 500ms」就是这个原因）。

因此 `PluginConfig` 采用两层读取：**先用 String 默认值取原始字面量再本地解析**（宿主的 `list`
存成 JSON 字符串 `"1.5"`、旧滑条存成数字 `3000.0`，这一层都能拿到），再按目标类型取一次兜底
（兜底默认值必须非空）。`ConfigHelper.reload()` 是直接从磁盘重新 `readString`，缓存的引用不会失效。

---

## 五、与宿主的兼容性边界

- 本插件基于 Salt Player for Windows **1.18.5** 开发，桌面歌词开关、主题跟随与主窗口唤起依赖对宿主
  **内部实现**的反射调用。**宿主更新后这三项功能有可能失效**（属兼容性风险）；播放器的基本功能
  （封面、标题、进度、播放控制、封面主题色、进度条颜色）不受影响。
- 「主窗口唤起」即使反射失败也还有 AWT 兜底，只是无法把窗口从「最小化到托盘」的状态恢复出来。
- 插件**不会、也不应分发宿主的任何字节码**。宿主自身的闭源内容不在本仓库与发行包范围内；
  仓库内也不包含从宿主抽取的类（`build/` 已在 `.gitignore` 中忽略）。
- 宿主相关的事实（目录布局、去重规则、导入与清理行为、JitPack API 面对比）均以本机实测为准；
  更换宿主或依赖版本后建议重新复核第 2.3 节与第 3.5 节。

---

## 六、发布流程

1. 确认工作区干净，`VERSION` 正确；
2. 用**显式 `--api=host`** 构建：

   ```bash
   python tools/build.py --api=host
   ```

3. 产物位于 `build/dist/`：`plugin-com.spw.miniplayer-<版本>.spmod` 与同名 `.zip`；
4. 发布时以 `.zip` 作为 Release 附件，并记录其 SHA-256 便于校验；
5. 建议先用 `python tools/harness.py all` 做一轮回归。

---

## 七、许可证

本项目以 Apache-2.0 许可发布，见 [`LICENSE`](../LICENSE)。
随包分发的第三方组件与仅构建期使用的依赖，清单见 [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)。
