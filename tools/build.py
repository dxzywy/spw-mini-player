#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""构建 Salt Player for Windows 迷你播放器插件（.spmod / .zip）。

流程：
  1. 解析编译期 API 来源（--api=host|jitpack|auto）；
  2. 使用 JDK 编译 src/main/java；
  3. 组装 classes/ + lib/ + classes/LICENSES/ 并写入 MANIFEST、extensions.idx、services 描述；
  4. 打包为 build/dist/plugin-<id>-<version>.spmod 与同名 .zip。

版本号只有一个来源：仓库根目录的 VERSION 文件。MANIFEST 的 Plugin-Version、产物文件名、
宿主工坊里显示的版本全部由它派生；插件运行期也从自身 MANIFEST 读取，不存在需要手工同步的第二处。

编译期 API 来源（--api，默认 auto）：
  host      从宿主安装包抽取 workshop API 类。要求 SPW_INSTALL 已设置且指向有效安装目录；
            未设置或无效 -> 立即报错退出，不回退。
  jitpack   从 JitPack 下载子模块产物，不要求本机装有 SPW（供第三方在无宿主环境编译）。
  auto      先试 host，失败再试 jitpack；两者都失败则列出各自原因后退出。
  硬性规则：Release 附件一律使用显式 --api=host 构建（JitPack 的 API 面比宿主新，存在
  NoSuchMethodError 风险）。

升级版本（构建前自动改写 VERSION 并重新打包）：
  python tools/build.py --bump patch     # 1.1.0 -> 1.1.1
  python tools/build.py --bump minor
  python tools/build.py --bump major
  python tools/build.py --set 1.2.3
  python tools/build.py --accept-new-hash     # 依赖哈希变化时人工确认放行

环境变量：
  SPW_INSTALL   宿主安装目录（host 模式必需；脚本不做任何路径猜测）
  JDK_HOME      JDK 根目录
