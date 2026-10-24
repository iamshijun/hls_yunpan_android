#!/usr/bin/env python3
"""反向巡检：影片详情服务(movie_api)里有记录、但网盘里没有对应番号目录的影片。

与 find_missing_movie_details.py 的方向相反（那个查「云盘有、API 缺」）。

流程：
  1. 全量枚举影片详情服务：GET <api-base>/api/movies?page=N&size=M，响应 {data, total}，
     翻到收齐 total 为止（番号去重；某页没带来新番号就停，防止排序不稳时死循环）；
  2. 全量枚举网盘 <media-root> 下的一级目录名；
  3. 以 API 的 fan_code 为准做**完全匹配**（不做 NFKC、不改大小写、不剥前缀后缀），
     网盘里没有同名目录 -> 判定缺失；
  4. 默认只报告；给了 `--delete` 就真删 API 记录。

「缺失」只表示网盘没有这个目录；目录存在但缺 playlist.m3u8 / meta.json 不算 —
前者是转码没完成，后者跑 calc_media_size.py 就行，都不该删 API 记录。

删除是**不可逆**的：先不带 --delete 跑一遍看清单，确认无误再加 --delete。另外：
  - API 全量没收齐（翻页漏行）时拒绝删除，只出报告；
  - 网盘里存在「只差首尾空白」的同名目录时不当缺失（极可能是真匹配）。

退出码：0=没有缺失  1=有缺失（已报告）  2=列表拉不全/参数错  3=有删除失败

用法：
  python3 tools/find_missing_netdisk_movies.py --env-file ../movies_service/hls_yunpan/.env \\
      --api-base https://www.asitanokibou.xyz/movies --out missing_in_netdisk.csv

  # 清单确认无误后，加 --delete 真删
  python3 tools/find_missing_netdisk_movies.py ... --delete
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field

# --- 与 Android 工程 / hls_yunpan 对齐的常量 ---------------------------------

BAIDU_LIST_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
WEB_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"

#: 与 app/build.gradle.kts 的 BAIDU_APP_NAME 对齐
DEFAULT_MEDIA_ROOT = "/apps/asitanokibou/movies"
#: 与 AppSettings.DEFAULT_MOVIE_API_BASE_URL 对齐
DEFAULT_API_BASE = "https://www.asitanokibou.xyz/movies"
#: 影片接口路径（列表 + 单条详情同前缀，删除是 DELETE /api/movies/{fan_code}）
MOVIES_PATH = "/api/movies"

#: 百度 list 接口单页上限
BAIDU_PAGE_SIZE = 1000
#: 影片列表接口单页条数（服务端可能再截断，不够就靠 total 兜底）
DEFAULT_API_PAGE_SIZE = 100
#: 翻页上限：防服务端忽略 page 参数时死循环（100 * 200 = 2 万部）
MAX_API_PAGES = 200

# --- 请求重试 -----------------------------------------------------------------

DEFAULT_MAX_ATTEMPTS = 5
DEFAULT_MAX_WAIT = 7.0
BACKOFF_BASE = 0.5
BACKOFF_FACTOR = 2.0
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})


# --- 小工具 ------------------------------------------------------------------

def die(msg: str, code: int = 2):
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(code)


def http_request(method: str, url: str, timeout: float) -> tuple[int, str]:
    """发一个请求，返回 (status_code, body_text)。HTTP 4xx/5xx 不抛异常，交给调用方分类。"""
    req = urllib.request.Request(url, headers={"User-Agent": WEB_UA}, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def shorten(url: str, limit: int = 70) -> str:
    """URL 压成一行日志：去掉 scheme/host 后只留路径，太长就截断。"""
    parts = urllib.parse.urlsplit(url)
    text = f"{parts.path}?{parts.query}" if parts.query else parts.path
    return text if len(text) <= limit else text[:limit - 1] + "…"


def request_retry(method: str, url: str, timeout: float, attempts: int, max_wait: float,
                  ) -> tuple[int, str, str]:
    """带指数退避的请求，返回 (status, body, error)。

    可重试：连接失败/超时、408/429/5xx。其余 4xx 是确定结论（如 404），不重试。
    第 n 次失败后等 `BASE * FACTOR^(n-1)` 秒，单次与累计都不超过 max_wait；
    预算用尽后不再等待，剩下的尝试立即重试。
    """
    attempts = max(1, attempts)
    waited = 0.0
    status, body, last_err = 0, "", ""
    for attempt in range(1, attempts + 1):
        try:
            status, body = http_request(method, url, timeout)
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
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → {delay:.1f}s 后重试 "
                  f"({shorten(url)})", file=sys.stderr, flush=True)
            time.sleep(delay)
        else:
            print(f"    重试 {attempt}/{attempts - 1}: {last_err} → 退避预算已用尽，立即重试 "
                  f"({shorten(url)})", file=sys.stderr, flush=True)

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


# --- 第一步：网盘一级目录名 ---------------------------------------------------

def list_netdisk_names(media_root: str, token: str, order: str, desc: int, timeout: float,
                       delay: float, attempts: int, max_wait: float) -> list[str]:
    """分页拉全 <media_root> 下的一级目录名（原样字符串，不做任何归一化）。

    errno != 0 直接终止：目录拉不全，后续「网盘里没有」的结论全是错的。
    """
    names: list[str] = []
    seen: set[str] = set()
    start = 0
    while True:
        params = urllib.parse.urlencode({
            "method": "list",
            "dir": media_root,
            "order": order,
            "desc": desc,
            "start": start,
            "limit": BAIDU_PAGE_SIZE,
            "access_token": token,
        })
        status, body, err = request_retry("GET", f"{BAIDU_LIST_URL}?{params}",
                                         timeout, attempts, max_wait)
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

        page = payload.get("list") or []
        if not page:
            break

        for item in page:
            if item.get("isdir") != 1:
                continue
            name = item.get("server_filename") or ""
            if not name or name in seen:
                continue
            seen.add(name)
            names.append(name)

        print(f"  网盘已获取 {len(names)} 个目录...", end="\r", file=sys.stderr, flush=True)

        if len(page) < BAIDU_PAGE_SIZE:
            break
        start += len(page)
        if delay:
            time.sleep(delay)

    print(" " * 44, end="\r", file=sys.stderr)
    return names


# --- 第二步：影片详情服务全量枚举 --------------------------------------------

@dataclass
class ApiInventory:
    movies: list[dict] = field(default_factory=list)
    total: int = 0
    #: 非致命问题（例如翻页漏行），出现时结论不完整
    warnings: list[str] = field(default_factory=list)

    @property
    def complete(self) -> bool:
        return not self.warnings


def list_api_movies(api_base: str, page_size: int, timeout: float, attempts: int, max_wait: float,
                    ) -> ApiInventory:
    """全量枚举影片记录（不传 fan_code 过滤），按 fan_code 去重后返回。

    收齐 total 即停；某页没带来任何新番号也停（服务端忽略 page 时会无限重复同一页）。
    收不满 total 只告警、不当场终止：漏行只会少报缺失，不会多报。
    """
    inv = ApiInventory()
    seen: set[str] = set()
    page = 1
    page_size = max(1, page_size)
    while True:
        params = urllib.parse.urlencode({"page": page, "size": page_size})
        status, body, err = request_retry("GET", f"{api_base}{MOVIES_PATH}?{params}",
                                         timeout, attempts, max_wait)
        if status == 0:
            die(f"影片列表接口不可用: {err}")
        if status != 200:
            die(f"影片列表接口 HTTP {status}: {body[:300]}")

        try:
            payload = json.loads(body or "{}")
        except json.JSONDecodeError as e:
            die(f"影片列表返回不是 JSON: {e}; 原文: {body[:300]}")

        rows = payload.get("data") or []
        total = payload.get("total")
        if isinstance(total, int) and total > inv.total:
            inv.total = total

        new_codes = 0
        for row in rows:
            # 以 API 的 fan_code 为准：只去首尾空白，不改大小写、不做 NFKC
            code = str(row.get("fan_code") or "").strip()
            if not code or code in seen:
                continue
            seen.add(code)
            row["fan_code"] = code
            inv.movies.append(row)
            new_codes += 1

        print(f"  API 已获取 {len(inv.movies)}/{inv.total or '?'} 条...",
              end="\r", file=sys.stderr, flush=True)

        if not rows or new_codes == 0:
            # 空页，或服务端把 page 当空气（同一页吐了无数遍）：再翻也没用
            break
        if inv.total and len(inv.movies) >= inv.total:
            break
        if len(rows) < page_size:
            break          # 最后一页（服务端 total 不可信时靠这个收尾）
        page += 1
        if page > MAX_API_PAGES:
            inv.warnings.append(f"翻页超过 {MAX_API_PAGES} 页仍未收齐（已收 {len(inv.movies)}）")
            break

    print(" " * 44, end="\r", file=sys.stderr)
    if inv.total and len(inv.movies) < inv.total:
        inv.warnings.append(f"只收到 {len(inv.movies)} 条，服务端 total={inv.total}"
                            f"（可能有漏行，结论偏保守）")
    if not inv.movies:
        die("影片列表一条都没拿到；确认 --api-base 正确，且列表接口允许不带 fan_code 全量查")
    return inv


# --- 第三步：比对 -------------------------------------------------------------

@dataclass
class MissingMovie:
    fan_code: str
    title: str
    year: str
    #: missing / deleted / delete_failed / delete_skipped
    status: str = "missing"
    detail: str = ""


def diff_missing(inv: ApiInventory, netdisk_names: list[str]) -> list[MissingMovie]:
    """以 API 为主：fan_code 在网盘目录名里找不到（完全相等）就是缺失。"""
    present = set(netdisk_names)
    return [MissingMovie(m["fan_code"], str(m.get("title") or ""), str(m.get("year") or ""))
            for m in inv.movies if m["fan_code"] not in present]


def find_near_misses(inv: ApiInventory, netdisk_names: list[str]) -> list[MissingMovie]:
    """只差首尾空白的近似匹配：单独列出，不参与删除（不当成真缺失）。"""
    present = set(netdisk_names)
    stripped: dict[str, str] = {}
    for name in netdisk_names:
        stripped.setdefault(name.strip(), name)
    out: list[MissingMovie] = []
    for row in inv.movies:
        code = row["fan_code"]
        if code in present:
            continue
        near = stripped.get(code)
        if near is not None:
            out.append(MissingMovie(code, str(row.get("title") or ""), str(row.get("year") or ""),
                                    status="near_miss",
                                    detail=f"网盘目录 {near!r} 与番号只差首尾空白"))
    return out


# --- 第四步：删除 -------------------------------------------------------------

def delete_movie(api_base: str, fan_code: str, timeout: float, attempts: int, max_wait: float,
                 ) -> tuple[bool, str]:
    """DELETE /api/movies/{fan_code}。404 视为已经不存在（算成功）。"""
    url = f"{api_base}{MOVIES_PATH}/{urllib.parse.quote(fan_code, safe='')}"
    status, body, err = request_retry("DELETE", url, timeout, attempts, max_wait)
    if status == 0:
        return False, f"删除请求失败: {err}"
    if status in (200, 204):
        return True, "已删除"
    if status == 404:
        return True, "服务端本来就没有这条记录"
    return False, f"删除失败 HTTP {status}: {body[:200]}"


# --- 输出 --------------------------------------------------------------------

def report(missing: list[MissingMovie], total_movies: int, netdisk_dirs: int,
           out_path: str | None, quiet: bool):
    deleted = [m for m in missing if m.status == "deleted"]
    failed = [m for m in missing if m.status == "delete_failed"]
    skipped = [m for m in missing if m.status == "delete_skipped"]
    pending = [m for m in missing if m.status == "missing"]

    if not quiet:
        if not missing:
            print(f"{total_movies} 条影片记录在网盘里都有对应目录，没有孤儿记录。")
        else:
            print("=" * 72)
            print(f"网盘里没有对应目录的影片（共 {len(missing)} 条）")
            print("=" * 72)
            for m in missing:
                mark = {"deleted": "已删", "delete_failed": "删失败",
                        "delete_skipped": "跳过"}.get(m.status, "缺失")
                # 标题放最后一欄且不截断：前面两列定宽，长标题只往右长不影响对齐
                title = m.title.strip() or "(无标题)"
                line = f"  [{mark}] {m.fan_code:<26} {m.year:<6} {title}".rstrip()
                if m.detail:
                    line += f"    ({m.detail})"
                print(line)

    print()
    print(f"合计: API 影片 {total_movies} 条 | 网盘目录 {netdisk_dirs} 个 | "
          f"云盘缺目录 {len(missing)}")
    if deleted:
        print(f"已删除 API 记录: {len(deleted)} 条")
    if skipped:
        print(f"跳过未删（全量没收齐，拒绝删除）: {len(skipped)} 条")
    if failed:
        print(f"删除失败: {len(failed)} 条（可重跑，或手动在 movie_admin 里删）")
        for m in failed:
            print(f"  {m.fan_code:<26} {m.title.strip() or '(无标题)'}    {m.detail}")
    if pending:
        print(f"仅报告未删: {len(pending)} 条（加 --delete 真删）")

    if out_path:
        # UTF-8 BOM：Excel 双击不乱码，与 movie_api 的 export 约定一致
        with open(out_path, "w", encoding="utf-8", newline="") as fh:
            fh.write("\ufeff")
            writer = csv.writer(fh)
            writer.writerow(["fan_code", "title", "year", "status", "detail"])
            for m in missing:
                writer.writerow([m.fan_code, m.title, m.year, m.status, m.detail])
        print(f"已写出 CSV: {out_path}")


# --- 入口 --------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="巡检：影片详情服务里有记录、但网盘里没有对应番号目录的影片（孤儿记录）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--token", help="百度网盘 access_token（默认取 $BAIDU_ACCESS_TOKEN 或 --env-file）")
    p.add_argument("--env-file", help="从 .env 文件读取 ACCESS_TOKEN，如 ../movies_service/hls_yunpan/.env")
    p.add_argument("--api-base", default=DEFAULT_API_BASE,
                   help=f"影片详情服务地址 (默认 {DEFAULT_API_BASE})")
    p.add_argument("--media-root", default=DEFAULT_MEDIA_ROOT,
                   help=f"网盘影片根目录 (默认 {DEFAULT_MEDIA_ROOT})")
    p.add_argument("--api-size", type=int, default=DEFAULT_API_PAGE_SIZE,
                   help=f"影片列表每页条数 (默认 {DEFAULT_API_PAGE_SIZE})")
    p.add_argument("--order", default="name", choices=["name", "time", "size"], help="网盘列表排序字段")
    p.add_argument("--desc", type=int, default=1, choices=[0, 1], help="1=降序 0=升序（默认 1）")
    p.add_argument("--delete", action="store_true",
                   help="删除孤儿 API 记录（不可逆）；不给就只报告")
    p.add_argument("--timeout", type=float, default=15.0, help="单请求超时秒数（默认 15）")
    p.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS,
                   help=f"每个请求最多尝试次数（含首次，默认 {DEFAULT_MAX_ATTEMPTS}）；"
                        "超时/连接失败/429/5xx 会按指数退避重试")
    p.add_argument("--max-wait", type=float, default=DEFAULT_MAX_WAIT,
                   help=f"退避等待上限秒数（单次与累计都不超过，默认 {DEFAULT_MAX_WAIT:g}）")
    p.add_argument("--delay", type=float, default=0.0, help="网盘列表翻页间隔秒数（默认 0）")
    p.add_argument("--out", help="导出 CSV 报告路径")
    p.add_argument("--quiet", action="store_true", help="只打印汇总，不逐条列出")
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

    attempts = max(1, args.max_attempts)
    print(f"网盘根目录: {args.media_root}")
    print(f"详情服务  : {api_base}")
    print("对齐规则  : fan_code 与网盘目录名完全相等（不做 NFKC / 大小写折叠）")
    print("删除      : " + ("开（不可逆）" if args.delete else "关（只报告）"))
    print(f"重试策略  : 最多 {attempts} 次尝试，退避上限 {args.max_wait:g}s")

    print("\n拉取影片详情服务全量记录...")
    inv = list_api_movies(api_base, args.api_size, args.timeout, attempts, args.max_wait)
    for w in inv.warnings:
        print(f"  注意: {w}", file=sys.stderr)

    print("拉取网盘一级目录...")
    names = list_netdisk_names(args.media_root, token, args.order, args.desc,
                               args.timeout, args.delay, attempts, args.max_wait)
    if not names:
        die("没拿到任何目录，检查 media-root / access_token")

    near = find_near_misses(inv, names)
    near_codes = {m.fan_code for m in near}
    missing = [m for m in diff_missing(inv, names) if m.fan_code not in near_codes]
    if near:
        print(f"  注意: {len(near)} 条在网盘里有只差首尾空白的同名目录，未判为缺失：", file=sys.stderr)
        for m in near[:5]:
            print(f"    {m.fan_code:<28} {m.detail}", file=sys.stderr)

    # 删除：全量没收齐就不动，宁可多报也不可错删
    if args.delete and missing:
        if not inv.complete:
            for m in missing:
                m.status = "delete_skipped"
            print("  拒绝删除：API 全量没收齐（见上面告警），只出报告", file=sys.stderr)
        else:
            print(f"\n删除 {len(missing)} 条孤儿记录...")
            for i, m in enumerate(missing, 1):
                ok, detail = delete_movie(api_base, m.fan_code, args.timeout, attempts, args.max_wait)
                m.status = "deleted" if ok else "delete_failed"
                m.detail = detail
                print(f"  [{i}/{len(missing)}] {m.fan_code:<26} {m.title.strip() or '(无标题)'}"
                      f"  → {detail}", file=sys.stderr, flush=True)

    print()
    report(missing, len(inv.movies), len(names), args.out, args.quiet)

    if any(m.status == "delete_failed" for m in missing):
        return 3
    if not inv.complete:
        return 2
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
