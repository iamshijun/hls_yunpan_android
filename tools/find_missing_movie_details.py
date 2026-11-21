#!/usr/bin/env python3
"""网盘有影片目录、但影片详情服务缺记录的巡检脚本。

只依赖 Python 标准库与 tools/ 下的共享模块（common / netdisk / movie_api），
独立运行，不碰 Android 工程也不碰 movies_service 的代码。

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
import json
import sys
import unicodedata
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field

from common import (DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_WAIT, DEFAULT_TIMEOUT, die, resolve_token,
                    write_csv)
from movie_api import DEFAULT_API_BASE, MovieApiClient, MovieApiError, MovieRecord
from netdisk import DEFAULT_MEDIA_ROOT, BaiduApiError, DirError, NetdiskClient, NetdiskDir

#: 单批查询的番号数上限（fan_code 是逗号分隔塞进 query 的，别塞太长）
DEFAULT_BATCH_SIZE = 30

#: 单批翻页上限：响应 total 大于本页条数时继续翻，防止 size 被服务端截断而误判缺失
MAX_BATCH_PAGES = 10

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


# --- 第二步：影片详情校验（批量 / 逐条）-------------------------------------

@dataclass
class DetailResult:
    fan_code: str
    path: str
    #: ok / missing(404) / incomplete / error
    outcome: str
    missing_fields: list[str] = field(default_factory=list)
    detail: str = ""


def fetch_batch(api: MovieApiClient, codes: list[str]) -> tuple[dict[str, MovieRecord], str]:
    """批量取记录，返回 (fan_code -> 记录, error)。error 非空表示整批不可信。

    `fan_code` 过滤走服务端精确匹配（逗号分隔）。total 大于本页条数时继续翻页，
    避免服务端截断 size 导致已存在的记录被误判成缺失。
    """
    found: dict[str, MovieRecord] = {}
    size = max(1, len(codes))
    page = 1
    while True:
        try:
            records, total = api.page(page, size, fan_codes=codes)
        except MovieApiError as e:
            return {}, f"批量请求失败: {e}"

        new_codes = 0
        for record in records:
            code = nfkc(record.fan_code).strip()
            if not code:
                continue
            if code not in found:
                new_codes += 1
            found[code] = record

        # 以服务端 total 为准：收齐了才停。若某页没带来新番号（空页/重复页/
        # size 被服务端截断到 0），再翻也没用，直接停。
        if len(found) >= total or new_codes == 0:
            return found, ""
        page += 1
        if page > MAX_BATCH_PAGES:
            return found, f"结果被截断（翻页超过 {MAX_BATCH_PAGES} 页，total={total}），本批结论不可信"


def check_batch(api: MovieApiClient, batch: list[NetdiskDir], required: list[str]) -> list[DetailResult]:
    """一整批一起查：响应里找不到 = 完全缺失；找到但必填字段为空 = 字段缺失。"""
    found, err = fetch_batch(api, [d.name for d in batch])
    results = []
    for d in batch:
        if err:
            results.append(DetailResult(d.name, d.path, "error", detail=err))
            continue
        movie = found.get(d.name)
        if movie is None:
            results.append(DetailResult(d.name, d.path, "missing"))
            continue
        absent = [f for f in required if not is_filled(movie.field(f))]
        if absent:
            results.append(DetailResult(d.name, d.path, "incomplete", missing_fields=absent))
        else:
            results.append(DetailResult(d.name, d.path, "ok"))
    return results


def check_detail_batch(api: MovieApiClient, batch: list[NetdiskDir], required: list[str],
                       ) -> list[DetailResult]:
    """逐条详情模式的适配层：签名与 check_batch 一致，好让并发调度只有一条路径。"""
    return [check_one(api, d, required) for d in batch]


def check_one(api: MovieApiClient, d: NetdiskDir, required: list[str]) -> DetailResult:
    try:
        movie = api.detail(d.name)
    except MovieApiError as e:
        return DetailResult(d.name, d.path, "error", detail=str(e))
    if movie is None:
        return DetailResult(d.name, d.path, "missing")

    # 响应是按目录名（已 NFKC）精确匹配到的记录；这里只判字段，不再比对响应里的番号
    absent = [f for f in required if not is_filled(movie.field(f))]
    if absent:
        return DetailResult(d.name, d.path, "incomplete", missing_fields=absent)
    return DetailResult(d.name, d.path, "ok")


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
        # UTF-8 BOM + CRLF，与 movie_api 的 export 约定一致（Excel 双击不乱码）
        rows = [[
            r.fan_code,
            {"missing": "完全缺失", "incomplete": "字段缺失", "error": "查询失败", "ok": "正常"}[r.outcome],
            ",".join(r.missing_fields),
            r.path,
            r.detail,
        ] for r in exported]
        write_csv(out_path, ["fan_code", "status", "missing_fields", "netdisk_path", "detail"], rows)
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
    p.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT,
                   help=f"单请求超时秒数（默认 {DEFAULT_TIMEOUT:g}）")
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

    token = resolve_token(args, 1)

    api_base = args.api_base.strip().rstrip("/")
    if not api_base:
        die("--api-base 不能为空", 1)

    required = [f.strip() for f in (args.require or "").split(",") if f.strip()]
    bad = [f for f in required if f not in CHECKABLE_FIELDS]
    if bad:
        die(f"--require 含未知字段 {bad}；可用: {', '.join(CHECKABLE_FIELDS)}", 1)
    if args.mode == "batch":
        uncheckable = [f for f in required if f in BATCH_UNCHECKABLE_FIELDS]
        if uncheckable:
            die(f"批量模式查不了 {uncheckable}（列表接口不返回该字段）；"
                f"改用 --mode detail，或从 --require 里去掉", 1)

    attempts = max(1, args.max_attempts)
    print(f"网盘根目录: {args.media_root}")
    print(f"详情服务  : {api_base}")
    print(f"必填字段  : {', '.join(required) if required else '(仅检查记录是否存在)'}")
    if args.mode == "batch":
        print(f"查询方式  : 批量（每批 {max(1, args.batch_size)} 个番号）")
    else:
        print("查询方式  : 逐条详情接口")
    print(f"重试策略  : 最多 {attempts} 次尝试，退避上限 {args.max_wait:g}s")
    print("拉取网盘目录列表...")

    pan = NetdiskClient(token, timeout=args.timeout, attempts=attempts,
                        max_wait=args.max_wait, order=args.order, desc=args.desc)
    try:
        raw_dirs = pan.top_dirs(
            args.media_root, delay=args.delay,
            on_progress=lambda got: print(f"  已获取 {got} 个目录...", end="\r",
                                          file=sys.stderr, flush=True),
        )
    except BaiduApiError as e:
        die(f"网盘列表接口失败 errno={e.errno} ({e.hint}) {e.errmsg}", 1)
    except DirError as e:
        die(f"网盘列表接口不可用: {e}", 1)
    print(" " * 40, end="\r", file=sys.stderr)

    # 番号按 NFKC 归一化后再去重：同一个番号的全/半角写法只查一遂
    dirs: list[NetdiskDir] = []
    seen: set[str] = set()
    for d in raw_dirs:
        code = nfkc(d.name).strip()
        if not code or code in seen:
            continue
        seen.add(code)
        dirs.append(NetdiskDir(name=code, path=d.path, mtime=d.mtime))

    for sub in args.exclude:
        dirs = [d for d in dirs if sub not in d.name]
    if not dirs:
        print("没拿到任何目录，检查 media-root / access_token")
        return 1

    api = MovieApiClient(api_base, timeout=args.timeout, attempts=attempts,
                         max_wait=args.max_wait)

    # 批内用 fan_code 逗号分隔过滤，番号本身带逗号的没法这么查，单独走详情接口
    units: list[list[NetdiskDir]] = []
    odd: list[NetdiskDir] = []
    if args.mode == "batch":
        size = max(1, args.batch_size)
        quotable = [d for d in dirs if "," not in d.name]
        odd = [d for d in dirs if "," in d.name]
        units = [quotable[i:i + size] for i in range(0, len(quotable), size)]
    else:
        units = [[d] for d in dirs]
    checker = check_batch if args.mode == "batch" else check_detail_batch
    work = [(checker, u) for u in units] + [(check_detail_batch, [d]) for d in odd]

    print(f"共 {len(dirs)} 个影片目录 → {len(work)} 个请求任务，开始查询（并发 {args.workers}）...")
    results: list[DetailResult] = []
    done_codes = 0
    with ThreadPoolExecutor(max_workers=max(1, args.workers)) as pool:
        futures = {pool.submit(fn, api, unit, required): unit for fn, unit in work}
        for fut in as_completed(futures):
            unit = futures[fut]
            try:
                results.extend(fut.result())
            except Exception as e:  # 兜底：一个批次的异常绝不该让整轮巡检崩掉
                detail = f"{type(e).__name__}: {e}"
                results.extend(DetailResult(d.name, d.path, "error", detail=detail) for d in unit)
            done_codes += len(unit)
            print(f"  已查 {done_codes}/{len(dirs)}", end="\r", file=sys.stderr, flush=True)
    print(" " * 40, end="\r", file=sys.stderr)

    report(results, args.out, args.json_out, args.quiet, args.include_ok)
    return 0


if __name__ == "__main__":
    sys.exit(main())