"""

import hashlib
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

PLUGIN_ID = "com.spw.miniplayer"
PLUGIN_CLASS = "com.spw.miniplayer.MiniPlayerPlugin"
PLUGIN_NAME = "迷你贴边播放器"
PLUGIN_PROVIDER = "dxzywy"
PLUGIN_DESCRIPTION = ("可贴边隐藏的小窗播放器：桌面歌词开关、播放/暂停、"
                      "上一首/下一首、迷你封面与歌曲信息")
PLUGIN_SOURCE_URL = "https://github.com/dxzywy/spw-mini-player"
PLUGIN_HAS_CONFIG = "true"

EXTENSION_CLASS = "com.spw.miniplayer.MiniPlayerExtension"
EXTENSION_POINT = "com.xuncorp.spw.workshop.api.PlaybackExtensionPoint"
KOTLIN_STDLIB_NAME = "kotlin-stdlib-2.0.21.jar"

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(BUILD, "dist")
GEN = os.path.join(BUILD, "gen")
CLASSES_OUT = os.path.join(BUILD, "classes")
VERSION_FILE = os.path.join(ROOT, "VERSION")

# 宿主安装目录**不再内置默认值**：host 模式的有效性完全由 SPW_INSTALL 决定。
HOST_JAR = "ffmpeg-x64.dll"

# ---------------------------------------------------------------- 依赖与校验
# 每个依赖固定 SHA-256。上游只发布 SHA-1（Maven Central / JitPack 均无 .sha256），
# 因此可信链条是：先用上游 SHA-1 一次性交叉核对产物真伪，再把实测 SHA-256 固定为强制校验值。
DEPENDENCIES = {
    "pf4j": {
        "file": "pf4j-3.12.0.jar",
        "url": ("https://repo1.maven.org/maven2/org/pf4j/pf4j/3.12.0/"
                "pf4j-3.12.0.jar"),
        "sha256": "e4af5f0e9a9b11ec187c084ed819cedede7f27076ac5cf6aa2333eceadb57215",
    },
    "kotlin-stdlib": {
        "file": KOTLIN_STDLIB_NAME,
        "url": ("https://repo1.maven.org/maven2/org/jetbrains/kotlin/"
                "kotlin-stdlib/2.0.21/kotlin-stdlib-2.0.21.jar"),
        "sha256": "f31cc53f105a7e48c093683bbd5437561d1233920513774b470805641bedbc09",
    },
    "spw-workshop-api": {
        "file": "spw-workshop-api-0.1.0-dev21.jar",
        # 必须使用**子模块**坐标：聚合坐标产出的 jar 只有 MANIFEST、零个类
        "url": ("https://jitpack.io/com/github/Moriafly/spw-workshop-api/"
                "spw-workshop-api/0.1.0-dev21/spw-workshop-api-0.1.0-dev21.jar"),
        "sha256": "f12c222e6e7f5f970f79e0343aef312ec9d8dd16c6731f1658591b4f8b891d52",
    },
}

API_MODES = ("host", "jitpack", "auto")
ACCEPT_NEW_HASH = False

# 随发行包分发的第三方许可声明（Apache-2.0 §4(a) 要求向接收者提供许可证副本）
LICENSES_SOURCE_DIR = os.path.join(ROOT, "plugin", "LICENSES")
LICENSES_FILES = ("Apache-2.0.txt", "Kotlin-NOTICE.txt", "THIRD-PARTY-NOTICES.md")

VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")

# 版本号只允许出现在 VERSION 文件里；插件与验证代码必须动态读取，
# 否则「每次更新同步改版本号」迟早会漏。构建时强制校验。
VERSION_SCAN_DIRS = [os.path.join(ROOT, "src"), os.path.join(HERE, "harness", "src")]
VERSION_LITERAL = re.compile(r"\b\d+\.\d+\.\d+\b")
VERSION_LITERAL_ALLOW = "version-literal-ok"


class DependencyError(Exception):
    """依赖下载 / 校验失败（一律 fail-closed）。"""


class ApiSourceError(Exception):
    """编译期 API 来源获取失败，kind 用于 fallback_reason。"""

    def __init__(self, kind, detail):
        super().__init__("%s | %s" % (kind, detail))
        self.kind = kind
        self.detail = detail


def log(message):
    print("[build] " + message)


def read_version():
    """读取 VERSION 文件（唯一版本来源）。"""
    if not os.path.exists(VERSION_FILE):
        raise SystemExit("缺少版本文件: " + VERSION_FILE)
    with open(VERSION_FILE, "r", encoding="utf-8") as handle:
        version = handle.read().strip()
    if not VERSION_PATTERN.match(version):
        raise SystemExit("VERSION 内容不是 x.y.z 形式: " + repr(version))
    return version


def write_version(version):
    if not VERSION_PATTERN.match(version):
        raise SystemExit("版本号必须是 x.y.z 形式: " + repr(version))
    with open(VERSION_FILE, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(version + "\n")
    log("版本号已更新 -> " + version)


def bump_version(current, part):
    major, minor, patch = (int(piece) for piece in current.split("."))
    if part == "major":
        return "%d.0.0" % (major + 1)
    if part == "minor":
        return "%d.%d.0" % (major, minor + 1)
    if part == "patch":
        return "%d.%d.%d" % (major, minor, patch + 1)
    raise SystemExit("未知的版本位: " + part)


def verify_no_version_literals():
    """确保版本号没有被硬编码进插件源码或验证代码。"""
    offenders = []
    for base in VERSION_SCAN_DIRS:
        if not os.path.isdir(base):
            continue
        for current, _dirs, files in os.walk(base):
            for name in files:
                if not name.endswith(".java"):
                    continue
                path = os.path.join(current, name)
                with open(path, "r", encoding="utf-8") as handle:
                    for number, line in enumerate(handle, 1):
                        if VERSION_LITERAL.search(line) and VERSION_LITERAL_ALLOW not in line:
                            offenders.append("%s:%d  %s"
                                             % (os.path.relpath(path, ROOT), number,
                                                line.strip()))
    if offenders:
        print("检测到硬编码的版本号，请改为从 MANIFEST 动态读取：")
        for item in offenders:
            print("  " + item)
        raise SystemExit("存在硬编码版本号，构建中止")


PLUGIN_VERSION = read_version()


def first_existing(*paths):
    for path in paths:
        if path and os.path.exists(path):
            return path
    return None


def locate_jdk():
    candidates = [
        os.environ.get("JDK_HOME"),
        r"C:\Program Files\Java\jdk-25",
    ]
    for root in (r"C:\Program Files\Eclipse Adoptium",
                 os.path.join(os.environ.get("TEMP", r"C:\Windows\Temp"),
                              "tools", "jdk25")):
        if os.path.isdir(root):
            for entry in sorted(os.listdir(root)):
                candidates.append(os.path.join(root, entry))
    jdk = first_existing(*[c for c in candidates if c])
    if jdk is None:
        return None
    javac = os.path.join(jdk, "bin", "javac.exe")
    if not os.path.exists(javac):
        javac = os.path.join(jdk, "bin", "javac")
    return jdk if os.path.exists(javac) else None


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def place_file(temporary, target):
    """原子就位；目标被占用时降级为直接写入并告警（不静默失败）。"""
    try:
        os.replace(temporary, target)
        return "atomic"
    except OSError as error:
        log("警告：无法原子替换 %s（%s），降级为直接写入" % (target, error))
    with open(temporary, "rb") as source:
        data = source.read()
    try:
        with open(target, "wb") as handle:
            handle.write(data)
    except OSError as error:
        raise DependencyError("无法写入依赖文件 %s: %s" % (target, error))
    try:
        os.remove(temporary)
    except OSError:
        pass
    return "direct"


def download_verified(name, url, expected_sha256, target):
    """下载 -> 校验 -> 原子就位。任何异常都不静默降级。"""
    os.makedirs(os.path.dirname(target), exist_ok=True)
    temporary = target + ".part"
    if os.path.exists(temporary):
        os.remove(temporary)
    log("下载依赖 %s <- %s" % (name, url))
    request = urllib.request.Request(url, headers={"User-Agent": "spw-miniplayer-build"})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            status = getattr(response, "status", 200)
            if status != 200:
                raise DependencyError("%s HTTP %s（期望 200）: %s" % (name, status, url))
            data = response.read()
    except DependencyError:
        raise
    except Exception as error:  # 网络 / 代理 / TLS 等
        raise DependencyError("%s 下载失败: %s: %s\n  URL: %s\n"
                              "  请按方案「代理」条核对链路后重试"
                              % (name, type(error).__name__, error, url))
    if not data:
        raise DependencyError("%s 下载内容为空: %s" % (name, url))
    with open(temporary, "wb") as handle:
        handle.write(data)
    if not zipfile.is_zipfile(temporary):
        os.remove(temporary)
        raise DependencyError("%s 下载内容不是 ZIP/JAR（防住聚合坐标空产物）: %s"
                              % (name, url))
    with zipfile.ZipFile(temporary) as archive:
        class_count = len([n for n in archive.namelist() if n.endswith(".class")])
    if class_count == 0:
        os.remove(temporary)
        raise DependencyError("%s 内不含任何 class 条目，拒绝使用: %s" % (name, url))
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha256:
        if ACCEPT_NEW_HASH:
            log("警告：%s SHA-256 与固定值不符，已按 --accept-new-hash 放行\n"
                "  固定值: %s\n  实际值: %s\n  请人工确认后更新脚本中的常量"
                % (name, expected_sha256, actual))
        else:
            os.remove(temporary)
            raise DependencyError(
                "%s SHA-256 不匹配\n  期望: %s\n  实际: %s\n  文件: %s\n"
                "  （JitPack 的 dev 版本可能被重建；确需接受请人工确认后加 --accept-new-hash）"
                % (name, expected_sha256, actual, target))
    mode = place_file(temporary, target)
    log("依赖就位 %s（%d 个 class，SHA-256 已校验，就位方式=%s）"
        % (target, class_count, mode))
    return target


def ensure_dependency(key):
    """按需下载并强制校验依赖；已存在也要校验哈希。"""
    spec = DEPENDENCIES[key]
    target = os.path.join(HERE, "lib", spec["file"])
    if os.path.exists(target):
        actual = sha256_file(target)
        if actual == spec["sha256"]:
            log("依赖已就位 %s（SHA-256 校验通过）" % os.path.relpath(target, ROOT))
            return target
        if not ACCEPT_NEW_HASH:
            raise DependencyError(
                "已存在的依赖哈希与固定值不符（拒绝使用）\n  文件: %s\n  期望: %s\n  实际: %s"
                % (target, spec["sha256"], actual))
        log("警告：%s 哈希与固定值不符，已按 --accept-new-hash 放行" % key)
        return target
    return download_verified(key, spec["url"], spec["sha256"], target)


# ---------------------------------------------------------------- API 来源
def api_jar_path(source):
    """按来源分文件：避免不同来源互相覆盖（也便于并排比对）。

    Windows 上文件可能被杀毒扫描或残留 JVM 短暂占用，覆盖同一路径会失败，
    因此每个来源使用独立文件名。
    """
    return os.path.join(BUILD, "api", "spw-workshop-api-%s.jar" % source)


def extract_api_from_host(target_jar):
    """从宿主包抽取 workshop API 类。失败抛 ApiSourceError（kind 用于回退原因）。"""
    install = (os.environ.get("SPW_INSTALL") or "").strip()
    if not install:
        raise ApiSourceError("spw_install_unset",
                             "环境变量 SPW_INSTALL 未设置（脚本已删除默认路径，不猜安装位置）")
    if not os.path.isdir(install):
        raise ApiSourceError("spw_install_invalid", "SPW_INSTALL 不是目录: " + install)
    host = os.path.join(install, "app", HOST_JAR)
    if not os.path.exists(host):
        raise ApiSourceError("spw_install_invalid", "找不到宿主程序包: " + host)
    prefix = "com/xuncorp/spw/workshop/"
    try:
        os.makedirs(os.path.dirname(target_jar), exist_ok=True)
        written = 0
        with zipfile.ZipFile(host) as source, zipfile.ZipFile(
                target_jar, "w", zipfile.ZIP_DEFLATED) as target:
            for info in source.infolist():
                if info.filename.startswith(prefix) and info.filename.endswith(".class"):
                    target.writestr(info, source.read(info.filename))
                    written += 1
    except (zipfile.BadZipFile, OSError, EOFError) as error:
        raise ApiSourceError("host_extract_error:%s" % type(error).__name__,
                             "读取宿主包失败: %s" % error)
    except Exception as error:  # 其他未归类异常，同样归类输出而非吞掉
        raise ApiSourceError("host_extract_error:%s" % type(error).__name__,
                             "抽取过程中出现未预期异常: %s" % error)
    if written == 0:
        raise ApiSourceError("host_api_empty",
                             "宿主包结构与预期不符，未抽取到任何 workshop API 类: " + host)
    log("API 来源 host：从宿主包抽取 %d 个类 -> %s"
        % (written, os.path.relpath(target_jar, ROOT)))
    return written


def fetch_api_from_jitpack(target_jar):
    """从 JitPack 下载子模块 API 产物并强制校验。"""
    spec = DEPENDENCIES["spw-workshop-api"]
    actual = sha256_file(target_jar) if os.path.exists(target_jar) else None
    if actual == spec["sha256"]:
        log("API 来源 jitpack：缓存已就位且哈希通过 -> "
            + os.path.relpath(target_jar, ROOT))
        return 0
    try:
        download_verified("spw-workshop-api", spec["url"], spec["sha256"], target_jar)
    except DependencyError as error:
        raise ApiSourceError("jitpack_download_error", str(error))
    with zipfile.ZipFile(target_jar) as archive:
        classes = [n for n in archive.namelist() if n.endswith(".class")]
    if not classes:
        raise ApiSourceError("jitpack_empty_jar", "下载的 API jar 不含任何 class")
    log("API 来源 jitpack：%d 个 class -> %s"
        % (len(classes), os.path.relpath(target_jar, ROOT)))
    return len(classes)


SOURCES = {"host": extract_api_from_host, "jitpack": fetch_api_from_jitpack}


def resolve_api(mode):
    """按模式解析 API 来源，返回 (api_source, attempts, fallback_reason, api_jar)。"""
    if mode not in API_MODES:
        raise SystemExit("未知的 --api 取值: %s（可选 %s）" % (mode, "|".join(API_MODES)))

    attempts = []
    if mode in ("host", "jitpack"):
        # 显式指定 = 强约束：发生任何错误都不得切换到另一来源
        target = api_jar_path(mode)
        try:
            SOURCES[mode](target)
        except ApiSourceError as error:
            print_api_failure(mode, [("%s" % mode, error)], mode)
            raise SystemExit("显式 --api=%s 失败，按强约束不切换来源，构建中止" % mode)
        attempts.append("%s:ok" % mode)
        return mode, attempts, "none", target

    # auto：host 先试，失败回退 jitpack
    fallback_reason = "none"
    host_jar = api_jar_path("host")
    try:
        extract_api_from_host(host_jar)
        attempts.append("host:ok")
        return "host", attempts, fallback_reason, host_jar
    except ApiSourceError as host_error:
        attempts.append("host:fail(%s)" % host_error.kind)
        log("API 来源 host 失败：%s | %s" % (host_error.kind, host_error.detail))
        fallback_reason = host_error.kind
        log("auto 模式按规则回退到 jitpack（fallback_reason=%s）" % fallback_reason)
    jitpack_jar = api_jar_path("jitpack")
    try:
        fetch_api_from_jitpack(jitpack_jar)
        attempts.append("jitpack:ok")
        log("=" * 72)
        log("警告：本次为 JitPack 构建（api_source=jitpack）")
        log("      JitPack 的 API 面比宿主运行时新，编译目标面不同，")
        log("      不得用于发布 Release。Release 必须使用显式 --api=host。")
        log("=" * 72)
        return "jitpack", attempts, fallback_reason, jitpack_jar
    except ApiSourceError as jitpack_error:
        attempts.append("jitpack:fail(%s)" % jitpack_error.kind)
        print_api_failure("auto", [("host", ApiSourceError(fallback_reason, "见上方日志")),
                                   ("jitpack", jitpack_error)], "auto")
        raise SystemExit("host 与 jitpack 均失败，构建中止")


def print_api_failure(source, failures, mode):
    """退出前必须打印三要素：API 来源、失败原因、回退行为。"""
    print("=" * 72)
    print("[build] 编译期 API 来源获取失败")
    print("  API 来源: " + source + "（模式 --api=%s）" % mode)
    print("  失败原因:")
    for label, error in failures:
        print("    - %s: %s" % (label, error.kind))
        print("      " + error.detail.replace("\n", "\n      "))
    if mode == "auto":
        print("  回退行为: 已按 auto 规则尝试 host -> jitpack，两者均失败，未再回退")
    else:
        print("  回退行为: 未回退（显式 --api=%s 为强约束）" % mode)
    print("  环境提示: SPW_INSTALL=%r" % (os.environ.get("SPW_INSTALL"),))
    print("  环境提示: git 代理=%r  curl/网络代理=%r"
          % (os.environ.get("https_proxy"), os.environ.get("HTTPS_PROXY")))
    print("  手动指定: python tools/build.py --api=host   或   --api=jitpack")
    print("=" * 72)


def collect_sources():
    sources = []
    base = os.path.join(ROOT, "src", "main", "java")
    for current, _dirs, files in os.walk(base):
        for name in files:
            if name.endswith(".java"):
                sources.append(os.path.join(current, name))
    return sorted(sources)


def compile_java(jdk, api_jar, pf4j_jar, sources):
    javac = os.path.join(jdk, "bin", "javac.exe")
    if not os.path.exists(javac):
        javac = os.path.join(jdk, "bin", "javac")
    if os.path.isdir(CLASSES_OUT):
        shutil.rmtree(CLASSES_OUT)
    os.makedirs(CLASSES_OUT, exist_ok=True)
    classpath = os.pathsep.join([api_jar, pf4j_jar])
    command = [javac, "--release", "21", "-encoding", "UTF-8", "-Xlint:-options",
               "-J-Duser.language=en", "-J-Duser.country=US", "-J-Duser.encoding=UTF-8",
               "-cp", classpath, "-d", CLASSES_OUT] + sources
    log("编译 %d 个源文件" % len(sources))
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output = result.stdout.decode("utf-8", "replace")
    if output.strip():
        print(output)
    if result.returncode != 0:
        raise SystemExit("编译失败")
    log("编译完成 -> " + os.path.relpath(CLASSES_OUT, ROOT))


def _wrap_utf8(text, first_limit=72, cont_limit=71):
    """按 UTF-8 字符边界折行，绝不切断多字节字符。"""
    lines = []
    current = b""
    limit = first_limit
    for char in text:
        encoded = char.encode("utf-8")
        if current and len(current) + len(encoded) > limit:
            lines.append(current)
            current = b""
            limit = cont_limit
        current += encoded
    lines.append(current)
    return lines


def manifest_text(with_extended_header):
    """生成 MANIFEST.MF，遵循 jar 规范的 72 字节折行。"""
    header = ["Manifest-Version: 1.0"]
    if with_extended_header:
        header += [
            "Plugin-Class: " + PLUGIN_CLASS,
            "Plugin-Id: " + PLUGIN_ID,
            "Plugin-Name: " + PLUGIN_NAME,
            "Plugin-Version: " + PLUGIN_VERSION,
            "Plugin-Provider: " + PLUGIN_PROVIDER,
            "Plugin-Description: " + PLUGIN_DESCRIPTION,
            "Plugin-Open-Source-Url: " + PLUGIN_SOURCE_URL,
            "Plugin-Has-Config: " + PLUGIN_HAS_CONFIG,
        ]
    lines = []
    for entry in header:
        parts = _wrap_utf8(entry)
        lines.append(parts[0])
        for part in parts[1:]:
            lines.append(b" " + part)
    return b"\r\n".join(lines) + b"\r\n\r\n"


def write_text(path, content):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as handle:
        handle.write(content)


def copy_licenses(classes_dir):
    """把许可声明放进发行包（Apache-2.0 §4(a)：必须向接收者提供许可证副本）。"""
    missing = [name for name in LICENSES_FILES
               if not os.path.exists(os.path.join(LICENSES_SOURCE_DIR, name))]
    if missing:
        raise SystemExit("缺少随包分发的许可文件（应位于 plugin/LICENSES/）: "
                         + ", ".join(missing))
    target = os.path.join(classes_dir, "LICENSES")
    os.makedirs(target, exist_ok=True)
    for name in LICENSES_FILES:
        shutil.copy2(os.path.join(LICENSES_SOURCE_DIR, name),
                     os.path.join(target, name))
    log("随包许可声明 %d 个 -> classes/LICENSES/" % len(LICENSES_FILES))


def assemble(kotlin_stdlib):
    """组装 .spmod 目录结构。"""
    if os.path.isdir(GEN):
        shutil.rmtree(GEN)
    spmod = os.path.join(GEN, "spmod")
    classes_dir = os.path.join(spmod, "classes")
    lib_dir = os.path.join(spmod, "lib")
    os.makedirs(classes_dir, exist_ok=True)
    os.makedirs(lib_dir, exist_ok=True)

    # 编译产物
    for current, _dirs, files in os.walk(CLASSES_OUT):
        relative = os.path.relpath(current, CLASSES_OUT)
        target = classes_dir if relative == "." else os.path.join(classes_dir, relative)
        os.makedirs(target, exist_ok=True)
        for name in files:
            shutil.copy2(os.path.join(current, name), os.path.join(target, name))

    # 插件描述符：classes/META-INF/MANIFEST.MF（插件类路径上）
    meta_dir = os.path.join(classes_dir, "META-INF")
    os.makedirs(meta_dir, exist_ok=True)
    with open(os.path.join(meta_dir, "MANIFEST.MF"), "wb") as handle:
        handle.write(manifest_text(True))
    # 根 MANIFEST.MF：宿主的目录式描述符查找器会读取它
    root_meta = os.path.join(spmod, "META-INF")
    os.makedirs(root_meta, exist_ok=True)
    with open(os.path.join(root_meta, "MANIFEST.MF"), "wb") as handle:
        handle.write(manifest_text(True))

    # 拓展点声明：同时提供 PF4J 的两种发现机制
    write_text(os.path.join(meta_dir, "extensions.idx"),
               "# Generated by PF4J\n" + EXTENSION_CLASS + "\n")
    write_text(os.path.join(meta_dir, "services", EXTENSION_POINT),
               EXTENSION_CLASS + "\n")

    # 配置界面声明
    shutil.copy2(os.path.join(ROOT, "plugin", "preference_config.json"),
                 os.path.join(classes_dir, "preference_config.json"))

    # 随包许可声明
    copy_licenses(classes_dir)

    # 运行期依赖
    if kotlin_stdlib and os.path.exists(kotlin_stdlib):
        shutil.copy2(kotlin_stdlib, os.path.join(lib_dir, KOTLIN_STDLIB_NAME))
    else:
        raise SystemExit("缺少 kotlin-stdlib，发行包将无法运行，构建中止")

    return spmod


def zip_dir(source_dir, target_zip):
    """按宿主导入包的布局打包，目录项使用 '/' 结尾。"""
    os.makedirs(os.path.dirname(target_zip), exist_ok=True)
    if os.path.exists(target_zip):
        os.remove(target_zip)
    entries = []
    for current, dirs, files in os.walk(source_dir):
        dirs.sort()
        relative = os.path.relpath(current, source_dir).replace("\\", "/")
        if relative != ".":
            entries.append((relative + "/", None))
        for name in sorted(files):
            full = os.path.join(current, name)
            relative_file = os.path.relpath(full, source_dir).replace("\\", "/")
            entries.append((relative_file, full))
    with zipfile.ZipFile(target_zip, "w", zipfile.ZIP_DEFLATED) as archive:
        for name, path in entries:
            if path is None:
                info = zipfile.ZipInfo(name)
                info.external_attr = 0o40755 << 16
                archive.writestr(info, b"")
            else:
                archive.write(path, name)
    return len(entries)


def read_manifest_entry(archive, entry, key):
    """从 zip 内的 MANIFEST.MF 解析指定键（按 jar 规范还原 72 字节折行）。"""
    raw = archive.read(entry).decode("utf-8")
    unfolded = raw.replace("\r\n ", "").replace("\n ", "")
    for line in unfolded.splitlines():
        if line.startswith(key + ":"):
            return line.split(":", 1)[1].strip()
    return None


def verify_artifact(spmod_path, zip_path):
    """校验产物：版本、Provider、开源地址、文件名、两份 MANIFEST 一致性。"""
    with zipfile.ZipFile(spmod_path) as archive:
        for entry in ("META-INF/MANIFEST.MF", "classes/META-INF/MANIFEST.MF"):
            version = read_manifest_entry(archive, entry, "Plugin-Version")
            if version != PLUGIN_VERSION:
                raise SystemExit("%s 中的 Plugin-Version=%s，期望 %s"
                                 % (entry, version, PLUGIN_VERSION))
            provider = read_manifest_entry(archive, entry, "Plugin-Provider")
            if provider != PLUGIN_PROVIDER:
                raise SystemExit("%s 中的 Plugin-Provider=%s，期望 %s"
                                 % (entry, provider, PLUGIN_PROVIDER))
            url = read_manifest_entry(archive, entry, "Plugin-Open-Source-Url")
            if url != PLUGIN_SOURCE_URL:
                raise SystemExit("%s 中的 Plugin-Open-Source-Url=%s，期望 %s"
                                 % (entry, url, PLUGIN_SOURCE_URL))
    expected = "plugin-%s-%s" % (PLUGIN_ID, PLUGIN_VERSION)
    for path in (spmod_path, zip_path):
        if os.path.splitext(os.path.basename(path))[0] != expected:
            raise SystemExit("产物文件名与版本号不一致: " + path)
    log("一致性校验通过：两处 MANIFEST 的 Version/Provider/Open-Source-Url 与常量一致")
    log("派生值：tag 名 = v%s ｜ Release 附件名 = %s.zip" % (PLUGIN_VERSION, expected))


def cleanup_old_artifacts(keep_base_name):
    """删除 build/dist 里旧版本的插件包，避免误导入过期产物。"""
    if not os.path.isdir(DIST):
        return
    removed = 0
    for name in sorted(os.listdir(DIST)):
        if not (name.endswith(".spmod") or name.endswith(".zip")):
            continue
        if not name.startswith("plugin-" + PLUGIN_ID + "-"):
            continue
        if os.path.splitext(name)[0] == keep_base_name:
            continue
        os.remove(os.path.join(DIST, name))
        removed += 1
    if removed:
        log("清理旧版本产物 %d 个" % removed)


def parse_args(argv):
    """解析 --bump/--set/--api/--accept-new-hash。"""
    global PLUGIN_VERSION, ACCEPT_NEW_HASH
    mode = "auto"
    index = 0
    while index < len(argv):
        item = argv[index]
        if item in ("--bump", "--set"):
            if index + 1 >= len(argv):
                raise SystemExit("用法: --bump patch|minor|major 或 --set x.y.z")
            value = argv[index + 1].strip()
            if item == "--set":
                PLUGIN_VERSION = value
            else:
                PLUGIN_VERSION = bump_version(PLUGIN_VERSION, value)
            write_version(PLUGIN_VERSION)
            index += 2
            continue
        if item == "--accept-new-hash":
            ACCEPT_NEW_HASH = True
            log("已启用 --accept-new-hash：依赖哈希不匹配时将放行（需人工确认）")
            index += 1
            continue
        if item == "--api":
            if index + 1 >= len(argv):
                raise SystemExit("用法: --api host|jitpack|auto")
            mode = argv[index + 1].strip()
            index += 2
            continue
        if item.startswith("--api="):
            mode = item.split("=", 1)[1].strip()
            index += 1
            continue
        raise SystemExit("未知参数: " + item)
    if mode not in API_MODES:
        raise SystemExit("未知的 --api 取值: %s（可选 %s）" % (mode, "|".join(API_MODES)))
    return mode


def main():
    mode = parse_args(sys.argv[1:])
    verify_no_version_literals()

    jdk = locate_jdk()
    if jdk is None:
        raise SystemExit("未找到 JDK，请设置 JDK_HOME 环境变量")
    log("构建版本 " + PLUGIN_VERSION)
    log("使用 JDK: " + jdk)
    log("API 模式: --api=%s" % mode)

    api_source, attempts, fallback_reason, api_jar = resolve_api(mode)
    log("编译期 API jar: " + os.path.relpath(api_jar, ROOT))
    log("API 来源尝试链: [%s]" % ", ".join(attempts))
    log("api_source=%s  api_source_attempts=%s  fallback_reason=%s"
        % (api_source, attempts, fallback_reason))
    if api_source == "jitpack":
        log("注意：本次产物来自 JitPack，禁止用于发布 Release")

    pf4j_jar = ensure_dependency("pf4j")
    kotlin_stdlib = ensure_dependency("kotlin-stdlib")

    compile_java(jdk, api_jar, pf4j_jar, collect_sources())
    spmod_dir = assemble(kotlin_stdlib)

    base_name = "plugin-%s-%s" % (PLUGIN_ID, PLUGIN_VERSION)
    spmod_path = os.path.join(DIST, base_name + ".spmod")
    zip_path = os.path.join(DIST, base_name + ".zip")
    cleanup_old_artifacts(base_name)
    count = zip_dir(spmod_dir, spmod_path)
    shutil.copy2(spmod_path, zip_path)
    log("打包 %d 个条目 -> %s" % (count, os.path.relpath(spmod_path, ROOT)))
    log("同时输出 -> " + os.path.relpath(zip_path, ROOT))
    verify_artifact(spmod_path, zip_path)
    log("产物 SHA-256：")
    log("  %s  %s" % (sha256_file(spmod_path), os.path.basename(spmod_path)))
    log("  %s  %s" % (sha256_file(zip_path), os.path.basename(zip_path)))
    log("最终：api_source=%s  api_source_attempts=%s  fallback_reason=%s"
        % (api_source, attempts, fallback_reason))
    print(spmod_path)
    print(zip_path)


if __name__ == "__main__":
    sys.exit(main())
