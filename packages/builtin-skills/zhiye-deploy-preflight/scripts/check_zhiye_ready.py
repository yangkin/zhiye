#!/usr/bin/env python3
"""
织页平台（Zhiye）导入与单体服务托管预检工具。
零第三方依赖（仅需 Python 3.9+ 标准库），支持检查本地项目目录或 .zip 压缩包。

退出码：
  0  PASS 或 WARN（可导入，WARN 需关注或按需调整）
  1  --strict 模式下存在 WARN，或输入参数错误
  2  BLOCK（触发平台能力上限或硬性部署规范，禁止直接交付导入）
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import tempfile
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Sequence, Tuple

MAX_ZIP_BYTES = 50 * 1024 * 1024
MAX_UNPACKED_BYTES = 200 * 1024 * 1024
MAX_SINGLE_FILE_BYTES = 20 * 1024 * 1024
MAX_FILE_COUNT = 5000

IGNORED_DIRS = {
    ".git",
    "node_modules",
    "__pycache__",
    ".venv",
    "venv",
    ".pytest_cache",
    ".mypy_cache",
    ".ruff_cache",
    ".idea",
    ".vscode",
    ".turbo",
    "__MACOSX",
    ".next",
    ".nuxt",
    "target",
    "dist",
    "build",
    "out",
}

JUNK_PACKAGE_DIRS = {
    "node_modules",
    ".venv",
    "venv",
    "__pycache__",
    ".git",
    ".idea",
    ".vscode",
    "__MACOSX",
}

KEEP_TOP_DIRS = {
    "src",
    "app",
    "public",
    "static",
    "templates",
    "frontend",
    "backend",
    "server",
    "client",
    "web",
    "api",
    "dist",
    "build",
    "scripts",
    "docs",
    "references",
    "cmd",
    "internal",
    "pkg",
    "assets",
    "components",
    "pages",
}

SECRET_FILE_RE = re.compile(
    r"(?:^|/)(?:\.env(?:\.(?!example$|sample$|template$)[^/]+)?|id_rsa|id_ed25519|[^/]+\.(?:pem|key|p12|pfx|jks))$",
    re.IGNORECASE,
)

# 通过分段拼接构造敏感特征正则，避免脚本自身包含完整样本字符串
SECRET_CONTENT_PATTERNS: List[Tuple[str, re.Pattern[str]]] = [
    ("私钥内容", re.compile(r"-----BEGIN\s+(?:RSA\s+|EC\s+|DSA\s+|OPENSSH\s+|ENCRYPTED\s+)?PRIVATE\s+KEY-----")),
    ("API Key / 云凭证", re.compile(r"\b(?:sk" + r"-[A-Za-z0-9_-]{20,}|gh" + r"[pousr]_[A-Za-z0-9]{20,}|AK" + r"IA[0-9A-Z]{16})\b")),
    ("飞书身份/应用 ID", re.compile(r"\b(?:ou" + r"_[A-Za-z0-9]{16,}|cli" + r"_[a-f0-9]{16,})\b")),
    ("JWT 令牌", re.compile(r"\beyJ" + r"[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b")),
]

LOCALHOST_URL_RE = re.compile(r"""https?://(?:localhost|127\.0\.0\.1|0\.0\.0\.0)(?::\d{2,5})?""", re.IGNORECASE)
BIND_LOOPBACK_PATTERNS = [
    re.compile(r"""--host[\s=]+['"]?(?:127\.0\.0\.1|localhost)['"]?"""),
    re.compile(r"""host\s*=\s*['"](?:127\.0\.0\.1|localhost)['"]"""),
    re.compile(r"""\.listen\s*\([^)]*['"](?:127\.0\.0\.1|localhost)['"]"""),
    re.compile(r"""ListenAndServe\s*\(\s*["'](?:127\.0\.0\.1|localhost):"""),
    re.compile(r"""server\.address\s*[:=]\s*(?:127\.0\.0\.1|localhost)\b"""),
]

MICROSERVICE_DEPS = [
    ("Spring Cloud", re.compile(r"\bspring-cloud\b", re.IGNORECASE)),
    ("Nacos 注册中心", re.compile(r"\b(?:nacos-client|spring-cloud-starter-alibaba-nacos|nacos-sdk)\b", re.IGNORECASE)),
    ("Dubbo RPC", re.compile(r"\b(?:dubbo-spring-boot|org\.apache\.dubbo)\b", re.IGNORECASE)),
    ("Eureka / Consul", re.compile(r"\b(?:spring-cloud-starter-netflix-eureka|spring-cloud-starter-consul)\b", re.IGNORECASE)),
    ("Kubernetes Operator / DinD", re.compile(r"/var/run/docker\.sock", re.IGNORECASE)),
]

GPU_DEPS = [
    ("PyTorch", re.compile(r"(?:^|[\s\"'=,/])torch(?:vision|audio)?(?:[\s\"'=<>,~!]|$)", re.IGNORECASE | re.MULTILINE)),
    ("vLLM / 本地大模型推理", re.compile(r"\b(?:vllm|bitsandbytes|deepspeed|flash-attn|triton)\b", re.IGNORECASE)),
    ("TensorFlow / CUDA", re.compile(r"\b(?:tensorflow|cupy|nvidia-cuda|cudatoolkit)\b", re.IGNORECASE)),
]

