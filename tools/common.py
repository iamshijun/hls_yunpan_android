#!/usr/bin/env python3
"""tools 的共享支撑层：退出/环境变量、HTTP + 重试、CSV 与原子落盘。

这里**不放业务**：网盘在 `netdisk.py`，影片详情服务在 `movie_api.py`。
放在这里的唯一标准是「两个以上脚本要一模一样的行为」，而不是「看起来通用」。

约定：
  - `die()` 的退出码由调用方给（默认 2）。各脚本的退出码是它对外接口的一部分，
    要改也只能在脚本里改，不能在这里统一。
  - `with_retry()` 返回 `(status, body, error)` 三元组：HTTP 4xx/5xx 与连接失败都
    不抛异常，交给调用方分类（这是既有四个脚本共同的报错措辞基础，别改签名）。
  - CSV 一律 UTF-8 BOM + CRLF（Excel 双击不乱码）。
  - `write_report()` 是报告落盘的统一口径（内容相同不写 / 有变化先归档旧文件 / partial
    不碰原路径）；`calc_media_size.py` 与 `find_missing_segments.py` 共用这一份。
"""
from __future__ import annotations

import csv
import fnmatch
import io
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime
from typing import Callable, Iterable, Sequence

# --- 常量 --------------------------------------------------------------------

#: 列表/元数据请求用浏览器 UA（下载直链另有要求，见 netdisk.DOWNLOAD_UA）
WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

DEFAULT_TIMEOUT = 15.0
DEFAULT_MAX_ATTEMPTS = 5
DEFAULT_MAX_WAIT = 7.0

BACKOFF_BASE = 0.5
BACKOFF_FACTOR = 2.0
#: 可重试的 HTTP 状态码：超时/限流/网关与 5xx；其余 4xx 是确定结论，不重试
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

#: 出现这些字符的 --only / --exclude 取值按通配符（glob）处理，否则按子串
WILDCARD_CHARS = frozenset("*?[")


# --- 退出与配置 --------------------------------------------------------------

def die(msg: str, code: int = 2):
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(code)


def read_env_file(path: str, code: int = 2) -> dict[str, str]:
    """极简 .env 解析：只认 KEY=VALUE，忽略注释与空行。读不了就按调用方的退出码退出。"""
    env: dict[str, str] = {}
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                env[key.strip()] = val.strip().strip("'\"")
    except OSError as e:
        die(f"读取 env 文件失败 {path}: {e}", code)
    return env


def resolve_token(args, code: int = 2) -> str:
    """按 --token > --env-file 的 ACCESS_TOKEN > $BAIDU_ACCESS_TOKEN 取 token。

    [args] 只要有 `.token` / `.env_file` 两个属性即可（argparse Namespace 或测试替身）。
    """
    token = (args.token or "").strip()
    if not token and args.env_file:
        token = read_env_file(args.env_file, code).get("ACCESS_TOKEN", "").strip()
    if not token:
        token = (os.environ.get("BAIDU_ACCESS_TOKEN") or "").strip()
    if not token:
        die("缺少 access_token：用 --token / --env-file / $BAIDU_ACCESS_TOKEN 提供", code)
    return token


def split_terms(values: list[str]) -> list[str]:
    """摊平 --only / --exclude 的取值：逗号（半角或全角）分隔，也可重复给参。

    `--only "abc-123, cdf-345"` 与 `--only abc-123 --only cdf-345` 等价；
    首尾空白丢掉，空条目（如尾随逗号）丢弃，重复项只留第一个。
    """
    terms: list[str] = []
    for value in values:
        for term in value.replace("，", ",").split(","):
            term = term.strip()
            if term and term not in terms:
                terms.append(term)
    return terms


