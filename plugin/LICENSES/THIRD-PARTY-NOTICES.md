# 第三方组件声明（THIRD-PARTY NOTICES）

本插件（`com.spw.miniplayer`，Salt Player for Windows 迷你贴边播放器）在构建期使用、或在发行包中
分发下列第三方组件。按 Apache License 2.0 第 4 条的要求，此处汇总各组件的名称、版本、许可、
上游地址、用途以及**是否随发行包分发**。

## 一、随发行包分发的组件（触发 Apache-2.0 §4(a) 义务）

| 组件 | 版本 | 许可 | 上游地址 | 用途 | 包内位置 |
|---|---|---|---|---|---|
| Kotlin Standard Library (`kotlin-stdlib`) | 2.0.21 | Apache-2.0 | https://github.com/JetBrains/kotlin | 插件运行期依赖（宿主以 Kotlin 编写，插件运行在其类路径上） | `lib/kotlin-stdlib-2.0.21.jar` |

- 该组件的**许可证副本**已随发行包分发：见包内 `classes/LICENSES/Apache-2.0.txt`。
- 该组件的 **NOTICE 归属声明**已随发行包分发：见包内 `classes/LICENSES/Kotlin-NOTICE.txt`
  （原文全文见本文档第四节）。
- 构建期强制校验其 SHA-256：`f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09`。
- 说明：上游 `kotlin-stdlib-2.0.21.jar` 产物内部**不含** LICENSE / NOTICE 条目，因此本插件
  主动附带，以满足 §4(a) 的"向接收者提供许可证副本"要求。

## 二、仅构建期使用的组件（字节码不进入发行包）

| 组件 | 版本 | 许可 | 上游地址 | 用途 | 是否随包分发 |
|---|---|---|---|---|---|
| PF4J | 3.12.0 | Apache-2.0 | https://github.com/pf4j/pf4j | 编译期依赖（宿主插件框架）；仅 `compileOnly`，字节码不进包 | 否 |
| spw-workshop-api | 0.1.0-dev21 | Apache-2.0 | https://github.com/Moriafly/spw-workshop-api | 编译期依赖（宿主工坊 API）；仅 `compileOnly`，字节码不进包 | 否 |

- 两者的 SHA-256 均已在 `tools/build.py` 中固定并在构建时强制校验：
  - `pf4j-3.12.0.jar` = `e4af5f0e9a9b11ec187c084ed819cedede7f27076ac5cf6aa2333eceadb57215`
  - `spw-workshop-api-0.1.0-dev21.jar` = `f12c222e6e7f5f970f79e0343aef312ec9d8dd16c6731f1658591b4f8b891d52`
- 这两项**不含 NOTICE 文件**（上游仓库无 `NOTICE`，已核实），因此无 §4(d) 归属声明义务；
  此处列出仅为可追溯性。
- **注意**：`compileOnly` 依赖不触发 §4 的随附义务，但这不等于"无义务"——如实声明来源与许可是
  为了让使用者能够独立复核。

## 三、构建工具链

| 组件 | 版本 | 许可 | 用途 | 是否随包分发 |
|---|---|---|---|---|
| OpenJDK (javac) | 25 | GPL-2.0-with-Classpath-Exception | 编译插件源码（`javac`） | 否（仅构建工具，不产出衍生作品） |

## 四、Kotlin 官方 NOTICE 全文

来源：https://github.com/JetBrains/kotlin → `license/NOTICE.txt`

```
   =========================================================================
   ==  NOTICE file corresponding to the section 4 d of                    ==
   ==  the Apache License, Version 2.0,                                   ==
   ==  in this case for the Kotlin Compiler distribution.                 ==
   =========================================================================

   Kotlin Compiler
   Copyright 2010-2024 JetBrains s.r.o and respective authors and developers
```

## 五、不属于本插件分发范围的内容

- Salt Player for Windows 宿主程序及其 API 字节码为**闭源**内容。本插件**不**分发宿主的任何字节码。
- 本插件通过**反射**调用宿主的内部实现（如 `com.xuncorp.voxzen.util.AppConfig`）以实现桌面歌词
  开关与主题跟随。这属于**兼容性风险**（宿主版本更新可能导致功能失效），与版权再分发是两件不同
  的事，故在此分开陈述。
