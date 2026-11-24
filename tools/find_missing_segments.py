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

严重缺片（阈值 `--severe-threshold`，默认 `50`）：
  - 一个参数、两个模式，看值里有没有 `%`：
      - `30%` -> 比例模式：缺失的分片文件名数 / playlist 里的分片文件名总数，
        **都先按文件名去重**；绝对 URL 分片行两头都不算（照旧跳过并告警）；
      - `50`  -> 数量模式：去重后缺失的分片文件名数（不是 slug 数 —— slug 会把
        `seg_1` / `seg_01` 并成一个，数字比文件名少）；
  - 达到阈值（`>=`）就判「严重缺片」（outcome `severe`）：这不是补几个分片能解决的，
    而是下载本身出了问题，建议整片重新下载；
  - 两个模式**一次只生效一个**，不是「比例或数量任一命中」的 OR；
  - 100% 缺失也走同一档，但文案会多点一句「可能 playlist 与目录不是同一批」——
    一片都不在目录里，更常见的是重转码/改名/半截上传，而不是下载丢了；
  - `0` 与 `0%` 都是**关闭**（不出 severe）；数量模式必须是非负整数（`50.5` 报错），
    比例模式 0~100 可小数（`12.5%`）；越界或解析不了按参数错退出；
  - 比例恒按单部影片自身算：`--only` / `--exclude` / `--limit` / `--sort` 都不影响它。

输出：
  - 列：`fan_code,missing_slugs,verdict`（表头恒定三列，与是否给阈值无关）：
      - `missing_slugs`：可补片的分片号，用 `,` 拼接（CSV 自动加引号，表格软件里仍是
        一列）；`verdict` 为 `redownload` 的行此列**留空**——严重缺片不给补片清单；
      - `verdict`：`patch`（补这几个分片）/ `redownload`（建议整片重下）；
  - 只有「有缺片」或「严重缺片」的番号出行，无缺片的不出行 —— 这张表是待办清单；
  - 默认落 `report.csv`（UTF-8 BOM + CRLF，Excel 双击不乱码）；`--out -` 才打到 stdout
    （人看的文字一律走 stderr，重定向不会混进汇总行）；
  - 不覆盖已有报告：内容逐字节相同 -> 不写也不轮转；有变化 -> 先把旧文件归档成
    `report.<旧文件mtime>.csv` 再写新的（与 calc_media_size.py 的 `--report` 同一口径）；
    归档失败就中止、原文件原封不动 —— 清单是拿去补片/对账的凭据；
  - 中断/中止时写 `<stem>.partial-<ts>.csv`，不碰原路径。

检查顺序与数量（`--sort` / `--limit`）：
  - `--sort time-desc`（默认）按番号目录的 mtime 新->旧；另有 `time-asc` / `name-asc` /
    `name-desc`。mtime 缺失的番号恒排最后（没时间就不该混进「最新」里）；
  - 同一 mtime 的番号稳定按番号升序，两次跑出来的顺序不会飘；
  - 排序只决定「先检查谁」，**CSV 行序永远是番号升序**（这样两次结果可以直接 diff）；
  - `--limit N` 取排序后的前 N 部，`0`（默认）= 不限。它只影响本轮覆盖范围，
    不改变「缺片」的判定口径；摘要会回显实际生效的策略和覆盖率。

请求量：每部影片 = 目录列表（翻页）+ 1 次 filemetas + 1 次 playlist 下载，
**不下载任何分片内容**、不写网盘。全库几百部是分钟级。

退出码：0 = 巡检干净（无缺片、无失败）；1 = 有缺片（含严重缺片），或存在无法检查/
        失败的番号（覆盖不完整也不算通过）；2 = 参数错 / token 缺失或失效 / 媒体根目录
        读不了 / 报告写不了；130 = 被 Ctrl-C 中断。

用法：
  python3 tools/find_missing_segments.py --env-file ~/py_workspace/movies_service/hls_yunpan/.env

  # 常用变体
  python3 tools/find_missing_segments.py --env-file ... --out missing_segments.csv
  python3 tools/find_missing_segments.py --env-file ... --only abc-123
  python3 tools/find_missing_segments.py --env-file ... --only "abc-123,cdf-345" --workers 4
  python3 tools/find_missing_segments.py --env-file ... --only "mgmj-*"   # 通配符：整一批番号
  python3 tools/find_missing_segments.py --env-file ... --out - | tee missing.csv

  # 只看最近改动的一批（默认 time-desc，最新在前）
  python3 tools/find_missing_segments.py --env-file ... --limit 20
  python3 tools/find_missing_segments.py --env-file ... --sort name-asc --limit 50

  # 严重缺片的阈值：默认 50 = 缺 50 片以上就建议整片重下；带 % 才是比例
  python3 tools/find_missing_segments.py --env-file ... --severe-threshold 30%
  python3 tools/find_missing_segments.py --env-file ... --severe-threshold 0   # 关掉严重判定

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