def match_terms(name: str, terms: Sequence[str]) -> bool:
    """--only / --exclude 的命中判定：任意一个 term 命中就算命中。

    两种写法，按 term 里**有没有通配符**区分（`*` / `?` / `[`）：
      - 没有通配符 -> 子串包含（`abc-123` 命中 `abc-123`、`abc-123-4k`）；
      - 有通配符   -> glob **整名**匹配（`mgmj-*` 命中 `mgmj-410`，`*-hd` 命中 `abc-123-hd`）。
    两种都**忽略大小写**：番号在命令行和目录里常差大小写，要求用户背目录的大小写没有意义。

    glob 是整名匹配而不是「在名字里找一段」，否则 `mgmj-*` 这种前缀写法会退化成
    「只要出现 mgmj- 就算」，「摘掉一部分」的意图就没法表达。要「含有」就写 `*abc*`。
    """
    folded = name.casefold()
    for term in terms:
        pattern = term.casefold()
        if WILDCARD_CHARS.isdisjoint(pattern):
            if pattern in folded:
                return True
        elif fnmatch.fnmatchcase(folded, pattern):
            return True
    return False


# --- HTTP + 重试 -------------------------------------------------------------

def shorten(url: str, limit: int = 70) -> str:
    """URL 压成一行日志：去掉 scheme/host 后只留路径，太长就截断。"""
    parts = urllib.parse.urlsplit(url)
    text = f"{parts.path}?{parts.query}" if parts.query else parts.path
    return text if len(text) <= limit else text[:limit - 1] + "…"


def http_request(method: str, url: str, timeout: float, ua: str = WEB_UA,
                 body: bytes | None = None, content_type: str | None = None,
                 ) -> tuple[int, str]:
    """发一个请求，返回 (status_code, body_text)。HTTP 4xx/5xx 不抛异常，交给调用方分类。"""
    headers = {"User-Agent": ua}
    if content_type:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def with_retry(call: Callable[[], tuple[int, str]], attempts: int, max_wait: float, label: str,
               abort=None) -> tuple[int, str, str]:
    """带指数退避的重试，返回 (status, body, error)。

    可重试：连接失败/超时（call 抛异常）、以及 408/429/5xx。
    其余 4xx 是确定结论（403 就是 403），不重试，直接返回给调用方分类。

    最多 [attempts] 次请求；第 n 次失败后等 `BASE * FACTOR^(n-1)` 秒，
    单次与累计等待都不超过 [max_wait] 秒；退避预算用尽后不再等待，剩下的尝试立即重试。
    重试耗尽时返回 status=0 + 最后一次错误描述。

    [abort] 是可选的 `threading.Event`：置位后本轮不再重试，立即返回「已中止」。
    """
    attempts = max(1, attempts)
    waited = 0.0
    status, body, last_err = 0, "", ""
    for attempt in range(1, attempts + 1):
        if abort is not None and abort.is_set():
            return 0, "", "已中止"
        try:
            status, body = call()
        except Exception as e:  # 连接失败 / 超时 / TLS 等，统一文本化
            status, body, last_err = 0, "", f"{type(e).__name__}: {e}"
        else:
            if not status or status not in RETRYABLE_STATUS:
                return status, body, ""  # 成功，或是不可重试的 4xx
            last_err = f"HTTP {status}: {body[:120]}"

        if attempt == attempts:
            break
        delay = min(BACKOFF_BASE * (BACKOFF_FACTOR ** (attempt - 1)), max_wait, max_wait - waited)
        if delay > 0:
            waited += delay
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → {delay:.1f}s 后重试 ({label})",
                  file=sys.stderr, flush=True)
            time.sleep(delay)
        else:
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → 退避预算已用尽，立即重试 ({label})",
                  file=sys.stderr, flush=True)

    return 0, "", f"重试 {attempts} 次仍失败: {last_err}"


# --- CSV 与文件落盘 ----------------------------------------------------------

