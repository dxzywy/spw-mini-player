# SPW 迷你贴边播放器（spw-mini-player）

Salt Player for Windows 的工坊插件：一个**可贴边隐藏的迷你播放器小窗**。
贴边自动收起后变成一枚**封面悬浮窗**，单击即可还原，拖动可换位置；鼠标悬停时浮出控制按钮。

- 插件 ID：`com.spw.miniplayer`
- 许可：Apache-2.0（见 [`LICENSE`](LICENSE)）
- 第三方声明：[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)

## 截图

| 深色主题 · 展开 | 深色主题 · 封面悬浮窗 |
|---|---|
| ![深色展开](docs/images/01-playing-dark.png) | ![深色悬浮窗](docs/images/02-bubble-dark.png) |

| 浅色主题 · 展开 | 浅色主题 · 悬浮窗悬停 |
|---|---|
| ![浅色展开](docs/images/03-playing-light.png) | ![浅色悬浮窗](docs/images/04-bubble-light.png) |

（以上为插件的离屏渲染结果，纯界面，不含桌面内容。）

## 功能

| 功能 | 说明 |
|---|---|
| 可贴边隐藏的小窗 | 无边框、置顶、可拖动；松开鼠标后自动吸附最近的屏幕边缘（左 / 右 / 上 / 下） |
| 封面悬浮窗 | 鼠标离开后收起为一枚浮动封面小窗：单击还原、可拖动换位置、悬停浮出控制按钮，并显示播放进度线 |
| 桌面歌词开关 | 与宿主自带桌面歌词开关**同一入口**，切换后宿主界面即时同步 |
| 播放 / 暂停 | 单击播放按钮，或在小窗内直接操作 |
| 上一首 / 下一首 | 切换曲目 |
| 迷你封面 | 优先读取音频内嵌封面（ID3v2 APIC / FLAC PICTURE / MP4 covr），失败时回退同目录 `cover.jpg` / `folder.jpg` / `front.jpg` |
| 歌曲名称 | 显示标题与「歌手 · 专辑」；长标题自动滚动 |
| 深色 / 浅色主题自适应 | 跟随宿主主题设置；宿主设为「跟随系统」时读取系统应用主题 |
| 播放进度 | 显示进度条与已播 / 总时长 |

## 安装