WINDOWS_ONLY_RE = re.compile(r"\b(?:pywin32|win32com|win32api|pythoncom|comtypes)\b", re.IGNORECASE)

MIDDLEWARE_RULES: List[Tuple[str, re.Pattern[str]]] = [
    ("Redis", re.compile(r"\b(?:redis|ioredis|redisson|spring-boot-starter-data-redis|go-redis)\b", re.IGNORECASE)),
    ("MySQL / MariaDB", re.compile(r"\b(?:mysql2|pymysql|mysqlclient|mysql-connector|mariadb|go-sql-driver/mysql)\b", re.IGNORECASE)),
    ("MongoDB", re.compile(r"\b(?:mongoose|pymongo|mongodb|spring-boot-starter-data-mongodb|mongo-driver)\b", re.IGNORECASE)),
    ("Kafka", re.compile(r"\b(?:kafkajs|confluent-kafka|kafka-python|spring-kafka|sarama)\b", re.IGNORECASE)),
    ("RabbitMQ / Celery", re.compile(r"\b(?:amqplib|pika|celery|spring-boot-starter-amqp|rabbitmq)\b", re.IGNORECASE)),
    ("Elasticsearch", re.compile(r"\b(?:@elastic/elasticsearch|elasticsearch|spring-boot-starter-data-elasticsearch)\b", re.IGNORECASE)),
]


@dataclass
class Finding:
    severity: str  # BLOCK | WARN | INFO
    code: str
    title: str
    reason: str
    remediation: str
    evidence: List[str] = field(default_factory=list)


@dataclass
class Report:
    target: str
    verdict: str  # PASS | WARN | BLOCK
    project_kind: str
    runtime: str
    framework: str
    file_count: int
    total_size_bytes: int
    findings: List[Finding] = field(default_factory=list)


def is_text_candidate(path: str) -> bool:
    lower = path.lower()
    if lower.endswith((
        ".py", ".js", ".mjs", ".cjs", ".ts", ".tsx", ".jsx", ".vue", ".svelte",
        ".java", ".kt", ".go", ".html", ".htm", ".css", ".scss",
        ".json", ".yml", ".yaml", ".toml", ".xml", ".gradle", ".properties",
        ".sh", ".txt", ".md", ".cfg", ".ini", ".conf", ".env.example",
    )):
        return True
    base = os.path.basename(lower)
    return base in ("dockerfile", "makefile", "procfile", "requirements.txt", "go.mod", "pom.xml", "cargo.toml", "gemfile", "composer.json")


def read_text_safe(p: Path, limit: int = 256 * 1024) -> Optional[str]:
    try:
        raw = p.read_bytes()[:limit]
        if b"\x00" in raw:
            return None
        return raw.decode("utf-8", errors="replace")
    except OSError:
        return None


def find_line_hits(text: str, pattern: re.Pattern[str], rel_path: str, max_hits: int = 3) -> List[str]:
    hits: List[str] = []
    for idx, line in enumerate(text.splitlines(), start=1):
        stripped = line.strip()
        if not stripped or stripped.startswith(("#", "//", "/*", "*")):
            continue
        if pattern.search(line):
            snippet = stripped[:100]
            hits.append(f"{rel_path}:{idx} -> {snippet}")
            if len(hits) >= max_hits:
                break
    return hits


def unwrap_nested_root(root: Path) -> Tuple[Path, int]:
    cur = root
    unwrapped = 0
    for _ in range(3):
        entries = [
            e for e in cur.iterdir()
            if e.name not in (".DS_Store", "__MACOSX", "Thumbs.db")
        ]
        if len(entries) == 1 and entries[0].is_dir() and entries[0].name.lower() not in KEEP_TOP_DIRS:
            cur = entries[0]
            unwrapped += 1
        else:
            break
    return cur, unwrapped