def render_csv(header: Sequence[str], rows: Iterable[Sequence]) -> bytes:
    """渲染成最终字节：UTF-8 BOM（Excel 双击不乱码）+ CSV 默认 CRLF 行尾。"""
    buf = io.StringIO(newline="")
    writer = csv.writer(buf)
    writer.writerow(header)
    for row in rows:
        writer.writerow(row)
    return b"\xef\xbb\xbf" + buf.getvalue().encode("utf-8")


def with_stamp(path: str, stamp: str) -> str:
    """report.csv + 20251026-212637 -> report.20251026-212637.csv（扩展名留在最后）。"""
    root, ext = os.path.splitext(path)
    return f"{root}.{stamp}{ext}"


def unique_path(path: str, limit: int = 10) -> str:
    """名字被占用时依次试 -2、-3……；全都占着就抛 OSError（宁可报错也不覆盖）。"""
    if not os.path.exists(path):
        return path
    root, ext = os.path.splitext(path)
    for n in range(2, limit + 1):
        candidate = f"{root}-{n}{ext}"
        if not os.path.exists(candidate):
            return candidate
    raise OSError(f"找不到可用的归档名：{path} 以及 -2…-{limit} 都被占用")


def atomic_write(path: str, data: bytes) -> None:
    """先写同目录临时文件再 os.replace：不会留下半截文件，失败也不动原文件。"""
    tmp = f"{path}.{os.getpid()}.tmp"
    try:
        with open(tmp, "wb") as fh:
            fh.write(data)
        os.replace(tmp, path)
    except OSError:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise


def archive_existing(path: str) -> str:
    """把已有报告移动到带时间戳的归档名，返回归档路径。

    移动（os.replace）而不是复制：原子、零拷贝，不会出现「两份相同内容」或
    「归档写了一半」的中间态。时间戳取旧文件的 mtime —— 归档名应该回答
    「这份报告是哪一轮的」，而不是「我哪一秒覆盖了它」。
    """
    try:
        stamp = datetime.fromtimestamp(os.path.getmtime(path)).strftime("%Y%m%d-%H%M%S")
    except OSError:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    target = unique_path(with_stamp(path, stamp))
    os.replace(path, target)
    return target


def write_report(path: str, data: bytes, partial: bool = False) -> tuple[str, str]:
    """写 CSV 报告（BOM+CRLF 的 [data] 已由调用方渲染好），返回 (实际路径, 给人看的一句话)。

    不覆盖已有报告：
      - partial=False：目标已存在且内容逐字节相同 -> 不写、不轮转；真有变化才把旧的
        移到 <stem>.<旧文件mtime>.csv 留档，再写新的；
      - partial=True（中断/中止时的半轮结果）：写到 <stem>.partial-<ts>.csv，
        永远不碰原路径 —— 半张表不能冒充跑完的那一轮。
    OSError 交给调用方处理（各脚本对「归档失败」的退出口径不完全一样）。
    """
    if partial:
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        path = unique_path(with_stamp(path, f"partial-{stamp}"))
        note = ""
    elif os.path.exists(path):
        try:
            with open(path, "rb") as fh:
                same = fh.read() == data
        except OSError:
            same = False  # 读不出来就当成变了：交给归档那步去报错，绝不静默覆盖
        if same:
            return path, f"报告内容与已有文件一致，未重写: {path}"
        note = f"（旧报告已归档为 {archive_existing(path)}）"
    else:
        note = ""

    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    atomic_write(path, data)
    return path, f"已写出 CSV: {path}{note}"


def write_csv(path: str, header: Sequence[str], rows: Iterable[Sequence], *,
              exclusive: bool = False, code: int = 2) -> None:
    """写 CSV（BOM + CRLF）。exclusive=True 时目标已存在就按 [code] 退出、绝不覆盖。"""
    if exclusive and os.path.exists(path):
        die(f"{path} 已存在，拒绝覆盖（换个路径或先把它挪走）", code)
    try:
        atomic_write(path, render_csv(header, rows))
    except OSError as e:
        die(f"写 CSV 失败 {path}: {e}", code)