from common import (DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_WAIT, DEFAULT_TIMEOUT, die, match_terms,
                    render_csv, resolve_token, split_terms, write_report)
from netdisk import (DEFAULT_MEDIA_ROOT, PLAYLIST_NAME, BaiduApiError, DirError, NetdiskClient,
                     NetdiskDir)

# --- 常量 --------------------------------------------------------------------

CSV_HEADER = ("fan_code", "missing_slugs", "verdict")
#: verdict 取值：补几个分片就完事 / 建议整片重下
VERDICT_PATCH = "patch"
VERDICT_REDOWNLOAD = "redownload"
#: 本项目分片命名：seg_<数字>.ts。大小写不敏感 —— 大写命名的也该归一成同一个号
SEGMENT_RE = re.compile(r"^seg_(\d+)$", re.IGNORECASE)
#: 并发处理的番号数（百度对列表接口有限流，别开太大）
DEFAULT_WORKERS = 4
#: 报告默认落盘路径（第二个参数是 `-` 时改走 stdout）
DEFAULT_OUT = "report.csv"

#: 检查顺序：显式带方向，一个参数说清「时间倒序」，不用再拿 --desc 拼
SORT_CHOICES = ("time-desc", "time-asc", "name-asc", "name-desc")
SORT_LABELS = {
    "time-desc": "按目录 mtime 新→旧",
    "time-asc": "按目录 mtime 旧→新",
    "name-asc": "按番号升序",
    "name-desc": "按番号降序",
}

#: 结局分类（汇总按它计数）
OUTCOMES = ("ok", "missing", "severe", "not_movie", "not_inspectable", "error")


@dataclass
class MovieResult:
    fan_code: str
    path: str
    #: ok / missing / severe / not_movie / not_inspectable / error
    outcome: str
    #: 已归一、已去重、已排序的缺失分片号；只有 missing 时才非空
    #: （severe 刻意不给补片清单 —— 那不是补片能解决的事）
    slugs: list[str] = field(default_factory=list)
    detail: str = ""
    warnings: list[str] = field(default_factory=list)
    #: 缺失分片文件名数 / playlist 列出的分片文件名数（都已去重），只用于报比例
    missing_count: int = 0
    total_count: int = 0


@dataclass(frozen=True)
class SevereThreshold:
    """`--severe-threshold` 的解析结果：mode 是 ratio/count/off，value 是阈值本身。"""

    mode: str = "off"
    value: float = 0.0

    def describe(self) -> str:
        if self.mode == "ratio":
            return f"缺失率 ≥ {self.value:g}% 即建议整片重下"
        if self.mode == "count":
            return f"缺失 ≥ {self.value:g} 片即建议整片重下"
        return "关闭"


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


def parse_threshold(text: str) -> SevereThreshold:
    """`30%` -> 比例 30；`50` -> 数量 50 片；`0` / `0%` -> 关闭。

    有没有 `%` 就是模式开关；数量模式只收非负整数（「片」没有半片），
    越界/解析不了按参数错退出。
    """
    raw = (text or "").strip()
    if not raw:
        die("--severe-threshold 不能为空（例: 50 / 30% / 0）")
    if raw.endswith("%"):
        body = raw[:-1].strip()
        try:
            value = float(body)
        except ValueError:
            die(f"--severe-threshold 的比例写法不是数字: {text!r}（例: 30% / 12.5%）")
        # nan 落不进 0~100 区间，一并在这里拦住
        if not 0 <= value <= 100:
            die(f"--severe-threshold 的比例必须在 0~100% 之间: {text!r}")
        return SevereThreshold("off", 0.0) if value == 0 else SevereThreshold("ratio", value)
    try:
        count = int(raw)
    except ValueError:
        die(f"--severe-threshold 不带 % 时必须是整数片数: {text!r}（例: 50 / 30%）")
    if count < 0:
        die(f"--severe-threshold 不能为负数: {text!r}（0 = 关闭）")
    return SevereThreshold("off", 0.0) if count == 0 else SevereThreshold("count", float(count))