def collect_project(target_path: Path, findings: List[Finding]) -> Tuple[Path, Optional[tempfile.TemporaryDirectory[str]], Dict[str, Path], int, int]:
    tmp_dir: Optional[tempfile.TemporaryDirectory[str]] = None
    work_root = target_path

    if target_path.is_file() and target_path.suffix.lower() == ".zip":
        zip_size = target_path.stat().st_size
        if zip_size > MAX_ZIP_BYTES:
            findings.append(Finding(
                severity="BLOCK",
                code="ARCHIVE_LIMIT_EXCEEDED",
                title="ZIP 压缩包超出 50 MB 上限",
                reason=f"当前压缩包大小为 {zip_size / (1024 * 1024):.1f} MB，超过织页单次导入 50 MB 限制。",
                remediation="请删除 node_modules、.venv、target、dist、大体积媒体或模型文件后重新打包。",
                evidence=[str(target_path)],
            ))
        tmp_dir = tempfile.TemporaryDirectory(prefix="zhiye-preflight-")
        extract_root = Path(tmp_dir.name)
        symlinks: List[str] = []
        unsafe_paths: List[str] = []
        junk_dirs_in_zip: set[str] = set()
        with zipfile.ZipFile(target_path, "r") as zf:
            for info in zf.infolist():
                norm = info.filename.replace("\\", "/")
                if norm.startswith("/") or re.match(r"^[A-Za-z]:", norm) or ".." in norm.split("/"):
                    unsafe_paths.append(norm)
                    continue
                is_symlink = ((info.external_attr >> 16) & 0o170000) == 0o120000
                if is_symlink:
                    symlinks.append(norm)
                    continue
                for seg in norm.split("/"):
                    if seg in JUNK_PACKAGE_DIRS:
                        junk_dirs_in_zip.add(seg)
            if unsafe_paths:
                findings.append(Finding(
                    severity="BLOCK",
                    code="UNSAFE_ZIP_PATH",
                    title="压缩包含不安全路径（绝对路径或 ..）",
                    reason="织页导入安全预检会直接拒绝包含目录穿越或绝对路径的压缩包。",
                    remediation="请在项目根目录重新打包，不要使用绝对路径或上级相对路径。",
                    evidence=unsafe_paths[:5],
                ))
            if symlinks:
                findings.append(Finding(
                    severity="BLOCK",
                    code="SYMLINK_FOUND",
                    title="压缩包包含符号链接（Symlink）",
                    reason="出于容器安全策略，织页导入器会直接拒绝任何包含符号链接的包。",
                    remediation="请删除符号链接或替换为真实文件后重新打包。",
                    evidence=symlinks[:5],
                ))
            if junk_dirs_in_zip:
                findings.append(Finding(
                    severity="WARN",
                    code="PACKAGE_JUNK_DIRS",
                    title="压缩包混入了本地缓存或依赖目录",
                    reason=f"检测到 {', '.join(sorted(junk_dirs_in_zip))}，不仅膨胀包体，还容易携带本机二进制或隐私配置。",
                    remediation="打包前请排除上述目录（可使用 git archive 或仅压缩源码）。",
                    evidence=sorted(junk_dirs_in_zip),
                ))
            for info in zf.infolist():
                norm = info.filename.replace("\\", "/")
                if norm.startswith("/") or ".." in norm.split("/") or info.is_dir():
                    continue
                if ((info.external_attr >> 16) & 0o170000) == 0o120000:
                    continue
                if any(seg in IGNORED_DIRS for seg in norm.split("/")[:-1]):
                    continue
                dest = extract_root / norm
                dest.parent.mkdir(parents=True, exist_ok=True)
                with zf.open(info, "r") as src, dest.open("wb") as dst:
                    dst.write(src.read())
        work_root = extract_root
    elif not target_path.is_dir():
        raise FileNotFoundError(f"路径不存在或不是目录/ZIP：{target_path}")

    effective_root, unwrapped = unwrap_nested_root(work_root)
    if unwrapped >= 2:
        findings.append(Finding(
            severity="WARN",
            code="DEEP_NESTED_WRAPPER",
            title=f"目录外层套壳过深（{unwrapped} 层）",
            reason="多层外层目录虽可由织页自动剥离，但容易导致手动配置子目录路径时出错。",
            remediation="建议直接从项目根目录打包，使 package.json / requirements.txt / pom.xml / go.mod 位于最外层。",
        ))

    files: Dict[str, Path] = {}
    symlinks_fs: List[str] = []
    oversized_files: List[str] = []
    junk_dirs_fs: set[str] = set()
    total_size = 0

    for cur_dir, dirnames, filenames in os.walk(effective_root, followlinks=False):
        cur_p = Path(cur_dir)
        keep_dirs: List[str] = []
        for d in dirnames:
            dp = cur_p / d
            if dp.is_symlink():
                symlinks_fs.append(str(dp.relative_to(effective_root)))
                continue
            if d in IGNORED_DIRS:
                if d in JUNK_PACKAGE_DIRS and cur_p == effective_root and tmp_dir is None:
                    junk_dirs_fs.add(d)
                continue
            keep_dirs.append(d)
        dirnames[:] = keep_dirs

        for fn in filenames:
            if fn in (".DS_Store", "Thumbs.db"):
                continue
            fp = cur_p / fn
            rel = str(fp.relative_to(effective_root)).replace(os.sep, "/")
            if fp.is_symlink():
                symlinks_fs.append(rel)
                continue
            try:
                sz = fp.stat().st_size
            except OSError:
                continue
            total_size += sz
            if sz > MAX_SINGLE_FILE_BYTES:
                oversized_files.append(f"{rel} ({sz / (1024 * 1024):.1f} MB)")
            files[rel] = fp

    if symlinks_fs:
        findings.append(Finding(
            severity="BLOCK",
            code="SYMLINK_FOUND",
            title="项目包含符号链接（Symlink）",
            reason="织页安全预检禁止导入符号链接。",
            remediation="请移除符号链接或改为普通文件。",
            evidence=symlinks_fs[:5],
        ))
    if len(files) > MAX_FILE_COUNT:
        findings.append(Finding(
            severity="BLOCK",
            code="ARCHIVE_LIMIT_EXCEEDED",
            title=f"源码文件数超限（{len(files)} > {MAX_FILE_COUNT}）",
            reason=f"织页单次导入最多支持 {MAX_FILE_COUNT} 个文件。",
            remediation="请清理构建产物、测试快照、静态图片集或拆离大资产到对象存储。",
        ))
    if total_size > MAX_UNPACKED_BYTES:
        findings.append(Finding(
            severity="BLOCK",
            code="ARCHIVE_LIMIT_EXCEEDED",
            title=f"解压总大小超限（{total_size / (1024 * 1024):.1f} MB > 200 MB）",
            reason="织页单次导入解压总大小上限为 200 MB。",
            remediation="请剔除大体积二进制、数据集或媒体文件（可改为挂载对象存储）。",
        ))
    if oversized_files:
        findings.append(Finding(
            severity="BLOCK",
            code="ARCHIVE_LIMIT_EXCEEDED",
            title="存在超过 20 MB 的单文件",
            reason="织页单文件上限为 20 MB。",
            remediation="请移除超大单文件或改用外部 URL / 对象存储加载。",
            evidence=oversized_files[:5],
        ))
    if junk_dirs_fs:
        findings.append(Finding(
            severity="INFO",
            code="LOCAL_CACHE_PRESENT",
            title="本地目录含依赖/缓存目录（打包时请排除）",
            reason=f"目录中存在 {', '.join(sorted(junk_dirs_fs))}，本次检查已自动忽略；打包 ZIP 上传时务必排除。",
            remediation="使用 zip 排除参数（如 -x 'node_modules/*' '.venv/*' '.git/*'）后再上传。",
        ))

    return effective_root, tmp_dir, files, len(files), total_size


