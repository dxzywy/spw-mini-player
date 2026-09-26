#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""开发期验证脚本（不属于插件本体）。

  1. 从宿主安装包抽取 PF4J / spw API / kotlin 运行时，组成测试 classpath；
  2. 编译 tools/harness/src 下的验证代码；
  3. 运行各项验证。

用法：
  python tools/harness.py config      # 配置读取语义（用宿主真实 ConfigHelper）
  python tools/harness.py delay       # 自动收起延迟的行为计时（会短暂显示窗口）
  python tools/harness.py preview     # 界面渲染与封面/时长解析自检
  python tools/harness.py load        # PF4J 加载链路 + 版本号一致性
  python tools/harness.py live        # 真机窗口截屏（会短暂显示窗口）
  python tools/harness.py stress [N]  # 展开/收起压力测试（会反复显示窗口）
  python tools/harness.py deadlock    # 卡死根因复现与修复验证
  python tools/harness.py all         # config + preview + load
"""

import os
import shutil
import subprocess
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build import (  # noqa: E402
    BUILD, HERE, HOST_JAR, ROOT, locate_jdk, log,
)

HARNESS = os.path.join(BUILD, "harness")
CP_DIR = os.path.join(HARNESS, "cp")
HARNESS_CLASSES = os.path.join(HARNESS, "classes")
HARNESS_SRC = os.path.join(HERE, "harness", "src")

CP_PREFIXES = ("org/pf4j/", "org/slf4j/", "com/xuncorp/spw/workshop/", "kotlin/")


def resolve_api_jar():
    """定位编译期 API jar。

    构建脚本按来源分文件存放（`spw-workshop-api-<source>.jar`），避免互相覆盖。
    优先用 `SPW_API_JAR` 显式指定；否则优先 host，其次 jitpack，最后兼容旧命名。
    """
    explicit = (os.environ.get("SPW_API_JAR") or "").strip()
    if explicit:
        if not os.path.exists(explicit):
            raise SystemExit("SPW_API_JAR 指向的文件不存在: " + explicit)
        log("API jar（来自 SPW_API_JAR）: " + explicit)
        return explicit
    api_dir = os.path.join(BUILD, "api")
    for name in ("spw-workshop-api-host.jar", "spw-workshop-api-jitpack.jar",
                 "spw-workshop-api.jar"):
        candidate = os.path.join(api_dir, name)
        if os.path.exists(candidate):
            log("API jar: " + os.path.relpath(candidate, ROOT))
            return candidate
    raise SystemExit("找不到编译期 API jar，请先执行 tools/build.py")


def extract_runtime_cp(install_dir):
    host = os.path.join(install_dir, "app", HOST_JAR)
    if not os.path.exists(host):
        raise SystemExit("找不到宿主程序包: " + host)
    if os.path.isdir(CP_DIR):
        shutil.rmtree(CP_DIR)
    count = 0
    with zipfile.ZipFile(host) as source:
        for info in source.infolist():
            if not info.filename.endswith(".class"):
                continue
            if not info.filename.startswith(CP_PREFIXES):
                continue
            target = os.path.join(CP_DIR, info.filename.replace("/", os.sep))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with open(target, "wb") as handle:
                handle.write(source.read(info.filename))
            count += 1
    log("抽取运行期类 %d 个 -> %s" % (count, os.path.relpath(CP_DIR, ROOT)))


def collect_harness_sources():
    sources = []
    for current, _dirs, files in os.walk(HARNESS_SRC):
        for name in files:
            if name.endswith(".java"):
                sources.append(os.path.join(current, name))
    return sorted(sources)


def compile_harness(jdk):
    javac = os.path.join(jdk, "bin", "javac.exe")
    if not os.path.exists(javac):
        javac = os.path.join(jdk, "bin", "javac")
    if os.path.isdir(HARNESS_CLASSES):
        shutil.rmtree(HARNESS_CLASSES)
    os.makedirs(HARNESS_CLASSES, exist_ok=True)
    classpath = os.pathsep.join([
        os.path.join(BUILD, "classes"),
        resolve_api_jar(),
        os.path.join(HERE, "lib", "pf4j-3.12.0.jar"),
        CP_DIR,
    ])
    sources = collect_harness_sources()
    command = [javac, "-encoding", "UTF-8", "-nowarn",
               "-J-Duser.language=en", "-J-Duser.country=US", "-J-Dfile.encoding=UTF-8",
               "-cp", classpath, "-d", HARNESS_CLASSES] + sources
    log("编译验证代码 %d 个" % len(sources))
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", "replace")
    if output.strip():
        print(output)
    if result.returncode != 0:
        raise SystemExit("验证代码编译失败")


def run_main(jdk, main_class, extra_classpath, extra_args, expect_success=True):
    java = os.path.join(jdk, "bin", "java.exe")
    if not os.path.exists(java):
        java = os.path.join(jdk, "bin", "java")
    classpath = os.pathsep.join(extra_classpath)
    command = [java, "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8",
               "-Dstderr.encoding=UTF-8", "-cp", classpath, main_class] + extra_args
    log("运行 " + main_class + (" " + " ".join(extra_args) if extra_args else ""))
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=180)
    output = result.stdout.decode("utf-8", "replace")
    print(output)
    if expect_success and result.returncode != 0:
        raise SystemExit("%s 退出码 %d" % (main_class, result.returncode))
    return result.returncode


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    install_dir = (os.environ.get("SPW_INSTALL") or "").strip()
    if not install_dir:
        raise SystemExit("请先设置 SPW_INSTALL 环境变量（指向 Salt Player for Windows 安装目录）；"
                         "构建与验证脚本均不再内置默认安装路径")
    jdk = locate_jdk()
    if jdk is None:
        raise SystemExit("未找到 JDK，请设置 JDK_HOME")

    if not os.path.isdir(os.path.join(BUILD, "classes")):
        raise SystemExit("请先执行 tools/build.py 生成插件产物")
    if not os.path.isdir(os.path.join(BUILD, "gen", "spmod")):
        raise SystemExit("缺少 build/gen/spmod，请先执行 tools/build.py")

    extract_runtime_cp(install_dir)
    compile_harness(jdk)

    base_cp = [
        HARNESS_CLASSES,
        os.path.join(BUILD, "classes"),
        resolve_api_jar(),
        os.path.join(HERE, "lib", "pf4j-3.12.0.jar"),
        CP_DIR,
    ]

    if mode in ("preview", "all"):
        run_main(jdk, "com.spw.miniplayer.DevPreview", base_cp, [HARNESS])
    if mode == "live":
        # 真机预览：会短暂在桌面右下角显示窗口并截屏
        run_main(jdk, "com.spw.miniplayer.LivePreview", base_cp, [HARNESS])
    if mode == "stress":
        # 展开/收起压力测试：会反复在桌面右上角闪烁小窗
        run_main(jdk, "com.spw.miniplayer.StressTest", base_cp,
                 [sys.argv[2] if len(sys.argv) > 2 else "160", HARNESS])
    if mode in ("config", "all"):
        # 配置语义探针：直接使用宿主包内的真实 ConfigHelper 实现
        host_jar = os.path.join(install_dir, "app", HOST_JAR)
        config_cp = [HARNESS_CLASSES, os.path.join(BUILD, "classes"),
                     resolve_api_jar(), host_jar]
        run_main(jdk, "com.spw.miniplayer.HostConfigProbe", config_cp, [])
    if mode == "delay":
        # 自动收起延迟的行为验证：真实窗口 + 真实计时
        run_main(jdk, "com.spw.miniplayer.HideDelayTest", base_cp, [])
    if mode == "deadlock":
        # 卡死根因：两个模式必须分别跑在独立 JVM（一个类只会被初始化一次）
        print("== 旧实现（持锁解析宿主类）应复现死锁 ==")
        legacy_code = run_main(jdk, "com.spw.miniplayer.DeadlockRepro", base_cp, ["legacy"],
                               expect_success=False)
        print("== 当前实现应正常完成 ==")
        fixed_code = run_main(jdk, "com.spw.miniplayer.DeadlockRepro", base_cp, ["fixed"],
                              expect_success=False)
        if legacy_code != 0:
            raise SystemExit("未能复现旧实现的死锁（退出码 %d）" % legacy_code)
        if fixed_code != 0:
            raise SystemExit("修复后的实现未通过（退出码 %d）" % fixed_code)
        print("卡死根因复现 + 修复验证 均通过 ✅")
    if mode in ("load", "all"):
        # PF4J 验证不把插件自身类放在系统 classpath，确保完全由插件类加载器加载
        clean_cp = [
            HARNESS_CLASSES,
            resolve_api_jar(),
            os.path.join(HERE, "lib", "pf4j-3.12.0.jar"),
            CP_DIR,
        ]
        run_main(jdk, "harness.PluginLoadTest", clean_cp,
                 [HARNESS, os.path.join(BUILD, "gen", "spmod")])


if __name__ == "__main__":
    main()