1. 把本仓库的 Release 附件 `plugin-com.spw.miniplayer-1.0.0.zip` 交给宿主的工坊导入；（或解压到
   `%APPDATA%\Salt Player for Windows\workshop\plugins\` 下）
2. 在创意工坊中**启用**该插件，并勾选其申请的权限；
3. **删除同 ID 的旧版本目录**后重启播放器。

> **重要**：宿主按插件 ID 去重，同一 ID 只加载**先被扫描到**的那个目录。若 `workshop\plugins\`
> 下同时存在新旧版本目录，会出现"装了新版却仍加载旧版"的现象。升级时请先删除旧版本目录。

插件日志位于 `%APPDATA%\Salt Player for Windows\workshop\data\com.spw.miniplayer\mini-player.log`。

## 设置项

在宿主的插件设置页中可调整：

| 设置项 | 类型 | 可选值 | 默认 |
|---|---|---|---|
| 显示 / 隐藏迷你播放器 | 按钮 | — | — |
| 收起为封面小窗 | 按钮 | — | — |
| 贴边自动收起 | 开关 | 开 / 关 | 开 |
| 窗口始终置顶 | 开关 | 开 / 关 | 开 |
| 默认贴边位置 | 下拉 | 右侧 / 左侧 / 顶部 / 底部 | 右侧 |
| 自动收起延迟 | 下拉 | 0.3 / 0.5 / 1 / 1.5 / 2 / 3 / 5 秒 | 0.5 秒 |
| 面板不透明度（%） | 下拉 | 0 / 10 / 20 / … / 100 | 80 |

> 「自动收起延迟」与「面板不透明度」使用下拉而非滑条，是因为宿主设置页的滑条不提供精度 /
> 步进控制，取值会出现长串小数。

## 构建

### 前置条件

- **JDK 21 及以上**（构建脚本使用 `javac --release 21`）。可用 `JDK_HOME` 指定 JDK 根目录。
- **`SPW_INSTALL` 环境变量**：指向 Salt Player for Windows 的安装目录。
  构建脚本**不内置任何默认安装路径**，未设置时会直接报错退出。

```bash
export SPW_INSTALL="/path/to/Salt Player for Windows"    # Git Bash
# $env:SPW_INSTALL="..."                                 # PowerShell
test -f "$SPW_INSTALL/app/ffmpeg-x64.dll" && echo SPW_INSTALL_OK
```

### 编译期 API 来源（`--api`）

| 取值 | 是否要求本机装有 SPW | 行为 |
|---|---|---|
| `host` | **要求**：`SPW_INSTALL` 有效 | 从宿主程序包抽取 workshop API 类作为编译期 API；未设置或无效即报错退出，不回退 |
| `jitpack` | 不要求 | 从 JitPack 下载子模块产物并校验 SHA-256；**标注为「实验性」**，见下方限制说明 |
| `auto`（默认） | 不要求 | 先试 `host`，失败再试 `jitpack`；两者都失败则列出各自原因后退出 |

```bash
python tools/build.py --api=host      # 支持路径：发布构建一律使用这条
python tools/build.py --api=jitpack   # 实验性：无宿主环境时的备用路径，见下方限制
```

**JitPack 路径的限制（实验性，实测结论）**

JitPack 上的 API 面比宿主运行时更新（**25 个类 vs 宿主 19 个类**，多出
`KeyBindingManager`、`PluginPermission`、`WorkshopApi$Library`、`ActionShortcut` 等）。实测结果：

- 用 `--api=jitpack` 可以**编译成功**，且编译产物与 `--api=host` 的产物**逐文件 SHA-256 完全相同**
  （22/22 个 class 字节一致）—— 说明本插件只使用了两种 API 面共有的成员。
- 但验证代码 `tools/harness/src/harness/PluginLoadTest.java` 中的 API 桩是按**宿主 19 类 API 面**
  编写的，缺少 JitPack 新增接口方法的实现，因此在该 API 面下**无法编译**，导致
  `python tools/harness.py all` / `deadlock` **不能**直接用于 JitPack 构建的产物。

因此按门槛规则，JitPack **未取得"正式备用路径"资格，标注为实验性**。发行包（Release 附件）
**必须使用显式 `--api=host` 构建**，产物文件名为 `plugin-com.spw.miniplayer-<版本>.spmod` 与同名 `.zip`。
`auto` 模式保留回退能力（属构建便利），但每次回退都会打印醒目警告。

构建期依赖（`pf4j`、`kotlin-stdlib`、`spw-workshop-api`）由脚本按需下载并做 **SHA-256 强制校验**，
校验不通过即中止；确需接受新哈希时必须人工确认并追加 `--accept-new-hash`。

### 版本号

版本号只有一个来源：仓库根目录的 `VERSION` 文件。MANIFEST、产物文件名与宿主工坊显示的版本
全部由它派生，因此不存在需要手工同步第二处的地方。

```bash
python tools/build.py --bump patch    # 1.0.0 -> 1.0.1
python tools/build.py --bump minor
python tools/build.py --bump major
python tools/build.py --set 1.2.3
```

插件源码与验证代码中**禁止**出现 `x.y.z` 形式的字面量（构建时会强制校验并中止）。

## 验证

开发期验证脚本（不属于插件本体，不随包分发）：

```bash
python tools/harness.py all        # 解析自检 + 界面离屏渲染 + PF4J 加载链路
python tools/harness.py preview    # 仅离屏渲染与封面 / 时长解析自检
python tools/harness.py load       # 仅 PF4J 加载链路（用宿主真实框架）
python tools/harness.py config     # 宿主配置语义探针（真实 ConfigHelper 实现）
python tools/harness.py delay      # 真实窗口 + 真实计时，验证自动收起延迟生效
python tools/harness.py deadlock   # 启动期类初始化死锁的确定性复现与回归
python tools/harness.py stress 140 # 展开 / 收起压力测试（含 EDT 心跳看门狗）
python tools/harness.py live       # 真实创建窗口并截屏
```

## 项目结构

```
src/main/java/com/spw/miniplayer/   插件本体（15 个源文件）
plugin/preference_config.json       宿主设置页声明
plugin/LICENSES/                    随发行包分发的许可声明
tools/build.py                      构建脚本（无 Gradle 依赖）
tools/harness.py                    验证脚本入口
tools/harness/src/                  验证代码（含 PF4J 加载链路、压力测试等）
docs/images/                        README 配图
build/                              构建产物（不入库）
```

## 兼容性说明

本插件通过**反射**调用宿主的内部实现（例如 `com.xuncorp.voxzen.util.AppConfig`）来实现桌面歌词
开关与主题跟随。这带来两项需要知情的限制：

1. **宿主版本更新可能导致相关功能失效**（属兼容性风险）。本插件基于宿主 1.18.5 开发。
2. 插件不会、也不应分发宿主的任何字节码；宿主自身的闭源内容不在本仓库与发行包范围内。

封面与时长由插件自行解析音频文件得到（不依赖宿主接口），支持 MP3 / FLAC / WAV / MP4 / OGG。

## 许可与致谢

本项目以 Apache-2.0 许可发布，见 [`LICENSE`](LICENSE)。

随发行包分发的第三方组件（Kotlin Standard Library）的许可副本与归属声明位于包内
`classes/LICENSES/`；完整清单见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。

感谢 [Salt Player](https://github.com/Moriafly/SaltPlayerSource) 作者 Moriafly 提供的
[spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 插件开发接口。