def detect_project(files: Dict[str, Path]) -> Tuple[str, str, str, Dict[str, object]]:
    """返回 (project_kind, runtime, framework, metadata)"""
    names = set(files.keys())
    lower_map = {k.lower(): k for k in names}
    meta: Dict[str, object] = {"pkg": None, "backend_dir": ""}

    if "SKILL.md" in names:
        return "skill", "skill", "Skill", meta

    # 1. 优先识别后端/全栈服务（与织页 ProjectAnalyzer 保持一致）
    has_py = any(f in names for f in ("requirements.txt", "pyproject.toml", "Pipfile", "manage.py", "app.py", "main.py"))
    has_go = "go.mod" in names
    has_java = any(f in names for f in ("pom.xml", "build.gradle", "build.gradle.kts"))
    has_dockerfile = "dockerfile" in lower_map

    if has_java:
        fw = "Java (Spring Boot / Maven)" if "pom.xml" in names else "Java (Gradle)"
        return "service", "java", fw, meta
    if has_go:
        return "service", "go", "Go Web Service", meta
    if has_py:
        fw = "Python Web Service"
        req_text = ""
        for cand in ("requirements.txt", "pyproject.toml"):
            if cand in files:
                req_text += (read_text_safe(files[cand]) or "").lower()
        if "fastapi" in req_text:
            fw = "Python (FastAPI)"
        elif "flask" in req_text:
            fw = "Python (Flask)"
        elif "django" in req_text or "manage.py" in names:
            fw = "Python (Django)"
        return "service", "python", fw, meta

    if "package.json" in files:
        pkg_text = read_text_safe(files["package.json"]) or ""
        try:
            pkg = json.loads(pkg_text)
            meta["pkg"] = pkg
        except json.JSONDecodeError:
            return "invalid", "node", "Node.js (package.json 格式错误)", meta
        deps: Dict[str, str] = {}
        for section in ("dependencies", "devDependencies"):
            if isinstance(pkg.get(section), dict):
                deps.update(pkg[section])
        scripts = pkg.get("scripts") if isinstance(pkg.get("scripts"), dict) else {}
        backend_libs = {"express", "fastify", "koa", "hono", "@nestjs/core", "next", "nuxt", "socket.io", "ws", "better-sqlite3", "pg", "prisma", "@prisma/client", "sequelize", "typeorm", "mongoose"}
        frontend_libs = {"vite", "react", "vue", "svelte", "astro", "@angular/core", "react-scripts"}
        is_backend = bool(set(deps.keys()) & backend_libs) or any(f in names for f in ("server.js", "server.ts", "server/index.js", "server/index.ts"))
        is_frontend = bool(set(deps.keys()) & frontend_libs)
        if is_backend:
            fw = "Node.js Full-Stack" if is_frontend else "Node.js Backend"
            if "next" in deps:
                fw = "Next.js"
            elif "express" in deps:
                fw = "Node.js (Express)" + (" + Frontend" if is_frontend else "")
            return "service", "node", fw, meta
        if is_frontend:
            if "vue" in deps:
                return "vue", "static", "Vue", meta
            return "vite", "static", "Vite / React SPA", meta
        if "start" in scripts or pkg.get("main"):
            return "service", "node", "Node.js Service", meta
        if "build" in scripts:
            return "vite", "static", "Frontend SPA", meta
        # 若根目录 package.json 是 monorepo 编排壳（如 TaskFlow），继续向下扫描子目录

    # 2. 检查常见前后端分离目录（如 backend/ + frontend/、server/ + client/、fastapi-backend/ + react-frontend/）
    top_dirs = sorted({k.split("/")[0] for k in names if "/" in k})
    for td in top_dirs:
        sub_prefix = td + "/"
        sub_names = {k[len(sub_prefix):] for k in names if k.startswith(sub_prefix)}
        if any(m in sub_names for m in ("requirements.txt", "pyproject.toml", "pom.xml", "build.gradle", "go.mod")):
            meta["backend_dir"] = td
            if "pom.xml" in sub_names or "build.gradle" in sub_names:
                return "service", "java", f"Full-Stack Java ({td}/)", meta
            if "go.mod" in sub_names:
                return "service", "go", f"Full-Stack Go ({td}/)", meta
            return "service", "python", f"Full-Stack Python ({td}/)", meta
        if "package.json" in sub_names:
            sub_pkg_text = read_text_safe(files[f"{td}/package.json"]) or ""
            try:
                sub_pkg = json.loads(sub_pkg_text)
                sub_deps = {**(sub_pkg.get("dependencies") or {}), **(sub_pkg.get("devDependencies") or {})}
                if set(sub_deps.keys()) & {"express", "fastify", "koa", "hono", "@nestjs/core", "better-sqlite3", "pg", "prisma"}:
                    meta["backend_dir"] = td
                    return "service", "node", f"Full-Stack Node.js ({td}/)", meta
            except Exception:
                pass

    if "package.json" in files:
        return "unsupported", "node", "未知 Node 项目（缺少 build / start 脚本）", meta

    # 3. 不支持的后端语言检查（无 Dockerfile）
    unsupported_markers = [
        ("Cargo.toml", "Rust (Cargo)"),
        ("composer.json", "PHP (Composer)"),
        ("Gemfile", "Ruby (Bundler)"),
        ("CMakeLists.txt", "C/C++ (CMake)"),
    ]
    for marker, label in unsupported_markers:
        if marker in names or any(k.endswith(".csproj") for k in names):
            if has_dockerfile:
                return "service", "dockerfile", f"Custom Dockerfile ({label})", meta
            if any(k.endswith(".csproj") for k in names):
                label = ".NET (C#)"
            return "unsupported", "unsupported", label, meta

    if has_dockerfile:
        return "service", "dockerfile", "Custom Dockerfile", meta

    if "index.html" in names:
        return "static", "static", "Static HTML", meta

    return "unsupported", "unsupported", "未识别的项目结构", meta