def is_severe(lacking: int, total: int, threshold: SevereThreshold) -> bool:
    """达到阈值即严重（`>=`）。比例用交叉相乘比，避开「刚好等于阈值」时的浮点误差。"""
    if threshold.mode == "ratio":
        return total > 0 and lacking * 100 + 1e-9 >= threshold.value * total
    if threshold.mode == "count":
        return lacking >= threshold.value
    return False


def fmt_pct(percent: float) -> str:
    """33.333 -> '33.3'；100.0 -> '100'（只留一位小数，整数不拖尾巴）。"""
    return f"{percent:.1f}".rstrip("0").rstrip(".")


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

def process_movie(d: NetdiskDir, pan: NetdiskClient,
                  threshold: SevereThreshold = SevereThreshold()) -> MovieResult:
    """列目录 + 下载 playlist -> 对比出缺失分片号（或判为严重缺片）。

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
    unique_names = list(dict.fromkeys(names))
    missing = [n for n in unique_names if n not in present]
    if not missing:
        return MovieResult(d.name, d.path, "ok", warnings=warnings,
                           total_count=len(unique_names))

    lacking, total = len(missing), len(unique_names)
    if is_severe(lacking, total, threshold):
        # 缺到这个程度不是补片能解决的：不给 slug 清单，直接建议整片重下
        if threshold.mode == "ratio":
            criterion = f"{fmt_pct(lacking / total * 100)}% ≥ 阈值 {threshold.value:g}%"
        else:
            criterion = f"≥ 阈值 {threshold.value:g} 片"
        detail = f"缺 {lacking}/{total} 片（{criterion}），建议重新下载该片"
        if lacking == total:
            detail += "；一片都不在目录里，可能 playlist 与目录不是同一批"
        return MovieResult(d.name, d.path, "severe", detail=detail, warnings=warnings,
                           missing_count=lacking, total_count=total)

    slugs = sorted({slug_of(n) for n in missing}, key=slug_sort_key)
    detail = f"playlist 列出 {total} 片，有 {len(slugs)} 个分片号缺失"
    return MovieResult(d.name, d.path, "missing", slugs, detail, warnings,
                       missing_count=lacking, total_count=total)


def run_one(d: NetdiskDir, pan: NetdiskClient,
            threshold: SevereThreshold = SevereThreshold()) -> MovieResult:
    """把 process_movie 的异常收敛成 MovieResult；token 失效向上抛（终止整轮）。"""
    try:
        return process_movie(d, pan, threshold)
    except BaiduApiError as e:
        if e.is_token_invalid:
            raise
        return MovieResult(d.name, d.path, "error", detail=str(e))
    except DirError as e:
        return MovieResult(d.name, d.path, "error", detail=str(e))
    except Exception as e:  # 兜底：一个番号的意外异常不该让整轮崩掉
        return MovieResult(d.name, d.path, "error", detail=f"{type(e).__name__}: {e}")


# --- 输出 --------------------------------------------------------------------

def rows_of(results: list[MovieResult]) -> list[tuple[str, str, str]]:
    """只留要动手的番号行，按番号升序。

    可补片的行第二列是 slug 清单；严重缺片的行第二列**留空**（不给补片清单），
    由 verdict 列说「建议整片重下」。不能用线程完成顺序：两次跑出来的行序会不同。
    """
    return [(r.fan_code,
             ",".join(r.slugs) if r.outcome == "missing" else "",
             VERDICT_REDOWNLOAD if r.outcome == "severe" else VERDICT_PATCH)
            for r in sorted(results, key=lambda r: r.fan_code)
            if r.outcome in ("missing", "severe")]


def csv_bytes(results: list[MovieResult]) -> bytes:
    """报告字节（BOM + CRLF，见 common.render_csv）；落盘与 stdout 用的是同一份。"""
    return render_csv(CSV_HEADER, rows_of(results))


def write_partial(path: str | None, results: list[MovieResult]) -> None:
    """中断/中止时的半轮结果：写 `<stem>.partial-<ts>.csv`，绝不占用目标路径。"""
    data = csv_bytes(results)
    if not path:
        sys.stdout.write(data.decode("utf-8-sig"))
        sys.stdout.flush()
        return
    try:
        written, _ = write_report(path, data, partial=True)
    except OSError as e:
        print(f"写部分结果失败 {path}: {e}", file=sys.stderr)
        return
    info(f"已写出部分结果: {written}（{len(rows_of(results))} 行，不是完整一轮）")


def info(message: str) -> None:
    """人看的文字一律走 stderr：stdout 只留 CSV，管道/重定向才不会混进汇总行。"""
    print(message, file=sys.stderr, flush=True)


def print_details(results: list[MovieResult]) -> None:
    """把严重缺片/缺片/无法检查/失败逐条打到 stderr（stdout 留给 CSV，方便管道）。"""
    for r in sorted(results, key=lambda r: r.fan_code):
        if r.outcome == "severe":
            line = f"  [建议重下] {r.fan_code}: {r.detail}"
        elif r.outcome == "missing":
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
        f"严重缺片    : {counts['severe']} 部（建议整片重下，不出补片清单）",
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
    p.add_argument("--only", action="append", default=[], metavar="TERM[,TERM...]",
                   help="只检查名字命中该项的番号：不含通配符按子串，含 * ? [ 按 glob 整名匹配"
                        "（如 'mgmj-*'），均忽略大小写；逗号分隔可给多个，也可重复给 --only")
    p.add_argument("--exclude", action="append", default=[], metavar="TERM[,TERM...]",
                   help="跳过名字命中该项的番号（匹配规则同 --only）；逗号分隔可给多个，"
                        "也可重复给；如 --exclude 'trash,@eaDir'")
    p.add_argument("--out", default=DEFAULT_OUT, metavar="PATH",
                   help=f"报告落盘路径（默认 {DEFAULT_OUT}）；写 `-` 则打到 stdout。"
                        "内容与已有文件一致时不重写，有变化时先把旧文件归档成 "
                        "<stem>.<旧mtime>.csv")
    p.add_argument("--limit", type=int, default=0, metavar="N",
                   help="排序后只检查前 N 部（0 = 不限，默认 0）；只影响覆盖范围，不改变缺片判定")
    p.add_argument("--sort", default="time-desc", choices=SORT_CHOICES,
                   help="检查顺序（默认 time-desc = 目录 mtime 新→旧）；只影响先检查谁，"
                        "CSV 行序永远是番号升序")
    p.add_argument("--severe-threshold", default="50", metavar="N|PCT",
                   help="缺片达到阈值就判严重缺片：不出补片清单，直接建议整片重下。"
                        "带 %% 是比例（如 30%%），不带是分片数（如 50）；默认 50；"
                        "0 / 0%% = 关闭")
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
    threshold = parse_threshold(args.severe_threshold)
    out = (args.out or "").strip() or DEFAULT_OUT
    # `--out -` = stdout；否则落盘（默认 report.csv）
    to_stdout = out == "-"
    out_path = "" if to_stdout else out

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
    info(f"严重判定  : {threshold.describe()}")
    info(f"输出去向  : {out_path or 'stdout'}")
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
        dirs = [d for d in dirs if match_terms(d.name, only)]
    if exclude:
        dirs = [d for d in dirs if not match_terms(d.name, exclude)]
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
        futures = {pool.submit(run_one, d, pan, threshold): d for d in dirs}
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
                write_partial(out_path, results)
                return 2
    except KeyboardInterrupt:
        abort.set()
        print(" " * 40, end="\r", file=sys.stderr)
        info("已中断。")
        write_partial(out_path, results)
        return 130
    finally:
        pool.shutdown(wait=False, cancel_futures=True)
    print(" " * 40, end="\r", file=sys.stderr)

    rows = rows_of(results)
    data = csv_bytes(results)
    if to_stdout:
        sys.stdout.write(data.decode("utf-8-sig"))
        sys.stdout.flush()
    else:
        try:
            _, note = write_report(out_path, data)
        except OSError as e:
            die(f"写 CSV 失败 {out_path}: {e}")
        info(f"{note}（{len(rows)} 行要动手）")

    print_details(results)
    print_summary(results, len(dirs))
    if limited:
        info(f"注意: --limit {args.limit} 只覆盖了 {len(dirs)}/{available} 部，"
             f"其余番号本轮未检查（不代表它们不缺片）")

    counts = Counter(r.outcome for r in results)
    if counts["missing"] or counts["severe"]:
        return 1
    # 有「没法检」或「检失败」的番号 = 覆盖不完整，不算通过
    if counts["not_inspectable"] or counts["error"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
