#!/usr/bin/env python3
"""遍历网盘影片目录，算出每部影片的体积，并把 meta.json 写回影片目录。

背景：百度网盘的 list 接口不返回「目录大小」，只返回单个文件的 size，
所以目录体积必须自己累加。

流程：
  1. 列举 <media-root> 下的一级目录，目录名即番号 (fan_code)；
  2. 逐个目录 list 出第一层内容：
       - 没有 playlist.m3u8 -> 不算影片目录，直接跳过；
       - 有子目录           -> 只告警（体积只统计第一层，见下），不递归；
  3. 目录里若已有 meta.json（且未 --force）：
       - 合法 JSON 且 size_bytes 为正整数 -> 「已有体积信息」，跳过
       - 合法 JSON 但没有正整数的 size_bytes（0 / 缺字段）-> 重新计算
       - 下载失败 / 不是 JSON -> 记 error，**不重算、不覆盖**
         （网络抖一下就把好数据覆盖掉是不可接受的）
  4. 需要重算时，上传 meta.json 覆盖原文件
     （PCS 单请求上传：ondup=overwrite，天然覆盖；meta.json 只有几 KB，不必用
      precreate/superfile2/create 三步法）。

体积的口径：
  - 只统计目录**第一层**的文件（本项目影片目录约定是平铺的），
    排除 meta.json 自身，以及 @eaDir / #recycle / Thumbs.db / .DS_Store 这类临时文件；
  - 一旦发现子目录，只告警不递归 —— 宁可让数字被质疑，也不要静默算错；
  - 排除 meta.json 自身是为了自洽：它的大小会因为自身内容而变，自指会让数字抖动。

写入的 meta.json（schema_version = 1）：
  {
    "schema_version": 1,
    "fan_code": "ABC-123",              # 网盘目录名原文（不做 NFKC 归一化）
    "path": "/apps/asitanokibou/movies/ABC-123",
    "size_bytes": 1234567890,           # 权威字段，识别「已有体积信息」只看它
    "size_human": "1.15 GiB",           # 1024 进制，只为人眼看
    "file_count": 42,
    "dir_count": 2,                     # 子目录数（未计入体积），非 0 时会告警
    "computed_at": "2025-10-06T20:30:00+08:00",
    "computed_by": "tools/calc_media_size.py"
  }

断点续跑：不需要状态文件。默认行为（已有有效 meta.json 就跳过）本身就是断点续跑，
直接原样重跑一遍即可，成功的跳过、失败的自动重试。

退出码：0 = 全部成功（含跳过）；1 = 跑完了但有失败目录/告警，或报告里存在非 ok 的行
        （--dry-run 时不因报告状态升码：那一轮本来就没打算写任何东西）；
        2 = 参数错误 / token 缺失失效 / 媒体根目录取不到（整轮没意义）。

用法：
  python3 tools/calc_media_size.py --env-file ~/py_workspace/movies_service/hls_yunpan/.env

  # 常用变体
  python3 tools/calc_media_size.py --token <access_token>
  python3 tools/calc_media_size.py --only ABC-123 --dry-run   # 先拿一部试水，不上传
  python3 tools/calc_media_size.py --force                    # 全部重算并覆盖上传
  python3 tools/calc_media_size.py --exclude trash --workers 4
  python3 tools/calc_media_size.py --verify --only ABC-123    # 上传后回读比对

汇总报告（--report / --report-only）：
  --report out.csv        正常跑完后，把每部影片的 meta.json 汇总成一张 CSV
  --report-only out.csv   只汇总出 CSV：不计算体积、不上传（仍要 list + 下载 meta.json），
                          跑完直接退出

  CSV 的 10 列：fan_code, status, size_bytes, size_human, file_count, dir_count,
  stale, computed_at, path, detail
    - 除 status/stale/detail 外的值都抽自磁盘上的 meta.json 本身；meta.json 缺失
      或不可用时那几列留空（未知和「就是 0」不是一回事）；
    - status: ok / missing(没有 meta.json) / invalid(读不出来或不是 JSON) /
              no_size(是 JSON 但没有正整数 size_bytes) / error(处理失败，没拿到结论)；
    - stale: meta.json 是否比它旁边被统计的文件还旧（分片补齐后忘了更新 meta 的信号），
             比的是 mtime 而不是 computed_at（避免跨时钟比较把本机时间偏差放大成整表 stale），
             任何一侧 mtime 缺失就留空，不瞎判；
    - 行序按 fan_code 升序；编码 UTF-8 BOM（Excel 双击不乱码）。
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime

# --- 与 Android 工程对齐的常量 -----------------------------------------------

FILE_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
MEDIA_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
#: PCS 老接口：单请求上传，带上 ondup=overwrite 就是覆盖
PCS_UPLOAD_URL = "https://d.pcs.baidu.com/rest/2.0/pcs/file"

#: 列表/元数据用浏览器 UA
WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
#: 下载直链必须用这个 UA（与 BaiduYunClient.DOWNLOAD_UA 一致）
DOWNLOAD_UA = "pan.baidu.com"

#: 与 app/build.gradle.kts 的 BAIDU_APP_NAME 对齐
DEFAULT_MEDIA_ROOT = "/apps/asitanokibou/movies"
DEFAULT_META_NAME = "meta.json"
#: 与 HlsPaths.PLAYLIST_NAME 对齐：不含它的目录不算影片目录
PLAYLIST_NAME = "playlist.m3u8"

META_SCHEMA_VERSION = 1
COMPUTED_BY = "tools/calc_media_size.py"

#: 百度 list 接口单页上限
LIST_PAGE = 1000

#: 临时/系统垃圾文件（小写比对），不计入体积
JUNK_NAMES = frozenset({"@eadir", "#recycle", "thumbs.db", ".ds_store", "._.ds_store"})

DEFAULT_WORKERS = 2
DEFAULT_TIMEOUT = 15.0
DEFAULT_UPLOAD_TIMEOUT = 60.0

# --- 请求重试 -----------------------------------------------------------------

DEFAULT_MAX_ATTEMPTS = 5
DEFAULT_MAX_WAIT = 7.0
BACKOFF_BASE = 0.5
BACKOFF_FACTOR = 2.0
#: 可重试的 HTTP 状态码：超时/限流/网关与 5xx；其余 4xx 是确定结论，不重试
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

#: access_token 失效/过期的 errno —— 再跑下去每个请求都会失败，立即终止整轮
TOKEN_ERRNOS = frozenset({-6, 111})

#: 全局中止开关：token 失效时让还在排队的任务直接放弃，而不是继续刷错误
ABORT = threading.Event()

#: 目录的结局分类（report 汇总按它计数）
OUTCOMES = ("skipped", "uploaded", "dry_run", "not_movie", "reported", "error")

#: 报告的 CSV 列，以及 status 的取值
REPORT_HEADER = ("fan_code", "status", "size_bytes", "size_human", "file_count",
                 "dir_count", "stale", "computed_at", "path", "detail")
REPORT_STATUSES = ("ok", "missing", "invalid", "no_size", "error")


# --- 异常与基础工具 -----------------------------------------------------------

class BaiduApiError(Exception):
    """百度接口返回 errno != 0。"""

    def __init__(self, errno: int, errmsg: str = ""):
        super().__init__(f"errno={errno} {errmsg}".strip())
        self.errno = errno
        self.errmsg = errmsg

    @property
    def is_token_invalid(self) -> bool:
        return self.errno in TOKEN_ERRNOS


class DirError(Exception):
    """单个目录处理失败：记 error 后继续处理其它目录。"""


def die(msg: str, code: int = 2):
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(code)


def shorten(url: str, limit: int = 70) -> str:
    """URL 压成一行日志：去掉 scheme/host 后只留路径，太长就截断。"""
    parts = urllib.parse.urlsplit(url)
    text = f"{parts.path}?{parts.query}" if parts.query else parts.path
    return text if len(text) <= limit else text[:limit - 1] + "…"


def http_get(url: str, timeout: float, ua: str = WEB_UA) -> tuple[int, str]:
    """GET 并返回 (status_code, body_text)。HTTP 4xx/5xx 不抛异常，交给调用方分类。"""
    req = urllib.request.Request(url, headers={"User-Agent": ua})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def http_post(url: str, body: bytes, content_type: str, timeout: float,
              ua: str = WEB_UA) -> tuple[int, str]:
    """POST 并返回 (status_code, body_text)。HTTP 4xx/5xx 不抛异常。"""
    req = urllib.request.Request(
        url, data=body, method="POST",
        headers={"User-Agent": ua, "Content-Type": content_type},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def with_retry(call, attempts: int, max_wait: float, label: str) -> tuple[int, str, str]:
    """带指数退避的重试，返回 (status, body, error)。

    可重试：连接失败/超时（call 抛异常）、以及 408/429/5xx。
    其余 4xx 是确定结论（403 就是 403），不重试，直接返回给调用方分类。

    最多 [attempts] 次请求；第 n 次失败后等 `BASE * FACTOR^(n-1)` 秒，
    单次与累计等待都不超过 [max_wait] 秒；退避预算用尽后不再等待，剩下的尝试立即重试。
    重试耗尽时返回 status=0 + 最后一次错误描述。
    """
    attempts = max(1, attempts)
    waited = 0.0
    status, body, last_err = 0, "", ""
    for attempt in range(1, attempts + 1):
        if ABORT.is_set():
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


def read_env_file(path: str) -> dict[str, str]:
    """极简 .env 解析：只认 KEY=VALUE，忽略注释与空行。"""
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
        die(f"读取 env 文件失败 {path}: {e}")
    return env


def is_junk(name: str) -> bool:
    return name.lower() in JUNK_NAMES


def is_positive_int(value) -> bool:
    """size_bytes 的唯一有效判定：正整数（bool 不算，0 不算）。"""
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def non_negative_int(value) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) and value >= 0 else None


def human_size(size: int) -> str:
    """1024 进制，只为人类可读；权威值永远是 size_bytes。"""
    if size < 1024:
        return f"{size} B"
    value = float(size)
    unit = "B"
    for unit in ("KiB", "MiB", "GiB", "TiB", "PiB"):
        value /= 1024
        if value < 1024:
            break
    return f"{value:.2f} {unit}"


def entry_size(item: dict) -> int:
    """取文件大小。缺失/非法 -> 该目录判失败（当 0 累加才是真正会咬人的脏数据）。"""
    raw = item.get("size")
    if isinstance(raw, bool) or not isinstance(raw, (int, float)):
        raise DirError(f"文件 {item.get('server_filename')!r} 的 size 字段缺失或非法: {raw!r}")
    try:
        size = int(raw)
    except (ValueError, OverflowError):
        raise DirError(f"文件 {item.get('server_filename')!r} 的 size 字段非法: {raw!r}") from None
    if size < 0:
        raise DirError(f"文件 {item.get('server_filename')!r} 的 size 为负: {size}")
    return size


def entry_mtime(item: dict) -> int | None:
    """取网盘返回的文件修改时间。字段名在不同响应里不一致，都没有则 None（不瞎判）。"""
    for key in ("server_mtime", "local_mtime", "mtime"):
        value = item.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
    return None


def compute_stale(meta_entry: dict, counted: list[dict]) -> str:
    """meta.json 是否比它旁边被统计的文件还旧（分片补齐后忘了更新 meta 的信号）。

    比 mtime 而不是比 computed_at：computed_at 是脚本时钟、mtime 是网盘时钟，
    跨时钟比较会把「本机时间偏了几分钟」放大成整表 stale。
    """
    meta_time = entry_mtime(meta_entry)
    times = [t for t in (entry_mtime(e) for e in counted) if t is not None]
    if meta_time is None or not times:
        return ""
    return "yes" if max(times) > meta_time else "no"


# --- 配置 --------------------------------------------------------------------

@dataclass(frozen=True)
class Config:
    token: str
    media_root: str
    meta_name: str
    order: str
    desc: int
    force: bool
    dry_run: bool
    verify: bool
    timeout: float
    upload_timeout: float
    attempts: int
    max_wait: float
    #: 报告 CSV 的输出路径；None = 不出报告
    report: str | None = None
    #: 只出报告：不算体积、不上传
    report_only: bool = False


@dataclass
class NetdiskDir:
    name: str
    path: str


@dataclass
class DirResult:
    name: str
    path: str
    outcome: str
    size_bytes: int = 0
    file_count: int = 0
    detail: str = ""
    warnings: list[str] = field(default_factory=list)
    #: 报告行；只有带 --report / --report-only 时才填
    row: "MetaRow | None" = None


@dataclass
class MetaRow:
    """报告的一行（CSV 的 10 列）。

    除 status/stale/detail 外的值都抽自磁盘上的 meta.json 本身；meta.json 缺失或
    不可用时那几列留空 —— 未知和「就是 0」不是一回事。
    """

    fan_code: str
    status: str
    path: str
    meta: dict | None = None
    stale: str = ""
    detail: str = ""

    def csv_cells(self) -> list:
        meta = self.meta or {}
        size = meta.get("size_bytes")
        size = size if is_positive_int(size) else None
        count = non_negative_int(meta.get("file_count"))
        dirs = non_negative_int(meta.get("dir_count"))
        computed_at = meta.get("computed_at")
        return [
            self.fan_code,
            self.status,
            size if size is not None else "",
            human_size(size) if size is not None else "",
            count if count is not None else "",
            dirs if dirs is not None else "",
            self.stale,
            computed_at if isinstance(computed_at, str) else "",
            self.path,
            self.detail,
        ]


# --- 网盘读写 -----------------------------------------------------------------

def list_dir(path: str, cfg: Config) -> list[dict]:
    """分页拉全一个目录的内容。errno != 0 抛 BaiduApiError；其余失败抛 DirError。"""
    items: list[dict] = []
    start = 0
    while True:
        query = urllib.parse.urlencode({
            "method": "list",
            "dir": path,
            "order": cfg.order,
            "desc": cfg.desc,
            "start": start,
            "limit": LIST_PAGE,
            "access_token": cfg.token,
        })
        url = f"{FILE_URL}?{query}"
        status, body, err = with_retry(lambda: http_get(url, cfg.timeout), cfg.attempts,
                                       cfg.max_wait, shorten(url))
        if status == 0:
            raise DirError(f"列表请求失败: {err}")
        if status != 200:
            raise DirError(f"列表 HTTP {status}: {body[:200]}")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as e:
            raise DirError(f"列表返回不是 JSON: {e}; 原文: {body[:200]}")

        errno = payload.get("errno", -1)
        if errno != 0:
            raise BaiduApiError(errno, payload.get("errmsg") or "")

        page = payload.get("list") or []
        if not page:
            break
        items.extend(page)
        if len(page) < LIST_PAGE:
            break
        start += len(page)
    return items


def list_top_dirs(cfg: Config) -> list[NetdiskDir]:
    """媒体根目录下的一级目录。整轮的前提，失败直接终止（exit 2）。"""
    try:
        items = list_dir(cfg.media_root, cfg)
    except BaiduApiError as e:
        hint = "（access_token 无效或过期，重新获取后再跑）" if e.is_token_invalid else ""
        die(f"读媒体根目录失败: {e}{hint}")
    except DirError as e:
        die(f"读媒体根目录失败: {e}")

    dirs: list[NetdiskDir] = []
    seen: set[str] = set()
    for item in items:
        if item.get("isdir") != 1:
            continue
        # 目录名原文入账，不做归一化：meta.json 记录的应该是「这个目录真的叫什么」
        name = item.get("server_filename") or ""
        if not name:
            continue
        path = item.get("path") or f"{cfg.media_root.rstrip('/')}/{name}"
        if path in seen:
            continue
        seen.add(path)
        dirs.append(NetdiskDir(name=name, path=path))
    return dirs


def download_meta(fsid: int, cfg: Config) -> dict:
    """按 fsid 下载并解析 meta.json。任何一步失败都抛 DirError（调用方据此放弃重算）。"""
    query = urllib.parse.urlencode({
        "method": "filemetas",
        "access_token": cfg.token,
        "dlink": 1,
        "fsids": f"[{fsid}]",
    })
    url = f"{MEDIA_URL}?{query}"
    status, body, err = with_retry(lambda: http_get(url, cfg.timeout), cfg.attempts,
                                   cfg.max_wait, "filemetas")
    if status == 0:
        raise DirError(f"取下载直链失败: {err}")
    if status != 200:
        raise DirError(f"取下载直链 HTTP {status}: {body[:200]}")
    try:
        payload = json.loads(body)
    except json.JSONDecodeError as e:
        raise DirError(f"filemetas 返回不是 JSON: {e}; 原文: {body[:200]}")
    errno = payload.get("errno", -1)
    if errno != 0:
        raise BaiduApiError(errno, payload.get("errmsg") or "")
    entries = payload.get("list") or []
    dlink = (entries[0].get("dlink") if entries else None) or ""
    if not dlink:
        raise DirError("下载直链为空")

    sep = "&" if "?" in dlink else "?"
    url = f"{dlink}{sep}{urllib.parse.urlencode({'access_token': cfg.token})}"
    status, text, err = with_retry(lambda: http_get(url, cfg.timeout, DOWNLOAD_UA), cfg.attempts,
                                   cfg.max_wait, "下载 meta.json")
    if status == 0:
        raise DirError(f"下载 meta.json 失败: {err}")
    if status != 200:
        raise DirError(f"下载 meta.json HTTP {status}: {text[:200]}")
    try:
        data = json.loads(text)
    except json.JSONDecodeError as e:
        raise DirError(f"已有 meta.json 不是合法 JSON: {e}; 原文: {text[:200]}")
    if not isinstance(data, dict):
        raise DirError(f"已有 meta.json 不是 JSON 对象，而是 {type(data).__name__}")
    return data


def build_multipart(filename: str, content: bytes) -> tuple[bytes, str]:
    """手工拼 multipart/form-data（PCS 上传只带一个 file 字段）。"""
    boundary = "----HlsPanMeta" + os.urandom(16).hex()
    ascii_name = filename.encode("ascii", "replace").decode("ascii")
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{ascii_name}"\r\n'
        f"Content-Type: application/octet-stream\r\n\r\n"
    ).encode("utf-8")
    tail = f"\r\n--{boundary}--\r\n".encode("utf-8")
    return head + content + tail, f"multipart/form-data; boundary={boundary}"


def upload_meta(remote_path: str, content: bytes, cfg: Config) -> dict:
    """上传（覆盖）meta.json。errno != 0 抛 BaiduApiError，其余失败抛 DirError。"""
    query = urllib.parse.urlencode({
        "method": "upload",
        "access_token": cfg.token,
        "path": remote_path,
        "ondup": "overwrite",
        "rtype": 3,
    })
    url = f"{PCS_UPLOAD_URL}?{query}"
    body, content_type = build_multipart(os.path.basename(remote_path), content)
    status, text, err = with_retry(
        lambda: http_post(url, body, content_type, cfg.upload_timeout),
        cfg.attempts, cfg.max_wait, f"上传 {os.path.basename(remote_path)}")
    if status == 0:
        raise DirError(f"上传失败: {err}")
    if status != 200:
        raise DirError(f"上传 HTTP {status}: {text[:200]}")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError as e:
        raise DirError(f"上传返回不是 JSON: {e}; 原文: {text[:200]}")
    errno = payload.get("errno", 0)
    if errno != 0:
        raise BaiduApiError(int(errno), payload.get("errmsg") or "")
    return payload


# --- 单个目录 -----------------------------------------------------------------

def build_meta(d: NetdiskDir, size_bytes: int, file_count: int, dir_count: int) -> dict:
    return {
        "schema_version": META_SCHEMA_VERSION,
        "fan_code": d.name,
        "path": d.path,
        "size_bytes": size_bytes,
        "size_human": human_size(size_bytes),
        "file_count": file_count,
        "dir_count": dir_count,
        "computed_at": datetime.now().astimezone().isoformat(timespec="seconds"),
        "computed_by": COMPUTED_BY,
    }


def failed(d: NetdiskDir, detail: str) -> DirResult:
    """失败兜底：连 meta.json 的状态都没拿到，报告里记 error + 原因。"""
    return DirResult(d.name, d.path, "error", detail=detail,
                     row=MetaRow(d.name, "error", d.path, detail=detail))


def process_dir(d: NetdiskDir, cfg: Config) -> DirResult:
    """处理一个影片目录：列目录 -> 读 meta.json -> 跳过 / 重算 / 只出报告。

    抛 BaiduApiError（token 失效时向上冒泡终止整轮）或 DirError（记 error 继续）。
    """
    entries = list_dir(d.path, cfg)
    files = [e for e in entries if e.get("isdir") != 1]
    subdirs = [e for e in entries if e.get("isdir") == 1]

    if PLAYLIST_NAME not in {(e.get("server_filename") or "") for e in files}:
        return DirResult(d.name, d.path, "not_movie", detail=f"缺 {PLAYLIST_NAME}")

    # 体积只算第一层：出现子目录说明假设被打破，必须喊出来，而不是静默算个偏小的数
    warnings: list[str] = []
    counted_subdirs = [e for e in subdirs if not is_junk(e.get("server_filename") or "")]
    if counted_subdirs:
        shown = ", ".join((e.get("server_filename") or "?") for e in counted_subdirs[:3])
        more = " 等" if len(counted_subdirs) > 3 else ""
        warnings.append(f"含 {len(counted_subdirs)} 个子目录未计入大小: {shown}{more}")

    counted = [e for e in files
               if not is_junk(e.get("server_filename") or "")
               and (e.get("server_filename") or "") != cfg.meta_name]
    meta_entry = next((e for e in files if (e.get("server_filename") or "") == cfg.meta_name), None)

    # 读磁盘上的 meta.json：跳过判定、报告行、--force 下失败时的回退行都要用
    existing: dict | None = None
    read_error = ""
    if meta_entry is not None and (not cfg.force or cfg.report is not None):
        try:
            existing = download_meta(int(meta_entry.get("fs_id") or 0), cfg)
        except DirError as e:
            read_error = str(e)
            if not cfg.force:
                # 读不出来就不敢动它：记 error、不重算、不覆盖
                return DirResult(d.name, d.path, "error", detail=read_error, warnings=warnings,
                                 row=MetaRow(d.name, "invalid", d.path, detail=read_error))
            # --force 下反正要覆盖，读失败无所谓

    if meta_entry is None:
        row = MetaRow(d.name, "missing", d.path)
    elif existing is not None:
        has_size = is_positive_int(existing.get("size_bytes"))
        row = MetaRow(
            d.name, "ok" if has_size else "no_size", d.path, existing,
            stale=compute_stale(meta_entry, counted),
            detail="" if has_size else "meta.json 里没有正整数 size_bytes",
        )
    else:
        # 只可能出现在 --force 下：旧 meta.json 读不出来，但反正要覆盖它
        row = MetaRow(d.name, "error", d.path, detail=read_error)

    # --report-only：只出表，不计算体积、不上传
    if cfg.report_only:
        return DirResult(d.name, d.path, "reported", warnings=warnings, row=row)

    # 已有可用体积信息 -> 跳过（磁盘现状就是这一行）
    if not cfg.force and existing is not None and is_positive_int(existing.get("size_bytes")):
        size = int(existing["size_bytes"])
        return DirResult(d.name, d.path, "skipped", size_bytes=size,
                         file_count=non_negative_int(existing.get("file_count")) or 0,
                         detail=human_size(size), warnings=warnings, row=row)

    size_bytes = 0
    file_count = 0
    for e in counted:
        size_bytes += entry_size(e)
        file_count += 1

    path = f"{d.path.rstrip('/')}/{cfg.meta_name}"
    meta = build_meta(d, size_bytes, file_count, len(counted_subdirs))
    # 末尾换行 + 固定字段顺序：同状态同内容，diff 才有意义
    content = json.dumps(meta, ensure_ascii=False, indent=2).encode("utf-8") + b"\n"

    if cfg.dry_run:
        return DirResult(d.name, d.path, "dry_run", size_bytes, file_count,
                         detail=human_size(size_bytes), warnings=warnings, row=row)

    upload_meta(path, content, cfg)

    if cfg.verify:
        entries = list_dir(d.path, cfg)
        target = next((e for e in entries if e.get("server_filename") == cfg.meta_name), None)
        if target is None:
            raise DirError(f"校验失败: 上传后目录里找不到 {cfg.meta_name}")
        readback = download_meta(int(target.get("fs_id") or 0), cfg)
        if readback != meta:
            raise DirError(f"校验失败: 回读内容与上传内容不一致 ({readback.get('size_bytes')} != {size_bytes})")

    # 上传成功：磁盘现状就是刚写下的这份
    return DirResult(d.name, d.path, "uploaded", size_bytes, file_count,
                     detail=human_size(size_bytes), warnings=warnings,
                     row=MetaRow(d.name, "ok", d.path, meta, stale="no"))


def run_one(d: NetdiskDir, cfg: Config) -> DirResult:
    """把 process_dir 的异常收敛成 DirResult；token 失效向上抛（终止整轮）。"""
    if ABORT.is_set():
        return failed(d, "已中止（token 失效）")
    try:
        return process_dir(d, cfg)
    except BaiduApiError as e:
        if e.is_token_invalid:
            ABORT.set()
            raise
        return failed(d, str(e))
    except DirError as e:
        return failed(d, str(e))
    except Exception as e:  # 兜底：一个目录的意外异常不该让整轮崩掉
        return failed(d, f"{type(e).__name__}: {e}")


# --- 输出 --------------------------------------------------------------------

def write_report(path: str, rows: list[MetaRow]) -> None:
    """写 CSV：UTF-8 BOM（Excel 双击不乱码），父目录不存在就建。"""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write("\ufeff")
        writer = csv.writer(fh)
        writer.writerow(REPORT_HEADER)
        for row in rows:
            writer.writerow(row.csv_cells())


def bad_rows(rows: list[MetaRow]) -> bool:
    """报告里是否存在需要动手的行（missing / invalid / no_size / error）。"""
    return any(r.status != "ok" for r in rows)


def print_report_summary(rows: list[MetaRow]) -> None:
    counts = {status: sum(1 for r in rows if r.status == status) for status in REPORT_STATUSES}
    parts = " | ".join(f"{status} {counts[status]}" for status in REPORT_STATUSES)
    stale = sum(1 for r in rows if r.stale == "yes")
    print(f"报告汇总: {len(rows)} 行 → {parts} | stale {stale}")


def report(results: list[DirResult], total: int) -> int:
    failures = sorted((r for r in results if r.outcome == "error"), key=lambda r: r.name)
    warned = [r for r in sorted(results, key=lambda r: r.name) if r.warnings]
    counts = {key: sum(1 for r in results if r.outcome == key) for key in OUTCOMES}

    if failures:
        print()
        print("=" * 72)
        print(f"失败（{len(failures)} 部，未写入 meta.json，重跑即可重试）")
        print("=" * 72)
        for r in failures:
            print(f"  {r.name:<28} {r.detail}")

    if warned:
        print()
        print("=" * 72)
        print(f"告警（{len(warned)} 部，体积口径可能偏小）")
        print("=" * 72)
        for r in warned:
            for w in r.warnings:
                print(f"  {r.name:<28} {w}")

    print()
    print(f"处理 {total} 个目录 → "
          f"跳过 {counts['skipped']} | 重算并上传 {counts['uploaded']} | "
          f"仅计算(--dry-run) {counts['dry_run']} | 非影片目录 {counts['not_movie']} | "
          f"失败 {len(failures)}")

    known = [r for r in results if r.outcome in ("skipped", "uploaded", "dry_run")]
    if known:
        tail = f"；失败 {len(failures)} 部未计入" if failures else ""
        print(f"已知体积合计: {human_size(sum(r.size_bytes for r in known))}（{len(known)} 部{tail}）")

    if counts["dry_run"]:
        print("（--dry-run 未上传任何文件；去掉该参数即真正写入）")

    return 1 if (failures or warned) else 0


# --- 入口 --------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="计算网盘影片目录的体积，并把 meta.json 写回影片目录",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--token", help="百度网盘 access_token（默认取 $BAIDU_ACCESS_TOKEN 或 --env-file）")
    p.add_argument("--env-file", help="从 .env 文件读取 ACCESS_TOKEN，如 ~/py_workspace/movies_service/hls_yunpan/.env")
    p.add_argument("--media-root", default=DEFAULT_MEDIA_ROOT,
                   help=f"网盘影片根目录 (默认 {DEFAULT_MEDIA_ROOT})")
    p.add_argument("--meta-name", default=DEFAULT_META_NAME,
                   help=f"写入影片目录的元数据文件名 (默认 {DEFAULT_META_NAME})")
    p.add_argument("--force", "--ignore-existing", action="store_true", dest="force",
                   help="忽略已存在的 meta.json，强制重新计算并覆盖上传（默认：已有有效体积信息则跳过）")
    p.add_argument("--dry-run", action="store_true", help="只计算、不上传（仍会下载已有 meta.json 以正确判定）")
    p.add_argument("--verify", action="store_true", help="上传后回读 meta.json 并比对（每部多 2 个请求）")
    p.add_argument("--only", action="append", default=[], metavar="SUBSTR",
                   help="只处理目录名包含该子串的项，可重复")
    p.add_argument("--exclude", action="append", default=[], metavar="SUBSTR",
                   help="跳过目录名包含该子串的项，可重复；如 --exclude trash")
    p.add_argument("--report", metavar="PATH",
                   help="把每部影片的 meta.json 汇总成 CSV（正常跑完后写出，零额外请求）")
    p.add_argument("--report-only", metavar="PATH",
                   help="只汇总出 CSV：不计算体积、不上传，跑完直接退出")
    p.add_argument("--order", default="name", choices=["name", "time", "size"], help="网盘列表排序字段")
    p.add_argument("--desc", type=int, default=1, choices=[0, 1], help="1=降序 0=升序（默认 1）")
    p.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                   help=f"并发处理的目录数（默认 {DEFAULT_WORKERS}；百度对列表接口有限流，别开太大）")
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT, help=f"列表/元数据请求超时秒数（默认 {DEFAULT_TIMEOUT:g}）")
    p.add_argument("--upload-timeout", type=float, default=DEFAULT_UPLOAD_TIMEOUT,
                   help=f"上传超时秒数（默认 {DEFAULT_UPLOAD_TIMEOUT:g}）")
    p.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS,
                   help=f"每个请求最多尝试次数（含首次，默认 {DEFAULT_MAX_ATTEMPTS}）；"
                        "超时/连接失败/429/5xx 会按指数退避重试")
    p.add_argument("--max-wait", type=float, default=DEFAULT_MAX_WAIT,
                   help=f"退避等待上限秒数（单次与累计都不超过，默认 {DEFAULT_MAX_WAIT:g}）")
    return p


def resolve_token(args) -> str:
    token = (args.token or "").strip()
    if not token and args.env_file:
        token = read_env_file(args.env_file).get("ACCESS_TOKEN", "").strip()
    if not token:
        token = (os.environ.get("BAIDU_ACCESS_TOKEN") or "").strip()
    if not token:
        die("缺少 access_token：用 --token / --env-file / $BAIDU_ACCESS_TOKEN 提供")
    return token


def main() -> int:
    args = build_parser().parse_args()
    media_root = (args.media_root or "").strip().rstrip("/")
    if not media_root:
        die("--media-root 不能为空")
    meta_name = (args.meta_name or "").strip()
    if not meta_name or "/" in meta_name:
        die("--meta-name 必须是不含 / 的文件名")
    report_path = args.report_only or args.report
    if args.report_only and args.report and args.report != args.report_only:
        die("--report 与 --report-only 只能给一个（--report-only 本身就是「只出表」）")

    cfg = Config(
        token=resolve_token(args),
        media_root=media_root,
        meta_name=meta_name,
        order=args.order,
        desc=args.desc,
        force=args.force,
        dry_run=args.dry_run,
        verify=args.verify,
        timeout=args.timeout,
        upload_timeout=args.upload_timeout,
        attempts=max(1, args.max_attempts),
        max_wait=args.max_wait,
        report=report_path,
        report_only=bool(args.report_only),
    )

    print(f"媒体根目录: {cfg.media_root}")
    print(f"元数据文件: {cfg.meta_name}")
    if cfg.report_only:
        print("模式      : 只出报告（不计算体积、不上传）")
        if cfg.force or cfg.dry_run:
            print("            （--report-only 下 --force / --dry-run 无意义，已忽略）")
    elif cfg.force:
        print("模式      : 强制重算（忽略已存在的 meta.json）")
    else:
        print("模式      : 默认（已有有效体积信息则跳过）")
    if cfg.dry_run and not cfg.report_only:
        print("            --dry-run，不会上传任何文件")
    if report_path:
        mode = "只出报告" if cfg.report_only else "随跑写出"
        print(f"报告      : {report_path}（{mode}）")
    print(f"重试策略  : 最多 {cfg.attempts} 次尝试，退避上限 {cfg.max_wait:g}s")
    print("拉取网盘目录列表...")

    dirs = list_top_dirs(cfg)
    if args.only:
        dirs = [d for d in dirs if any(s in d.name for s in args.only)]
    for sub in args.exclude:
        dirs = [d for d in dirs if sub not in d.name]
    if not dirs:
        die(f"媒体根目录下没列到目录，或都被 --only/--exclude 过滤掉了"
            f"（{cfg.media_root}）")

    workers = max(1, args.workers)
    print(f"共 {len(dirs)} 个目录，开始处理（并发 {workers}）...")

    results: list[DirResult] = []
    done = 0
    pool = ThreadPoolExecutor(max_workers=workers)
    try:
        futures = {pool.submit(run_one, d, cfg): d for d in dirs}
        for fut in as_completed(futures):
            d = futures[fut]
            done += 1
            print(f"  已处理 {done}/{len(dirs)}", end="\r", file=sys.stderr, flush=True)
            try:
                results.append(fut.result())
            except BaiduApiError as e:
                ABORT.set()
                for f in futures:
                    f.cancel()
                print(" " * 40, end="\r", file=sys.stderr)
                print()
                print(f"access_token 无效或已过期（{e}），已中止。", file=sys.stderr)
                print("重新获取 access_token 后再跑；已成功的不受影响（默认会跳过）。", file=sys.stderr)
                return 2
    except KeyboardInterrupt:
        ABORT.set()
        print(" " * 40, end="\r", file=sys.stderr)
        print("\n已中断。重跑即可继续（已写入的会自动跳过）。", file=sys.stderr)
        return 130
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
    print(" " * 40, end="\r", file=sys.stderr)

    rows: list[MetaRow] = []
    if report_path:
        # 行序按名称（不能用线程完成顺序：同一状态两次跑出来的 CSV 行序会不同）
        rows = sorted((r.row for r in results if r.row is not None), key=lambda r: r.fan_code)
        try:
            write_report(report_path, rows)
        except OSError as e:
            die(f"写 CSV 失败 {report_path}: {e}")
        print(f"已写出 CSV: {report_path}")

    if cfg.report_only:
        not_movie = sum(1 for r in results if r.outcome == "not_movie")
        if not_movie:
            print(f"（另有 {not_movie} 个目录不含 {PLAYLIST_NAME}，未进报告）")
        print_report_summary(rows)
        return 1 if bad_rows(rows) else 0

    code = report(results, len(dirs))
    if report_path:
        print_report_summary(rows)
        # --dry-run 那一轮本来就没打算写东西，不因「磁盘上还没 meta.json」升码
        if bad_rows(rows) and not cfg.dry_run:
            code = 1
    return code


if __name__ == "__main__":
    sys.exit(main())