def run_checks(target: Path) -> Report:
    findings: List[Finding] = []
    effective_root, tmp_dir, files, file_count, total_size = collect_project(target, findings)
    try:
        if not files:
            findings.append(Finding(
                severity="BLOCK",
                code="EMPTY_PROJECT",
                title="未找到任何可部署源码文件",
                reason="目录或压缩包为空（过滤缓存后无有效文件）。",
                remediation="请确认传入路径正确且包含项目源码。",
            ))
            return Report(str(target), "BLOCK", "unsupported", "unsupported", "空项目", 0, 0, findings)

        project_kind, runtime, framework, meta = detect_project(files)

        # A. 项目类型 / 能力上限检查
        if project_kind == "invalid":
            findings.append(Finding(
                severity="BLOCK",
                code="INVALID_MANIFEST",
                title="项目配置文件 JSON 格式损坏",
                reason="package.json 无法解析为合法 JSON，平台无法识别依赖与启动命令。",
                remediation="请修复 package.json 语法错误后重新预检。",
                evidence=["package.json"],
            ))
        elif project_kind == "unsupported":
            findings.append(Finding(
                severity="BLOCK",
                code="UNSUPPORTED_RUNTIME",
                title=f"超出织页原生支持范围：{framework}",
                reason="织页目前支持纯静态 HTML、Vite/Vue/React 前端、以及 Python / Node.js / Java / Go / 自定义 Linux Dockerfile 单体全栈应用，不支持该运行时或缺少可识别入口。",
                remediation="若为 Rust/PHP/Ruby/.NET 等后端，请补充可在 Linux 容器中独立构建并监听 $PORT 的 Dockerfile；若为前端请补充 package.json 或 index.html。",
            ))

        # 读取全部文本候选文件（用于安全、端口、localhost、中间件扫描）
        secret_files: List[str] = []
        secret_hits: List[str] = []
        microservice_hits: Dict[str, List[str]] = {}
        gpu_hits: Dict[str, List[str]] = {}
        windows_hits: List[str] = []
        middleware_hits: Dict[str, List[str]] = {}
        localhost_frontend_hits: List[str] = []
        loopback_bind_hits: List[str] = []
        sqlite_mentions: List[str] = []
        uses_data_dir = False
        uses_port_env = False
        has_health_route = False
        has_compose_multi_service = False
        compose_services: List[str] = []

        manifest_names = {
            "requirements.txt", "pyproject.toml", "pipfile", "package.json",
            "pom.xml", "build.gradle", "build.gradle.kts", "go.mod",
            "docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml",
            "application.yml", "application.yaml", "application.properties",
        }

        for rel, fp in files.items():
            base = os.path.basename(rel).lower()

            # 1. 敏感文件检测
            if SECRET_FILE_RE.search(rel):
                secret_files.append(rel)

            if not is_text_candidate(rel):
                continue
            text = read_text_safe(fp)
            if not text:
                continue

            # 2. 源码明文密钥检测（跳过测试/示例文件降低误报）
            is_test_or_doc = any(seg in rel.lower().split("/") for seg in ("test", "tests", "__tests__", "docs", "examples")) or base.endswith((".md", ".example", ".sample"))
            if not is_test_or_doc:
                for label, pat in SECRET_CONTENT_PATTERNS:
                    hits = find_line_hits(text, pat, rel, max_hits=2)
                    for h in hits:
                        secret_hits.append(f"[{label}] {h}")

            # 3. 微服务 / GPU / Windows 专用依赖（检查清单与配置）
            is_manifest = base in manifest_names or base.startswith("application") or base == "dockerfile"
            if is_manifest:
                for label, pat in MICROSERVICE_DEPS:
                    hits = find_line_hits(text, pat, rel, max_hits=2)
                    if hits:
                        microservice_hits.setdefault(label, []).extend(hits)
                for label, pat in GPU_DEPS:
                    hits = find_line_hits(text, pat, rel, max_hits=2)
                    if hits:
                        gpu_hits.setdefault(label, []).extend(hits)
                for label, pat in MIDDLEWARE_RULES:
                    hits = find_line_hits(text, pat, rel, max_hits=2)
                    if hits:
                        middleware_hits.setdefault(label, []).extend(hits)

                # docker-compose 多服务识别
                if base in ("docker-compose.yml", "docker-compose.yaml", "compose.yml", "compose.yaml"):
                    in_services = False
                    for line in text.splitlines():
                        if re.match(r"^services\s*:\s*$", line):
                            in_services = True
                            continue
                        if in_services:
                            if line and not line.startswith((" ", "\t", "#")):
                                in_services = False
                                continue
                            m = re.match(r"^  ([a-zA-Z0-9_-]+)\s*:\s*$", line)
                            if m:
                                compose_services.append(m.group(1))
                    if len(compose_services) >= 2:
                        has_compose_multi_service = True

            if WINDOWS_ONLY_RE.search(text) and not is_test_or_doc:
                windows_hits.extend(find_line_hits(text, WINDOWS_ONLY_RE, rel, max_hits=2))

            # 4. 前端硬编码 localhost 扫描（排除纯后端目录与 server 入口，跳过多行注释）
            rel_lower = rel.lower()
            parts_lower = rel_lower.split("/")
            in_backend_dir = bool(set(parts_lower[:-1]) & {"server", "backend", "api", "fastapi-backend", "migrations", "prisma"}) or base in ("server.js", "server.ts", "server.mjs", "server.cjs")
            is_frontend_file = rel_lower.endswith((".ts", ".tsx", ".js", ".jsx", ".vue", ".svelte", ".html")) and not is_test_or_doc and not in_backend_dir
            is_config_file = base.startswith(("vite.config", "webpack.config", "next.config", "nuxt.config", "tailwind.config", "eslint", "jest.config", "vitest.config"))
            if is_frontend_file and not is_config_file:
                in_block_comment = False
                for idx, line in enumerate(text.splitlines(), start=1):
                    st = line.strip()
                    if in_block_comment:
                        if "*/" in st:
                            in_block_comment = False
                        continue
                    if st.startswith("/*"):
                        if "*/" not in st:
                            in_block_comment = True
                        continue
                    if not st or st.startswith(("//", "*", "<!--")):
                        continue
                    if LOCALHOST_URL_RE.search(line):
                        if re.search(r"console\.(log|info|warn)|server\s+running|listening\s+on", line, re.IGNORECASE):
                            continue
                        localhost_frontend_hits.append(f"{rel}:{idx} -> {st[:100]}")

            # 5. 后端代码端口 / 回环地址 / 健康检查 / 持久化扫描
            is_backend_code = rel.lower().endswith((".py", ".js", ".mjs", ".cjs", ".ts", ".java", ".kt", ".go")) or base in ("dockerfile", "application.yml", "application.yaml", "application.properties", "package.json")
            if project_kind == "service" and is_backend_code and not is_test_or_doc:
                if re.search(r"\b(?:PORT|SERVER_PORT|server\.port)\b", text):
                    uses_port_env = True
                if re.search(r"""['"](/health|/api/health|/actuator/health|/healthz|/ready)['"]""", text) or "spring-boot-starter-actuator" in text:
                    has_health_route = True
                if re.search(r"\b(?:DATA_DIR|DATABASE_PATH|DATABASE_URL|JDBC_DATABASE_URL)\b|/data\b", text):
                    uses_data_dir = True
                if re.search(r"\b(?:sqlite3|better-sqlite3|aiosqlite|go-sqlite3|mattn/go-sqlite3|modernc\.org/sqlite|sqlite://)\b", text, re.IGNORECASE):
                    if len(sqlite_mentions) < 3:
                        sqlite_mentions.append(rel)
                for pat in BIND_LOOPBACK_PATTERNS:
                    loopback_bind_hits.extend(find_line_hits(text, pat, rel, max_hits=2))

        # --- 生成结构化 Findings ---

        if secret_files:
            findings.append(Finding(
                severity="BLOCK",
                code="SECRET_FILE_COMMITTED",
                title="包含敏感凭证文件（.env / 私钥）",
                reason="代码包内包含 .env 或私钥文件，存在凭证泄露风险，违反织页安全规范。",
                remediation="请删除这些文件（仅保留 .env.example），将真实密钥配置在织页「服务托管 → 环境变量」中加密注入。",
                evidence=secret_files[:5],
            ))

        if secret_hits:
            findings.append(Finding(
                severity="BLOCK",
                code="PLAINTEXT_SECRET",
                title="源码中检测到疑似明文密钥或敏感 ID",
                reason="直接硬编码 API Key / Token / 私钥 / 飞书 ID 会导致凭据泄露，无法通过平台安全审查。",
                remediation="请改为从环境变量读取（如 os.environ['API_KEY'] / process.env.API_KEY），并在「环境变量」面板录入。",
                evidence=secret_hits[:5],
            ))

        if microservice_hits:
            ev = [f"{k}: {v[0]}" for k, v in microservice_hits.items()]
            findings.append(Finding(
                severity="BLOCK",
                code="MICROSERVICE_STACK",
                title=f"超出能力上限：检测到微服务/注册中心或特权依赖（{', '.join(microservice_hits.keys())}）",
                reason="织页仅提供单实例单体应用托管，不提供 Nacos/Eureka/Dubbo 服务治理集群或宿主机 Docker 特权环境。",
                remediation="若业务可简化，请拆除微服务注册中心依赖改为单体应用；若必须多服务协作，请改用独立云主机或标准 Kubernetes 集群部署。",
                evidence=ev[:5],
            ))

        if gpu_hits:
            ev = [f"{k}: {v[0]}" for k, v in gpu_hits.items()]
            findings.append(Finding(
                severity="BLOCK",
                code="GPU_WORKLOAD",
                title=f"超出能力上限：检测到重型深度学习/GPU 依赖（{', '.join(gpu_hits.keys())}）",
                reason="织页应用运行容器为纯 CPU 环境（单应用硬上限 1C 1G：1000 mCPU / 1024 MB），无 GPU 卡，无法运行本地大模型或 CUDA 推理。",
                remediation="请改为通过 HTTP API 调用外部模型服务（API Key 放入环境变量），移除本地 torch/vllm/cuda 依赖。",
                evidence=ev[:5],
            ))

        if windows_hits:
            findings.append(Finding(
                severity="BLOCK",
                code="WINDOWS_ONLY_DEPENDENCY",
                title="超出能力上限：依赖 Windows 专用组件",
                reason="织页生产运行时为 Linux 容器环境，无法运行 pywin32 / win32com 等 Windows 专用 API。",
                remediation="请替换为跨平台纯 Python/Node 库（如 openpyxl、python-docx、playwright 等）。",
                evidence=windows_hits[:5],
            ))

        if project_kind == "service" and loopback_bind_hits:
            findings.append(Finding(
                severity="BLOCK",
                code="BIND_LOOPBACK",
                title="后端服务硬编码绑定 127.0.0.1 / localhost",
                reason="在容器化部署环境中，监听 127.0.0.1 会导致平台反向代理与健康检查探针无法连接容器端口，应用启动必然超时失败。",
                remediation="请将监听地址改为 0.0.0.0（例如 uvicorn --host 0.0.0.0 或 app.listen(PORT, '0.0.0.0')）。",
                evidence=loopback_bind_hits[:5],
            ))

        if localhost_frontend_hits:
            findings.append(Finding(
                severity="BLOCK",
                code="HARDCODED_LOCALHOST_API",
                title="前端代码硬编码了 localhost / 127.0.0.1 接口地址",
                reason="织页为应用分配独立子域名且前后端同源访问；前端写死 http://localhost:xxxx 会导致所有线上用户的浏览器请求失败或被跨域拦截。",
                remediation="请改为相对路径（如 fetch('/api/...')），或通过构建变量默认空串（const API_BASE = import.meta.env.VITE_API_BASE || ''）。",
                evidence=localhost_frontend_hits[:6],
            ))

        if has_compose_multi_service:
            findings.append(Finding(
                severity="WARN",
                code="MULTI_SERVICE_COMPOSE",
                title=f"检测到多容器编排配置（docker-compose 含 {len(compose_services)} 个服务：{', '.join(compose_services[:5])}）",
                reason="织页不支持直接编排 docker-compose 多容器栈，只会启动单个单体应用容器。",
                remediation="请确认主应用可脱离 compose 独立启动（内置 SQLite 或接入托管 PostgreSQL `DATABASE_URL`）；若强依赖其他伴生服务，需先在外部独立部署并填入环境变量。",
                evidence=[f"services: {', '.join(compose_services)}"],
            ))

        if middleware_hits:
            names_str = "、".join(middleware_hits.keys())
            ev = [f"{k}: {v[0]}" for k, v in middleware_hits.items()]
            findings.append(Finding(
                severity="WARN",
                code="EXTERNAL_MIDDLEWARE",
                title=f"检测到外部中间件依赖：{names_str}",
                reason="织页平台内置托管 PostgreSQL（独立 Schema）与 /data 持久化卷，但不内置 Redis/MySQL/MongoDB/Kafka/RabbitMQ/ES 等中间件。",
                remediation="若为可选组件，请确保未配置时能降级启动（如切换 SQLite/内存缓存）；若强依赖外部中间件，请准备集群网络可达的外部实例并通过「环境变量」注入连接串。",
                evidence=ev[:5],
            ))

        if project_kind == "service" and runtime in ("python", "node", "go", "java") and not uses_port_env:
            findings.append(Finding(
                severity="WARN",
                code="PORT_NOT_CONFIGURABLE",
                title="未检测到读取 PORT / SERVER_PORT 环境变量",
                reason="织页启动单体服务时会自动注入 PORT（及 SERVER_PORT）环境变量；如果代码写死端口且与面板「监听端口」不一致，探活将会失败。",
                remediation="建议在入口代码读取 PORT 环境变量（如 int(os.getenv('PORT', '8000')) 或 process.env.PORT || 3000），或在导入后于「服务托管」面板手工对齐监听端口。",
            ))

        if project_kind == "service" and sqlite_mentions and not uses_data_dir:
            findings.append(Finding(
                severity="WARN",
                code="EPHEMERAL_SQLITE_PATH",
                title="使用了 SQLite 但未引用 DATA_DIR / /data 持久化目录",
                reason="织页服务默认开启闲置自动休眠（Scale-to-Zero）；容器重建后，未挂载到 /data 持久化卷的本地 .db 文件会被清空。",
                remediation="请将 SQLite 文件路径改为读取 os.getenv('DATA_DIR', '/data') 或 DATABASE_PATH，并在「服务托管」面板开启「挂载 /data 持久化卷」。",
                evidence=sqlite_mentions,
            ))

        if project_kind == "service" and not has_health_route:
            findings.append(Finding(
                severity="INFO",
                code="NO_EXPLICIT_HEALTH_ROUTE",
                title="未显式发现 /health 或 /api/health 路由",
                reason="平台默认探测根路径 / 或 /health；如果首页较重或需要鉴权返回 401/500，可能导致健康检查误判。",
                remediation="推荐提供一个免鉴权、快速返回 200 OK 的 GET /health 接口。",
            ))

        if project_kind == "service" and runtime == "python":
            if "requirements.txt" not in files and "pyproject.toml" not in files and not meta.get("backend_dir"):
                findings.append(Finding(
                    severity="WARN",
                    code="MISSING_PYTHON_DEPS_FILE",
                    title="缺少 requirements.txt 或 pyproject.toml",
                    reason="平台无法自动安装第三方 Python 包，若项目引用了非标准库（如 fastapi/flask/requests）启动将报 ModuleNotFoundError。",
                    remediation="请在项目根目录生成 requirements.txt（pip freeze > requirements.txt 或列出核心依赖）。",
                ))

        # 汇总最终结论
        if any(f.severity == "BLOCK" for f in findings):
            verdict = "BLOCK"
        elif any(f.severity == "WARN" for f in findings):
            verdict = "WARN"
        else:
            verdict = "PASS"

        return Report(
            target=str(target),
            verdict=verdict,
            project_kind=project_kind,
            runtime=runtime,
            framework=framework,
            file_count=file_count,
            total_size_bytes=total_size,
            findings=findings,
        )
    finally:
        if tmp_dir is not None:
            tmp_dir.cleanup()


