# SPW 迷你贴边播放器（spw-mini-player）

一个给 Salt Player for Windows 用的工坊插件：把播放控制收进一块贴在屏幕边缘的迷你小窗。

小窗可以收纳到屏幕边缘；鼠标离开后自动收起成一枚盖着当前专辑封面的小圆窗——单击即可重新展开，
拖动可以换位置，把鼠标移到上面还会浮出控制按钮——不必切回主界面，也能随手控制正在播放的音乐。
点一下小窗上的「打开播放器」按钮就能唤起 Salt Player 主窗口，再点一下则按你的「关闭主窗口」
设置把它收起来。

- 插件 ID：`com.spw.miniplayer`
- 许可：[Apache-2.0](LICENSE)
- 第三方组件声明：[`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)

## 功能

| 功能 | 说明 |
|---|---|
| 可贴边隐藏的小窗 | 无边框、置顶、可拖动；松开鼠标后自动吸附到最近的屏幕边缘（左 / 右 / 上 / 下） |
| 封面悬浮窗 | 鼠标离开后收起为一枚封面小窗：单击展开、可拖动换位置、悬停浮出控制按钮，并显示播放进度线 |
| 一键打开 / 收起播放器 | 展开面板右上角与悬浮窗左上角都有入口：点一下唤起（并前置）Salt Player 主窗口，再点一下按你的「关闭主窗口」设置收起它；小窗本身不关闭，继续与播放器保持同步 |
| 桌面歌词开关 | 与宿主自带的桌面歌词开关是同一个入口，在这里切换，宿主界面会立即同步 |
| 播放 / 暂停 | 单击播放按钮即可切换 |
| 上一首 / 下一首 | 切换曲目 |
| 迷你封面 | 优先读取音频文件内嵌封面，未找到时尝试读取同目录下的常见封面图片 |
| 歌曲名称 | 显示标题与「歌手 · 专辑」；长标题自动滚动 |
| 封面主题色背景 | 展开后的面板背景自动从当前封面提取主题色，且与「面板不透明度」叠加生效，两者互不覆盖 |
| 进度条颜色可自定义 | 可填任意颜色值，也可让它跟随封面主题色；改完保存立即生效 |
| 深色 / 浅色主题自适应 | 跟随宿主主题设置；宿主设为「跟随系统」时读取系统应用主题 |
| 播放进度 | 显示进度条与已播 / 总时长 |

## 截图

| 深色主题 · 展开 | 深色主题 · 封面悬浮窗 |
|---|---|
| ![深色展开](docs/images/01-playing-dark.png) | ![深色悬浮窗](docs/images/02-bubble-dark.png) |

| 浅色主题 · 展开 | 浅色主题 · 悬浮窗悬停 |
|---|---|
| ![浅色展开](docs/images/03-playing-light.png) | ![浅色悬浮窗](docs/images/04-bubble-light.png) |

（以上为插件的离屏渲染结果，纯界面，不含桌面内容。）

## 安装

1. 打开 [Releases 页面](https://github.com/dxzywy/spw-mini-player/releases/latest)，下载最新版本的
   `plugin-com.spw.miniplayer-<版本>.zip`；
2. 把这个 zip 文件交给宿主「创意工坊」的导入功能；
3. 在创意工坊中**启用**该插件，并勾选它申请的权限。

插件日志位于 `%APPDATA%\Salt Player for Windows\workshop\data\com.spw.miniplayer\mini-player.log`，
遇到问题可以先看这里。

### 升级

直接用创意工坊的导入功能导入新版 zip 即可，宿主会自行处理被替换掉的旧版本。如果导入后提示
需要重启应用才能加载，重启播放器即可生效。

宿主按插件 ID 识别插件，**同一个插件 ID 只会加载先被扫描到的那个目录**。正常升级后不应该残留
旧目录；若在**手动解压覆盖**、导入中途中断等情况下发现「装了新版却还是旧版的行为」，可以打开

```
%APPDATA%\Salt Player for Windows\workshop\plugins\
```

确认是否存在同一个插件 ID 的多个版本目录。若有，删除旧的那一份（目录及其同名 `.zip`）后
重启播放器。

## 设置项

在宿主的插件设置页中可调整：

| 设置项 | 类型 | 可选值 | 默认 |
|---|---|---|---|
| 显示迷你播放器 | 开关 | 开 / 关 | 开 |
| 贴边自动收起 | 开关 | 开 / 关 | 开 |
| 窗口始终置顶 | 开关 | 开 / 关 | 开 |
| 默认贴边位置 | 下拉 | 右侧 / 左侧 / 顶部 / 底部 | 右侧 |
| 自动收起延迟 | 下拉 | 0.3 / 0.5 / 1 / 1.5 / 2 / 3 / 5 秒 | 0.5 秒 |
| 面板不透明度（%） | 下拉 | 0 / 10 / 20 / … / 100 | 80 |
| 面板背景使用封面主题色 | 开关 | 开 / 关 | 开 |
| 进度条颜色 | 文本 | `auto` 或颜色值（见下） | `auto` |

> 「自动收起延迟」与「面板不透明度」使用下拉而非滑条，是因为宿主设置页的滑条不提供精度 /
> 步进控制，取值会出现长串小数。

### 进度条颜色怎么写

| 写法 | 含义 |
|---|---|
| `auto` | 跟随当前封面的主题色；没有封面时用主题默认强调色 |
| `#FF4081` | 指定颜色（`#` 可省略），也支持简写 `#F08` |
| `#80FF4081` | 带透明度：前两位是不透明度，后六位是颜色 |

颜色值无法识别时会自动退回「跟随封面」，并在插件日志里留一条记录。
改完保存即生效，**不需要重启播放器**。

## 兼容性说明

本插件基于 Salt Player for Windows **1.18.5** 开发。桌面歌词开关、主题跟随与「一键打开播放器」
这三项功能，需要通过**反射**调用宿主内部的实现来与宿主联动，因此：

- 宿主更新后，这三项功能**有可能失效**（属兼容性风险）；插件自身的其他功能不受影响。
- 「一键打开 / 收起播放器」在宿主接口不可用时会退回 AWT 兜底（把已存在的主窗口顶到前台），
  只影响「从托盘恢复窗口」这一种情况。

### 收起主窗口时的行为

小窗上的按钮是开关式的：主窗口已打开时再点一次，会**跟随宿主的「关闭主窗口」设置**：

| 宿主的「关闭主窗口」设置 | 再点一次的结果 |
|---|---|
| 最小化到系统托盘 | 主窗口最小化到系统托盘（走宿主自己的关闭流程） |
| 退出 Salt Player | **只把主窗口最小化到任务栏**，不会退出播放器 |

第二行是刻意为之：宿主设置为「退出」时，走宿主的关闭流程会直接结束整个应用。插件在这条路径上
只用系统的窗口最小化，因此**点错也不会退掉正在播放的音乐**。
- 插件不包含、也不分发宿主的任何字节码。

封面与时长由插件自行解析音频文件得到（不依赖宿主接口），支持 MP3 / FLAC / WAV / M4A/MP4 / OGG。

关于反射调用的具体对象与应对方式，见 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。

## 开发与构建

### 环境

- **JDK 21 及以上**（构建脚本使用 `javac --release 21`）。可用 `JDK_HOME` 指定 JDK 根目录。
- **`SPW_INSTALL` 环境变量**：指向 Salt Player for Windows 的安装目录，`--api=host` 与
  `--api=auto`（回退前）都会从中抽取 API 类；`--api=jitpack` 不依赖本机宿主。脚本**不内置
  任何默认安装路径**，也不做路径猜测；`--api=host` 未设置或指向的目录无效时直接报错退出、
  不回退。由于正式发布必须使用 `--api=host`（见下文），发布前请务必确认 `SPW_INSTALL`
  指向有效的安装目录。

```powershell
# PowerShell
$env:SPW_INSTALL = "C:\Program Files\Salt Player for Windows"
```

```bash
# Git Bash
export SPW_INSTALL="/path/to/Salt Player for Windows"
test -f "$SPW_INSTALL/app/ffmpeg-x64.dll" && echo SPW_INSTALL_OK   # 验证配置是否生效
```

### 编译期 API 来源（`--api`）

| 取值 | 是否要求本机装有 SPW | 行为 |
|---|---|---|
| `host` | **要求**：`SPW_INSTALL` 有效 | 从宿主程序包抽取 workshop API 类作为编译期 API；未设置或无效即报错退出，不回退 |
| `jitpack` | 不要求 | 从 JitPack 下载子模块产物并校验 SHA-256；**标注为「实验性」** |
| `auto`（默认） | 不要求 | 先试 `host`，失败再试 `jitpack`；两者都失败则列出各自原因后退出 |

> **`jitpack` 仍为实验性，不能用于发布。** 正式发布的产物（Release 附件）**必须使用显式
> `--api=host` 构建**。JitPack 与宿主 API 面的差异、以及它为何暂不能作为正式备用路径，
> 记录在 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。

### 构建

```bash
python tools/build.py --api=host      # 发布构建一律使用这条
```

产物输出在 `build/dist/`：`plugin-com.spw.miniplayer-<版本>.spmod` 与同名 `.zip`。

### 版本号

版本号只有一个来源：仓库根目录的 `VERSION` 文件。MANIFEST、产物文件名与宿主工坊显示的版本
全部由它派生，因此不存在需要手工同步第二处的地方。

```bash
python tools/build.py --bump patch    # 1.0.0 -> 1.0.1
python tools/build.py --bump minor
python tools/build.py --bump major
python tools/build.py --set 1.2.3
```

### 验证

```bash
python tools/harness.py all        # 配置 + 界面离屏渲染 + 新功能自检 + PF4J 加载链路
python tools/harness.py preview    # 仅离屏渲染与封面 / 时长解析自检
python tools/harness.py features   # 仅「打开播放器 / 进度条颜色 / 封面主题色」自检
```

完整的验证命令、压力测试与死锁回归、项目结构、内部实现说明，见
[`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)。

## 许可与致谢

本项目以 [Apache-2.0](LICENSE) 许可发布。

随发行包分发的第三方组件（Kotlin Standard Library）的许可副本与归属声明位于包内
`classes/LICENSES/`；完整清单，以及 PF4J、spw-workshop-api 等仅构建期使用的依赖说明，
见 [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md)。

感谢 [Salt Player](https://github.com/Moriafly/SaltPlayerSource) 作者 Moriafly 提供的
[spw-workshop-api](https://github.com/Moriafly/spw-workshop-api) 插件开发接口。
