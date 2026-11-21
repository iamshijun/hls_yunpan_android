#!/usr/bin/env python3
"""巡检：playlist.m3u8 里列出的分片，在网盘目录里是不是真的存在。

「缺片」的定义只有一个方向 —— **写在 playlist 里的分片名，网盘目录里没有同名文件**。
反过来（目录里多出来的孤儿分片）不报：播放只认 playlist，多出来的东西不咬人。

判定口径：
  - playlist 是唯一依据：非 `#` 开头的非空行即分片行（本项目是单层 media playlist，
    里面直接是 `seg_000123.ts` 这类文件名）；
  - 比对是**精确同名**：不做 NFKC、不改大小写、不剥前后缀。分片名只取 basename
    （去掉 `?query`），带绝对 URL（`http://`）的分片无法与目录比对，跳过并告警；
  - 分片名去重后再比对；目录列表按 1000/页翻页拉全 ——
    2000+ 片的片子必须靠翻页才不漏；
  - 目录里没有 playlist.m3u8 的不算影片目录（与 calc_media_size.py 同口径），
    master playlist（只有 #EXT-X-STREAM-INF）、不是 m3u8、没有分片行 属于
    「无法检查」，不是「缺片」，不进 CSV。

分片号 (slug) 的口径：
  - 本项目分片统一 `seg_<数字>.ts`；slug = 去掉 `seg_` 前缀与扩展名后的十进制数
    （`seg_001023.ts` -> `1023`，前导零丢掉，`str(int(...))` 归一）；
  - 不符合该命名的分片，slug **原样**保留文件名（`chunk-a.ts` 就报 `chunk-a.ts`），
    宁可刺眼也不要被静默吞掉；
  - 同一部影片的 slug 去重后，**数字升序在前，其它字符串升序在后**。

输出：
  - 列：`fan_code,missing_slugs`；第二列用 `,` 拼接（CSV 会自动加引号，表格软件
    读出来就是一列）；
  - 只有「有缺片」的番号出行，无缺片的不出行 —— 这张表是待办的补片清单；
  - 默认打到 stdout，`--out PATH` 才落盘（UTF-8 BOM + CRLF，Excel 双击不乱码）；
  - `PATH` 已存在 -> 拒绝覆盖并 exit 2：缺片清单是拿去补片/对账的凭据，
    被半张表踩掉比多敲一个文件名糟。中断/中止时写 `<stem>.partial-<ts>.csv`，不碰原路径。

检查顺序与数量（`--sort` / `--limit`）：
  - `--sort time-desc`（默认）按番号目录的 mtime 新->旧；另有 `time-asc` / `name-asc` /
    `name-desc`。mtime 缺失的番号恒排最后（没时间就不该混进「最新」里）；
  - 同一 mtime 的番号稳定按番号升序，两次跑出来的顺序不会飘；
  - 排序只决定「先检查谁」，**CSV 行序永远是番号升序**（这样两次结果可以直接 diff）；
  - `--limit N` 取排序后的前 N 部，`0`（默认）= 不限。它只影响本轮覆盖范围，
    不改变「缺片」的判定口径；摘要会回显实际生效的策略和覆盖率。

请求量：每部影片 = 目录列表（翻页）+ 1 次 filemetas + 1 次 playlist 下载，
**不下载任何分片内容**、不写网盘。全库几百部是分钟级。

退出码：0 = 巡检干净（无缺片、无失败）；1 = 有缺片，或存在无法检查/失败的番号
        （覆盖不完整也不算通过）；2 = 参数错 / token 缺失或失效 / 媒体根目录读不了；
        130 = 被 Ctrl-C 中断。

用法：
  python3 tools/find_missing_segments.py --env-file ~/py_workspace/movies_service/hls_yunpan/.env

  # 常用变体
  python3 tools/find_missing_segments.py --env-file ... --out missing_segments.csv
  python3 tools/find_missing_segments.py --env-file ... --only abc-123
  python3 tools/find_missing_segments.py --env-file ... --only "abc-123,cdf-345" --workers 4
  python3 tools/find_missing_segments.py --env-file ... | tee missing_segments.csv

  # 只看最近改动的一批（默认 time-desc，最新在前）
  python3 tools/find_missing_segments.py --env-file ... --limit 20
  python3 tools/find_missing_segments.py --env-file ... --sort name-asc --limit 50

网盘访问与 CLI/IO 支撑都在同目录的共享模块里（`netdisk.py` / `common.py`），
本脚本只保留「缺片」这一件事的口径与报告。
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import threading
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
from datetime import datetime

from common import (DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_WAIT, DEFAULT_TIMEOUT, atomic_write,
                    die, render_csv, resolve_token, split_terms, unique_path, with_stamp, write_csv)
from netdisk import (DEFAULT_MEDIA_ROOT, PLAYLIST_NAME, BaiduApiError, DirError, NetdiskClient,
                     NetdiskDir)

# --- 常量 --------------------------------------------------------------------

CSV_HEADER = ("fan_code", "missing_slugs")
#: 本项目分片命名：seg_<数字>.ts。大小写不敏感 —— 大写命名的也该归一成同一个号
SEGMENT_RE = re.compile(r"^seg_(\d+)$", re.IGNORECASE)
#: 并发处理的番号数（百度对列表接口有限流，别开太大）
DEFAULT_WORKERS = 4

#: 检查顺序：显式带方向，一个参数说清「时间倒序」，不用再拿 --desc 拼
SORT_CHOICES = ("time-desc", "time-asc", "name-asc", "name-desc")
SORT_LABELS = {
    "time-desc": "按目录 mtime 新→旧",
    "time-asc": "按目录 mtime 旧→新",
    "name-asc": "按番号升序",
    "name-desc": "按番号降序",
}

#: 结局分类（汇总按它计数）
OUTCOMES = ("ok", "missing", "not_movie", "not_inspectable", "error")


@dataclass
class MovieResult:
    fan_code: str
    path: str
    #: ok / missing / not_movie / not_inspectable / error
    outcome: str
    #: 已归一、已去重、已排序的缺失分片号；只有 missing 时才非空
    slugs: list[str] = field(default_factory=list)
    detail: str = ""
    warnings: list[str] = field(default_factory=list)


# --- playlist 解析 -----------------------------------------------------------

def parse_segments(text: str) -> tuple[list[str], str, list[str]]:
    """解析 m3u8，返回 (分片文件名列表, 无法检查的原因, 告警列表)。

    第二个值非空 = 「这部片没法检」（master playlist / 不是 m3u8 / 没有分片行），
    这是数据本身的问题，重试一万次也一样，所以不当缺片、不进 CSV。
    """
    warnings: list[str] = []
    if not text.lstrip("\ufeff \t\r\n").startswith("#EXTM3U"):
        return [], "内容不是 m3u8（缺 #EXTM3U 头）", warnings
    if "#EXT-X-STREAM-INF" in text:
        return [], "master playlist（只有 #EXT-X-STREAM-INF），未下钻", warnings

    names: list[str] = []
    absolute = 0
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "://" in line:
            # 远端绝对地址：和网盘目录无从比对，只能跳过并喊出来
            absolute += 1
            continue
        name = line.split("?", 1)[0].rsplit("/", 1)[-1]
        if name:
            names.append(name)

    if absolute:
        warnings.append(f"{absolute} 个绝对 URL 分片无法与目录比对，已跳过")
    if not names:
        return [], "playlist 里没有分片行", warnings
    if "#EXT-X-ENDLIST" not in text:
        warnings.append("缺 #EXT-X-ENDLIST，转码/上传可能还没完成")
    return names, "", warnings


def slug_of(filename: str) -> str:
    """seg_001023.ts -> 1023；认不出的命名原样返回文件名（带扩展名）。"""
    stem, _ = os.path.splitext(filename)
    stem = stem or filename  # 纯 `.ts` 这种怪名不要被吃成空串
    match = SEGMENT_RE.match(stem)
    return str(int(match.group(1))) if match else filename


def slug_sort_key(slug: str) -> tuple:
    """数字号按数值升序在前，其它命名按字符串升序排在后面。"""
    return (0, int(slug), "") if slug.isdigit() else (1, 0, slug)


def sort_dirs(dirs: list[NetdiskDir], order: str) -> list[NetdiskDir]:
    """决定「先检查谁」（只影响扫描顺序，CSV 行序仍由 rows_of 按番号升序固定）。

    先按番号升序排一次，再按 mtime 排：Python 的排序是稳定的，于是同一 mtime 的
    番号在两种方向下都稳定按番号升序，两次跑出来的顺序不会飘。
    mtime 缺失的番号恒排最后 —— 没有时间就不该混进「最新的」那一批。
    """
    by_name = sorted(dirs, key=lambda d: d.name)
    if order.startswith("name"):
        return by_name if order == "name-asc" else by_name[::-1]
    newest_first = order == "time-desc"
    dated = [d for d in by_name if d.mtime is not None]
    undated = [d for d in by_name if d.mtime is None]
    dated.sort(key=lambda d: d.mtime, reverse=newest_first)
    return dated + undated


# --- 单部影片 ----------------------------------------------------------------

def process_movie(d: NetdiskDir, pan: NetdiskClient) -> MovieResult:
    """列目录 + 下载 playlist -> 对比出缺失分片号。

    抛 BaiduApiError（token 失效时向上冒泡终止整轮）或 DirError（记 error 继续）。
    """
    entries = pan.list(d.path)
    files = [e for e in entries if e.get("isdir") != 1]
    playlist = next((e for e in files
                     if (e.get("server_filename") or "") == PLAYLIST_NAME), None)
    if playlist is None:
        return MovieResult(d.name, d.path, "not_movie", detail=f"缺 {PLAYLIST_NAME}")

    text = pan.download_text(int(playlist.get("fs_id") or 0), PLAYLIST_NAME)
    names, reason, warnings = parse_segments(text)
    if reason:
        return MovieResult(d.name, d.path, "not_inspectable", detail=reason, warnings=warnings)

    # 目录里实际存在的文件名（含第一层的 playlist / meta.json，不影响比对）
    present = {e.get("server_filename") or "" for e in files}
    # dict.fromkeys 去重且保序：同一片在 playlist 里出现两次只报一次
    missing = [n for n in dict.fromkeys(names) if n not in present]
    slugs = sorted({slug_of(n) for n in missing}, key=slug_sort_key)
    detail = f"playlist 列出 {len(dict.fromkeys(names))} 片，有 {len(slugs)} 个分片号缺失" \
        if slugs else ""
    return MovieResult(d.name, d.path, "missing" if slugs else "ok", slugs, detail, warnings)


def run_one(d: NetdiskDir, pan: NetdiskClient) -> MovieResult:
    """把 process_movie 的异常收敛成 MovieResult；token 失效向上抛（终止整轮）。"""
    try:
        return process_movie(d, pan)
    except BaiduApiError as e:
        if e.is_token_invalid:
            raise
        return MovieResult(d.name, d.path, "error", detail=str(e))
    except DirError as e:
        return MovieResult(d.name, d.path, "error", detail=str(e))
    except Exception as e:  # 兜底：一个番号的意外异常不该让整轮崩掉
        return MovieResult(d.name, d.path, "error", detail=f"{type(e).__name__}: {e}")


# --- 输出 --------------------------------------------------------------------

def rows_of(results: list[MovieResult]) -> list[tuple[str, str]]:
    """只留缺片行（第二列已拼成 `100,2333`），按番号升序。

    不能用线程完成顺序：两次跑出来的行序会不同。
    """
    return [(r.fan_code, ",".join(r.slugs)) for r in sorted(results, key=lambda r: r.fan_code)
            if r.outcome == "missing"]


def csv_text(results: list[MovieResult]) -> str:
    """CSV 文本（给 stdout 用；落盘那份带 BOM，见 common.write_csv）。"""
    return render_csv(CSV_HEADER, rows_of(results)).decode("utf-8-sig")


def write_partial(path: str | None, results: list[MovieResult]) -> None:
    """中断/中止时的半轮结果：写 `<stem>.partial-<ts>.csv`，绝不占用目标路径。"""
    text = csv_text(results)
    if not path:
        sys.stdout.write(text)
        sys.stdout.flush()
        return
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    target = unique_path(with_stamp(path, f"partial-{stamp}"))
    try:
        atomic_write(target, b"\xef\xbb\xbf" + text.encode("utf-8"))
    except OSError as e:
        print(f"写部分结果失败 {target}: {e}", file=sys.stderr)
        return
    info(f"已写出部分结果: {target}（{len(rows_of(results))} 行，不是完整一轮）")


def info(message: str) -> None:
    """人看的文字一律走 stderr：stdout 只留 CSV，管道/重定向才不会混进汇总行。"""
    print(message, file=sys.stderr, flush=True)


def print_details(results: list[MovieResult]) -> None:
    """把缺片/无法检查/失败逐条打到 stderr（stdout 留给 CSV，方便管道）。"""
    for r in sorted(results, key=lambda r: r.fan_code):
        if r.outcome == "missing":
            line = f"  [缺片] {r.fan_code}: {','.join(r.slugs)}"
        elif r.outcome == "not_inspectable":
            line = f"  [无法检查] {r.fan_code}: {r.detail}"
        elif r.outcome == "error":
            line = f"  [失败] {r.fan_code}: {r.detail}"
        else:
            continue
        warns = f"（{'；'.join(r.warnings)}）" if r.warnings else ""
        print(line + warns, file=sys.stderr)


def print_summary(results: list[MovieResult], total: int) -> None:
    counts = Counter(r.outcome for r in results)
    slug_total = sum(len(r.slugs) for r in results)
    for line in (
        f"扫描番号    : {len(results)}/{total}",
        f"有缺片      : {counts['missing']} 部，共 {slug_total} 个分片号",
        f"无缺片      : {counts['ok']} 部",
        f"无 {PLAYLIST_NAME}: {counts['not_movie']} 部（不是影片目录，跳过）",
        f"无法检查    : {counts['not_inspectable']} 部（master playlist / 不是 m3u8 / 无分片行）",
        f"处理失败    : {counts['error']} 部",
    ):
        info(line)


# --- 入口 --------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="巡检 playlist.m3u8 里列出的分片是否都还在网盘目录里（只读，不改网盘）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--token", help="百度网盘 access_token（默认取 $BAIDU_ACCESS_TOKEN 或 --env-file）")
    p.add_argument("--env-file", help="从 .env 文件读取 ACCESS_TOKEN，"
                                      "如 ~/py_workspace/movies_service/hls_yunpan/.env")
    p.add_argument("--media-root", default=DEFAULT_MEDIA_ROOT,
                   help=f"网盘影片根目录 (默认 {DEFAULT_MEDIA_ROOT})")
    p.add_argument("--only", action="append", default=[], metavar="SUBSTR[,SUBSTR...]",
                   help="只检查目录名包含该子串的番号；逗号分隔可给多个，也可重复给 --only")
    p.add_argument("--exclude", action="append", default=[], metavar="SUBSTR[,SUBSTR...]",
                   help="跳过目录名包含该子串的番号；逗号分隔可给多个，也可重复给")
    p.add_argument("--out", metavar="PATH",
                   help="把缺片清单写成 CSV（UTF-8 BOM + CRLF）；不给则打到 stdout；"
                        "已存在的文件不会被覆盖")
    p.add_argument("--limit", type=int, default=0, metavar="N",
                   help="排序后只检查前 N 部（0 = 不限，默认 0）；只影响覆盖范围，不改变缺片判定")
    p.add_argument("--sort", default="time-desc", choices=SORT_CHOICES,
                   help="检查顺序（默认 time-desc = 目录 mtime 新→旧）；只影响先检查谁，"
                        "CSV 行序永远是番号升序")
    p.add_argument("--workers", type=int, default=DEFAULT_WORKERS,
                   help=f"并发检查的番号数（默认 {DEFAULT_WORKERS}；百度对列表接口有限流，别开太大）")
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                   help=f"列表/元数据/下载请求超时秒数（默认 {DEFAULT_TIMEOUT:g}）")
    p.add_argument("--max-attempts", type=int, default=DEFAULT_MAX_ATTEMPTS,
                   help=f"每个请求最多尝试次数（含首次，默认 {DEFAULT_MAX_ATTEMPTS}）；"
                        "超时/连接失败/429/5xx 会按指数退避重试")
    p.add_argument("--max-wait", type=float, default=DEFAULT_MAX_WAIT,
                   help=f"退避等待上限秒数（单次与累计都不超过，默认 {DEFAULT_MAX_WAIT:g}）")
    return p


def main() -> int:
    args = build_parser().parse_args()
    media_root = (args.media_root or "").strip().rstrip("/")
    if not media_root:
        die("--media-root 不能为空")
    if args.limit < 0:
        die("--limit 不能为负数（0 = 不限）")
    out = (args.out or "").strip()
    if out and os.path.exists(out):
        die(f"{out} 已存在，拒绝覆盖（缺片清单是对账凭据；换个路径或先挪走它）")

    abort = threading.Event()
    pan = NetdiskClient(
        resolve_token(args),
        timeout=args.timeout,
        attempts=max(1, args.max_attempts),
        max_wait=args.max_wait,
        abort=abort,
    )

    info(f"媒体根目录: {media_root}")
    info(f"判定依据  : {PLAYLIST_NAME} 列出的分片名 vs 同目录文件名（精确同名）")
    info(f"输出去向  : {out or 'stdout'}")
    info("拉取网盘目录列表...")

    try:
        dirs = pan.top_dirs(media_root)
    except BaiduApiError as e:
        hint = "（access_token 无效或过期，重新获取后再跑）" if e.is_token_invalid else ""
        die(f"读媒体根目录失败: {e}{hint}")
    except DirError as e:
        die(f"读媒体根目录失败: {e}")

    only = split_terms(args.only)
    exclude = split_terms(args.exclude)
    if only:
        dirs = [d for d in dirs if any(s in d.name for s in only)]
    if exclude:
        dirs = [d for d in dirs if not any(s in d.name for s in exclude)]
    if not dirs:
        die(f"媒体根目录下没列到目录，或都被 --only/--exclude 过滤掉了（{media_root}）")

    # 排序 -> 限量：筛完之后才谈「前 N 部」，否则 --only 与 --limit 的先后会互相咬
    available = len(dirs)
    dirs = sort_dirs(dirs, args.sort)
    limited = 0 < args.limit < available
    if limited:
        dirs = dirs[:args.limit]

    workers = max(1, args.workers)
    info(f"排序      : {args.sort}（{SORT_LABELS[args.sort]}）")
    info(f"限制      : {(f'前 {args.limit} 部' if args.limit else '不限')}，本轮 {len(dirs)}/{available}")
    info(f"开始检查（并发 {workers}，每部 3 个请求左右）...")

    results: list[MovieResult] = []
    done = 0
    pool = ThreadPoolExecutor(max_workers=workers)
    try:
        futures = {pool.submit(run_one, d, pan): d for d in dirs}
        for fut in as_completed(futures):
            done += 1
            print(f"  已检查 {done}/{len(dirs)}", end="\r", file=sys.stderr, flush=True)
            try:
                results.append(fut.result())
            except BaiduApiError as e:
                abort.set()
                for f in futures:
                    f.cancel()
                print(" " * 40, end="\r", file=sys.stderr)
                info(f"access_token 无效或已过期（{e}），已中止。")
                info("重新获取 access_token 后再跑；巡检是只读的，重跑一遍即可。")
                write_partial(out, results)
                return 2
    except KeyboardInterrupt:
        abort.set()
        print(" " * 40, end="\r", file=sys.stderr)
        info("已中断。")
        write_partial(out, results)
        return 130
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
    print(" " * 40, end="\r", file=sys.stderr)

    rows = rows_of(results)
    if out:
        # 前面已确认不存在；exclusive 只是兜住这中间的竞争
        write_csv(out, CSV_HEADER, rows, exclusive=True)
        info(f"已写出 CSV: {out}（{len(rows)} 行有缺片）")
    else:
        sys.stdout.write(render_csv(CSV_HEADER, rows).decode("utf-8-sig"))
        sys.stdout.flush()

    print_details(results)
    print_summary(results, len(dirs))
    if limited:
        info(f"注意: --limit {args.limit} 只覆盖了 {len(dirs)}/{available} 部，"
             f"其余番号本轮未检查（不代表它们不缺片）")

    counts = Counter(r.outcome for r in results)
    if counts["missing"]:
        return 1
    # 有「没法检」或「检失败」的番号 = 覆盖不完整，不算通过
    if counts["not_inspectable"] or counts["error"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