def print_human_report(report: Report) -> None:
    badge = {"PASS": "[PASS 通过]", "WARN": "[WARN 有风险提醒]", "BLOCK": "[BLOCK 阻断，不可直接导入]"}[report.verdict]
    print("=" * 72)
    print(f"织页平台（Zhiye）部署前预检报告  {badge}")
    print("=" * 72)
    print(f"目标路径  : {report.target}")
    print(f"识别形态  : {report.framework}  (kind={report.project_kind}, runtime={report.runtime})")
    print(f"源码规模  : {report.file_count} 个文件 / {report.total_size_bytes / 1024:.1f} KB")
    print("-" * 72)

    if not report.findings:
        print("未发现任何阻断项或风险项，可直接打包导入织页平台。")
        return

    order = {"BLOCK": 0, "WARN": 1, "INFO": 2}
    for idx, f in enumerate(sorted(report.findings, key=lambda x: order.get(x.severity, 9)), start=1):
        print(f"{idx}. [{f.severity}] ({f.code}) {f.title}")
        print(f"   原因：{f.reason}")
        print(f"   整改：{f.remediation}")
        if f.evidence:
            print("   证据：")
            for e in f.evidence:
                print(f"     - {e}")
    print("-" * 72)
    if report.verdict == "BLOCK":
        print("结论：存在 BLOCK 阻断项。请先完成上述整改或评估是否超出织页单体托管能力边界后再决定导入。")
    elif report.verdict == "WARN":
        print("结论：可导入，但请确认上述 WARN 项并在织页「服务托管」面板配齐环境变量/存储开关。")
    else:
        print("结论：预检通过，符合织页平台规范。")


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description="检查项目目录或 ZIP 包是否符合织页平台导入与托管能力边界")
    parser.add_argument("target", help="本地项目目录路径或 .zip 压缩包路径")
    parser.add_argument("--json", action="store_true", dest="as_json", help="以 JSON 格式输出结果")
    parser.add_argument("--strict", action="store_true", help="将 WARN 也视为非零退出码 (1)")
    args = parser.parse_args(argv)

    target = Path(args.target).expanduser().resolve()
    if not target.exists():
        print(f"错误：路径不存在：{target}", file=sys.stderr)
        return 1

    report = run_checks(target)
    if args.as_json:
        print(json.dumps(asdict(report), ensure_ascii=False, indent=2))
    else:
        print_human_report(report)

    if report.verdict == "BLOCK":
        return 2
    if report.verdict == "WARN" and args.strict:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
