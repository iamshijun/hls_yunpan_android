#!/usr/bin/env python3
"""网盘有影片目录、但影片详情服务缺记录的巡检脚本。

只依赖标准库，独立运行，不碰 Android 工程也不碰 movies_service 的代码。

流程：
  1. 网盘列表分页接口 (pan.baidu.com/rest/2.0/xpan/file?method=list) 拉全
     <media-root> 下的一级目录，目录名即番号 (fan_code)；
  2. 按批（默认 30 个/批）调影片列表接口的 fan_code 过滤
     (GET <api-base>/api/movies?page=1&size=30&fan_code=a,b,c)，然后比对：
       - 响应里没有该番号  -> 完全缺失（服务端压根没有这条记录）
       - 有记录但必填字段为空 -> 字段缺失（有记录但信息不全）
       - 本批请求失败      -> 查询失败（网络/HTTP/解析，整批归为未知，不当缺失处理）
  3. 只输出/导出「要动手补的」：完全缺失 + 字段缺失 + 查询失败；正常的不列表、不占行
     （需要完整清册时加 --include-ok），可导出 CSV/JSON 供人工补全（movie_admin 里照着加）。

  所有请求（含网盘列表翻页）都有重试：超时/连接失败/429/5xx 按指数退避重试，
  默认最多 5 次尝试，单次与累计退避等待都不超过 7 秒（--max-attempts / --max-wait）。
  重试耗尽才算「查询失败」，从不当成「完全缺失」。

  需要时可 --mode detail 退回逐条 `GET /api/movies/{fan_code}`（404 即缺失），
  用于复核某几个番号；此时请求数与番号数相同，慢很多。

用法：
  python3 tools/find_missing_movie_details.py --token <access_token>

  # 常用变体
  python3 tools/find_missing_movie_details.py --env-file ../movies_service/hls_yunpan/.env \\
      --api-base https://www.asitanokibou.xyz/movies --out missing.csv
  python3 tools/find_missing_movie_details.py --batch-size 50
  python3 tools/find_missing_movie_details.py --require title,cover,casts,year
  python3 tools/find_missing_movie_details.py --exclude 'trash' --exclude '@eaDir'
  python3 tools/find_missing_movie_details.py --mode detail --token <token>   # 逐条复核
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

# --- 与 Android 工程 / hls_yunpan 对齐的常量 ---------------------------------

BAIDU_LIST_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

#: 与 app/build.gradle.kts 的 BAIDU_APP_NAME 对齐
DEFAULT_MEDIA_ROOT = "/apps/asitanokibou/movies"
#: 与 AppSettings.DEFAULT_MOVIE_API_BASE_URL 对齐
DEFAULT_API_BASE = "https://www.asitanokibou.xyz/movies"

#: 百度 list 接口单页上限
BATCH_SIZE = 1000

#: 影片接口路径（列表 + 单条详情同前缀）
MOVIES_PATH = "/api/movies"

#: 单批查询的番号数上限（fan_code 是逗号分隔塞进 query 的，别塞太长）
DEFAULT_BATCH_SIZE = 30

#: 单批翻页上限：响应 total 大于本页条数时继续翻，防止 size 被服务端截断而误判缺失
MAX_BATCH_PAGES = 10

# --- 请求重试 -----------------------------------------------------------------

#: 每个请求最多尝试次数（含首次）
DEFAULT_MAX_ATTEMPTS = 5
#: 退避等待上限（秒）：单次等待与一次请求的累计等待都不超过它
DEFAULT_MAX_WAIT = 7.0
#: 退避基数（秒），第 n 次等待 = BASE * FACTOR^(n-1)，再被上限截断
BACKOFF_BASE = 0.5
BACKOFF_FACTOR = 2.0
#: 可重试的 HTTP 状态码：超时/限流/网关与 5xx；其余 4xx 是确定结论，不重试
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

#: 详情接口可校验的字段（fan_code 恒有值，不参与校验）
CHECKABLE_FIELDS = (
    "title", "year", "cover", "thumbnail",
    "duration", "description", "casts", "labels",
)
#: 批量模式走的是列表接口，而它刻意不返回 description（见 movie_api store._rows_to_movies）
BATCH_UNCHECKABLE_FIELDS = ("description",)
DEFAULT_REQUIRED = "title"


# --- 小工具 ------------------------------------------------------------------

def nfkc(value: str) -> str:
    """NFKC 归一化：全/半角统一，与 movie_api 的 fan_code 存储规则一致。"""
    return unicodedata.normalize("NFKC", value or "")


def die(msg: str, code: int = 1):
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(code)


def http_get(url: str, timeout: float) -> tuple[int, str]:
    """GET 并返回 (status_code, body_text)。HTTP 4xx/5xx 不抛异常，交给调用方分类。"""
    req = urllib.request.Request(url, headers={"User-Agent": WEB_UA})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


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


# --- 第一步：网盘目录分页 ----------------------------------------------------

@dataclass
class NetdiskDir:
    fan_code: str
    path: str
    fs_id: int


def list_netdisk_dirs(
    media_root: str,
    token: str,
    order: str,
    desc: int,
    timeout: float,
    delay: float,
    attempts: int,
    max_wait: float,
) -> list[NetdiskDir]:
    """分页拉全 <media_root> 下的一级目录。errno != 0 直接终止（列表拉不全，后续结论全是错的）。"""
    dirs: list[NetdiskDir] = []
    seen: set[str] = set()
    start = 0
    while True:
        params = urllib.parse.urlencode({
            "method": "list",
            "dir": media_root,
            "order": order,
            "desc": desc,
            "start": start,
            "limit": BATCH_SIZE,
            "access_token": token,
        })
        # 网盘侧同样重试：翻页翻到一半超时会让结果变成一个“看起来没错”的半截列表
        status, body, err = http_get_retry(f"{BAIDU_LIST_URL}?{params}", timeout, attempts, max_wait)
        if status == 0:
            die(f"网盘列表接口不可用: {err}")
        if status != 200:
            die(f"网盘列表接口 HTTP {status}: {body[:300]}")

        try:
            payload = json.loads(body)
        except json.JSONDecodeError as e:
            die(f"网盘列表返回不是 JSON: {e}; 原文: {body[:300]}")

        errno = payload.get("errno", -1)
        if errno != 0:
            hint = {
                -6: "access_token 无效或过期",
                -9: "文件不存在",
                -12: "目录不存在",
            }.get(errno, "未知错误")
            die(f"网盘列表接口失败 errno={errno} ({hint}) {payload.get('errmsg', '')}")

        page = payload.get("list", []) or []
        if not page:
            break

        for item in page:
            if item.get("isdir") != 1:
                continue
            name = nfkc(item.get("server_filename") or "").strip()
            if not name:
                continue
            # 同名（归一化后）只收一次，避免百度返回重复项时把同一个番号查两遍
            if name in seen:
                continue
            seen.add(name)
            dirs.append(NetdiskDir(fan_code=name, path=item.get("path") or "", fs_id=item.get("fs_id") or 0))

        print(f"  已获取 {len(dirs)} 个目录...", end="\r", file=sys.stderr, flush=True)

        if len(page) < BATCH_SIZE:
            break
        start += len(page)
        if delay:
            time.sleep(delay)

    print(" " * 40, end="\r", file=sys.stderr)
    return dirs


# --- 第二步：影片详情校验（批量 / 逐条）-------------------------------------

@dataclass
class DetailResult:
    fan_code: str
    path: str
    #: ok / missing(404) / incomplete / error
    outcome: str
    missing_fields: list[str] = field(default_factory=list)
    detail: str = ""


def http_get_retry(url: str, timeout: float, attempts: int, max_wait: float) -> tuple[int, str, str]:
    """带指数退避的 GET，返回 (status, body, error)。

    可重试的情形：连接失败/超时（urllib 抛异常）、以及 408/429/5xx。
    4xx（除 429）是确定结论（如 404 = 没这条记录），不重试。

    最多 [attempts] 次请求；第 n 次失败后等 `BACKOFF_BASE * FACTOR^(n-1)` 秒，
    单次与累计等待都不超过 [max_wait] 秒；退避预算用尽后不再等待，剩下的尝试立即重试。
    重试耗尽时返回 status=0 + 最后一次错误描述，供调用方归类为「查询失败」。
    """
    attempts = max(1, attempts)
    waited = 0.0
    status, body, last_err = 0, "", ""
    for attempt in range(1, attempts + 1):
        try:
            status, body = http_get(url, timeout)
        except Exception as e:  # 连接失败 / 超时 / TLS 等，统一文本化
            status, body, last_err = 0, "", f"{type(e).__name__}: {e}"
        else:
            if not status or status not in RETRYABLE_STATUS:
                return status, body, ""  # 成功，或是不可重试的 4xx
            last_err = f"HTTP {status}: {body[:120]}"

        if attempt == attempts:
            break
        # 指数退避，受单次上限与累计预算双重节流；预算用尽后不再等，剩下的尝试立即重试
        delay = min(BACKOFF_BASE * (BACKOFF_FACTOR ** (attempt - 1)), max_wait, max_wait - waited)
        if delay > 0:
            waited += delay
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → {delay:.1f}s 后重试 "
                  f"({shorten(url)})", file=sys.stderr, flush=True)
            time.sleep(delay)
        else:
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → 退避预算已用尽，立即重试 "
                  f"({shorten(url)})", file=sys.stderr, flush=True)

    return 0, "", f"重试 {attempts} 次仍失败: {last_err}"


def shorten(url: str, limit: int = 70) -> str:
    """URL 压成一行日志：去掉 scheme/host 后只留路径，太长就截断。"""
    path = urllib.parse.urlsplit(url).path
    query = urllib.parse.urlsplit(url).query
    text = f"{path}?{query}" if query else path
    return text if len(text) <= limit else text[:limit - 1] + "…"


def fetch_detail(api_base: str, fan_code: str, timeout: float, attempts: int, max_wait: float,
                 ) -> tuple[int, str | None, str]:
    """取单条详情，返回 (status, payload_text|None, error)。404 是有效结论，不算 error。"""
    url = f"{api_base}{MOVIES_PATH}/{urllib.parse.quote(fan_code, safe='')}"
    status, body, err = http_get_retry(url, timeout, attempts, max_wait)
    if status == 0:
        return 0, None, err
    if status == 404:
        return 404, None, ""
    if status != 200:
        return status, None, f"HTTP {status}: {body[:200]}"
    return 200, body, ""


def fetch_batch(api_base: str, codes: list[str], timeout: float, attempts: int, max_wait: float,
                ) -> tuple[dict[str, dict], str]:
    """批量取详情，返回 (fan_code -> 记录, error)。error 非空表示整批不可信。

    `fan_code` 过滤走服务端精确匹配（逗号分隔）。total 大于本页条数时继续翻页，
    避免服务端截断 size 导致已存在的记录被误判成缺失。
    """
    found: dict[str, dict] = {}
    size = max(1, len(codes))
    page = 1
    while True:
        params = urllib.parse.urlencode({
            "page": page,
            "size": size,
            "fan_code": ",".join(codes),
        })
        status, body, err = http_get_retry(f"{api_base}{MOVIES_PATH}?{params}", timeout, attempts, max_wait)
        if status == 0:
            return {}, f"批量请求失败: {err}"
        if status != 200:
            return {}, f"批量查询 HTTP {status}: {body[:200]}"

        try:
            payload = json.loads(body or "{}")
        except json.JSONDecodeError as e:
            return {}, f"批量响应不是 JSON: {e}; 原文: {body[:200]}"

        rows = payload.get("data") or []
        new_codes = 0
        for row in rows:
            code = nfkc(str(row.get("fan_code") or "")).strip()
            if not code:
                continue
            if code not in found:
                new_codes += 1
            found[code] = row

        # 以服务端 total 为准：收齐了才停。若某页没带来新番号（空页/重复页/
        # size 被服务端截断到 0），再翻也没用，直接停。
        total = payload.get("total") or 0
        if len(found) >= total or new_codes == 0:
            return found, ""
        page += 1
        if page > MAX_BATCH_PAGES:
            return found, f"结果被截断（翻页超过 {MAX_BATCH_PAGES} 页，total={total}），本批结论不可信"


def check_batch(api_base: str, batch: list[NetdiskDir], required: list[str], timeout: float, attempts: int,
                max_wait: float) -> list[DetailResult]:
    """一整批一起查：响应里找不到 = 完全缺失；找到但必填字段为空 = 字段缺失。"""
    found, err = fetch_batch(api_base, [d.fan_code for d in batch], timeout, attempts, max_wait)
    results = []
    for d in batch:
        if err:
            results.append(DetailResult(d.fan_code, d.path, "error", detail=err))
            continue
        movie = found.get(d.fan_code)
        if movie is None:
            results.append(DetailResult(d.fan_code, d.path, "missing"))
            continue
        absent = [f for f in required if not is_filled(movie.get(f))]
        if absent:
            results.append(DetailResult(d.fan_code, d.path, "incomplete", missing_fields=absent))
        else:
            results.append(DetailResult(d.fan_code, d.path, "ok"))
    return results


def check_detail_batch(api_base: str, batch: list[NetdiskDir], required: list[str], timeout: float,
                       attempts: int, max_wait: float) -> list[DetailResult]:
    """逐条详情模式的适配层：签名与 check_batch 一致，好让并发调度只有一条路径。"""
    return [check_one(api_base, d, required, timeout, attempts, max_wait) for d in batch]


def check_one(api_base: str, d: NetdiskDir, required: list[str], timeout: float, attempts: int,
              max_wait: float) -> DetailResult:
    status, body, err = fetch_detail(api_base, d.fan_code, timeout, attempts, max_wait)
    if status == 404:
        return DetailResult(d.fan_code, d.path, "missing")
    if status != 200:
        return DetailResult(d.fan_code, d.path, "error", detail=err or f"HTTP {status}")

    try:
        movie = json.loads(body or "{}")
    except json.JSONDecodeError as e:
        return DetailResult(d.fan_code, d.path, "error", detail=f"详情不是 JSON: {e}")

    # 响应是按目录名（已 NFKC）精确匹配到的记录；这里只判字段，不再比对响应里的番号
    absent = [f for f in required if not is_filled(movie.get(f))]
    if absent:
        return DetailResult(d.fan_code, d.path, "incomplete", missing_fields=absent)
    return DetailResult(d.fan_code, d.path, "ok")


def is_filled(value) -> bool:
    """字段是否算「有值」：None / 空串 / 空列表 / 空白字符串 都算缺。"""
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, dict, set)):
        return len(value) > 0
    if isinstance(value, (int, float)):
        return True
    return bool(value)


# --- 输出 --------------------------------------------------------------------

def report(results: list[DetailResult], out_path: str | None, json_path: str | None, quiet: bool,
           include_ok: bool = False):
    # 并发完成顺序不定，按番号排一下，输出/对比才稳定
    missing = sorted((r for r in results if r.outcome == "missing"), key=lambda r: r.fan_code)
    incomplete = sorted((r for r in results if r.outcome == "incomplete"), key=lambda r: r.fan_code)
    errors = sorted((r for r in results if r.outcome == "error"), key=lambda r: r.fan_code)
    # 导出只看「要动手补的」：正常的不占行，需要时用 --include-ok 带上
    exported = sorted(results, key=lambda x: (x.outcome, x.fan_code)) if include_ok \
        else missing + incomplete + errors

    if not quiet:
        if not (missing or incomplete or errors):
            print()
            print(f"{len(results)} 部影片都已有详情记录，无需补全。")

        if missing:
            print()
            print("=" * 72)
            print(f"完全缺失（列表接口查不到，共 {len(missing)} 部）")
            print("=" * 72)
            for r in missing:
                print(f"  {r.fan_code:<28} {r.path}")

        if incomplete:
            print()
            print("=" * 72)
            print(f"字段缺失（记录存在但必填字段为空，共 {len(incomplete)} 部）")
            print("=" * 72)
            for r in incomplete:
                print(f"  {r.fan_code:<28} 缺: {', '.join(r.missing_fields)}")

        if errors:
            print()
            print("=" * 72)
            print(f"查询失败（{len(errors)} 部，结论未知，建议重跑）")
            print("=" * 72)
            for r in errors:
                print(f"  {r.fan_code:<28} {r.detail}")

    print()
    print(f"合计: 目录 {len(results)} 个 | 完全缺失 {len(missing)} | 字段缺失 {len(incomplete)} | 查询失败 {len(errors)}")
    ok_count = len(results) - len(missing) - len(incomplete) - len(errors)
    if not include_ok and ok_count and (missing or incomplete or errors):
        print(f"（正常 {ok_count} 个不列出，可用 --include-ok 一并带上）")

    if out_path:
        # UTF-8 BOM：Excel 双击不乱码，与 movie_api 的 export 约定一致
        with open(out_path, "w", encoding="utf-8", newline="") as fh:
            fh.write("\ufeff")
            writer = csv.writer(fh)
            writer.writerow(["fan_code", "status", "missing_fields", "netdisk_path", "detail"])
            for r in exported:
                writer.writerow([
                    r.fan_code,
                    {"missing": "完全缺失", "incomplete": "字段缺失", "error": "查询失败", "ok": "正常"}[r.outcome],
                    ",".join(r.missing_fields),
                    r.path,
                    r.detail,
                ])
        print(f"已写出 CSV: {out_path}")

    if json_path:
        with open(json_path, "w", encoding="utf-8") as fh:
            json.dump(
                [
                    {
                        "fan_code": r.fan_code,
                        "status": r.outcome,
                        "missing_fields": r.missing_fields,
                        "netdisk_path": r.path,
                        "detail": r.detail,
                    }
                    for r in exported
                ],
                fh,
                ensure_ascii=False,
                indent=2,
            )
        print(f"已写出 JSON: {json_path}")


# --- 入口 --------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="巡检：网盘里有影片目录、但影片详情服务缺记录/缺字段的番号",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--token", help="百度网盘 access_token（默认取 $BAIDU_ACCESS_TOKEN 或 --env-file）")
    p.add_argument("--env-file", help="从 .env 文件读取 ACCESS_TOKEN，如 ../movies_service/hls_yunpan/.env")
    p.add_argument("--api-base", default=DEFAULT_API_BASE, help=f"影片详情服务地址 (默认 {DEFAULT_API_BASE})")
    p.add_argument("--media-root", default=DEFAULT_MEDIA_ROOT, help=f"网盘影片根目录 (默认 {DEFAULT_MEDIA_ROOT})")
    p.add_argument("--mode", default="batch", choices=["batch", "detail"],
                   help="batch=列表接口按批查（默认，30 个/请求）；detail=逐个详情接口查")
    p.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE,
                   help=f"批量模式下每个请求带的番号数（默认 {DEFAULT_BATCH_SIZE}）")
    p.add_argument("--require", default=DEFAULT_REQUIRED,
                   help=f"必填字段，逗号分隔，取值 {','.join(CHECKABLE_FIELDS)}；留空则只查 404。默认 {DEFAULT_REQUIRED}")
    p.add_argument("--order", default="name", choices=["name", "time", "size"], help="网盘列表排序字段")
    p.add_argument("--desc", type=int, default=1, choices=[0, 1], help="1=降序 0=升序（默认 1）")
    p.add_argument("--exclude", action="append", default=[], metavar="SUBSTR",
                   help="跳过目录名包含该子串的项，可重复；如 --exclude trash")
    p.add_argument("--workers", type=int, default=4, help="并发请求数（默认 4）")
    p.add_argument("--timeout", type=float, default=15.0, help="单请求超时秒数（默认 15）")
    p.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS,
                   help=f"每个请求最多尝试次数（含首次，默认 {DEFAULT_MAX_ATTEMPTS}）；"
                        "超时/连接失败/429/5xx 会按指数退避重试")
    p.add_argument("--max-wait", type=float, default=DEFAULT_MAX_WAIT,
                   help=f"退避等待上限秒数（单次与累计都不超过，默认 {DEFAULT_MAX_WAIT:g}）")
    p.add_argument("--delay", type=float, default=0.0, help="网盘列表翻页间隔秒数（默认 0）")
    p.add_argument("--out", help="导出 CSV 报告路径")
    p.add_argument("--json-out", help="导出 JSON 报告路径")
    p.add_argument("--quiet", action="store_true", help="只打印汇总，不逐条列出")
    p.add_argument("--include-ok", action="store_true",
                   help="导出时连「正常」的也带上（默认只导出缺失/失败的）")
    return p


def main() -> int:
    args = build_parser().parse_args()

    token = (args.token or "").strip()
    if not token and args.env_file:
        token = read_env_file(args.env_file).get("ACCESS_TOKEN", "").strip()
    if not token:
        token = (os.environ.get("BAIDU_ACCESS_TOKEN") or "").strip()
    if not token:
        die("缺少 access_token：用 --token / --env-file / $BAIDU_ACCESS_TOKEN 提供")

    api_base = args.api_base.strip().rstrip("/")
    if not api_base:
        die("--api-base 不能为空")

    required = [f.strip() for f in (args.require or "").split(",") if f.strip()]
    bad = [f for f in required if f not in CHECKABLE_FIELDS]
    if bad:
        die(f"--require 含未知字段 {bad}；可用: {', '.join(CHECKABLE_FIELDS)}")
    if args.mode == "batch":
        uncheckable = [f for f in required if f in BATCH_UNCHECKABLE_FIELDS]
        if uncheckable:
            die(f"批量模式查不了 {uncheckable}（列表接口不返回该字段）；"
                f"改用 --mode detail，或从 --require 里去掉")

    print(f"网盘根目录: {args.media_root}")
    print(f"详情服务  : {api_base}")
    print(f"必填字段  : {', '.join(required) if required else '(仅检查记录是否存在)'}")
    if args.mode == "batch":
        print(f"查询方式  : 批量（每批 {max(1, args.batch_size)} 个番号）")
    else:
        print("查询方式  : 逐条详情接口")
    print(f"重试策略  : 最多 {max(1, args.max_attempts)} 次尝试，退避上限 {args.max_wait:g}s")
    print("拉取网盘目录列表...")

    dirs = list_netdisk_dirs(
        media_root=args.media_root, token=token, order=args.order,
        desc=args.desc, timeout=args.timeout, delay=args.delay,
        attempts=max(1, args.max_attempts), max_wait=args.max_wait,
    )
    for sub in args.exclude:
        dirs = [d for d in dirs if sub not in d.fan_code]
    if not dirs:
        print("没拿到任何目录，检查 media-root / access_token")
        return 1

    # 批内用 fan_code 逗号分隔过滤，番号本身带逗号的没法这么查，单独走详情接口
    units: list[list[NetdiskDir]] = []
    odd: list[NetdiskDir] = []
    if args.mode == "batch":
        size = max(1, args.batch_size)
        quotable = [d for d in dirs if "," not in d.fan_code]
        odd = [d for d in dirs if "," in d.fan_code]
        units = [quotable[i:i + size] for i in range(0, len(quotable), size)]
    else:
        units = [[d] for d in dirs]
    checker = check_batch if args.mode == "batch" else check_detail_batch
    work = [(checker, u) for u in units] + [(check_detail_batch, [d]) for d in odd]

    print(f"共 {len(dirs)} 个影片目录 → {len(work)} 个请求任务，开始查询（并发 {args.workers}）...")
    results: list[DetailResult] = []
    done_codes = 0
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {
            pool.submit(fn, api_base, unit, required, args.timeout,
                        max(1, args.max_attempts), args.max_wait): unit
            for fn, unit in work
        }
        for fut in as_completed(futures):
            unit = futures[fut]
            try:
                results.extend(fut.result())
            except Exception as e:  # 兜底：一个批次的异常绝不该让整轮巡检崩掉
                detail = f"{type(e).__name__}: {e}"
                results.extend(DetailResult(d.fan_code, d.path, "error", detail=detail) for d in unit)
            done_codes += len(unit)
            print(f"  已查 {done_codes}/{len(dirs)}", end="\r", file=sys.stderr, flush=True)
    print(" " * 40, end="\r", file=sys.stderr)

    report(results, args.out, args.json_out, args.quiet, args.include_ok)
    return 0


if __name__ == "__main__":
    sys.exit(main())
