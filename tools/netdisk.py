#!/usr/bin/env python3
"""网盘访问层：分页列举、直链换发、错误分类。

四个工具里关于「网盘」的事实只在这里出现一次：

  - 分页：list 接口 1000/页，必须翻全 —— 只翻一半得到的缺失清单是错的；
  - 错误：`errno != 0` 是接口级结论（token 失效 / 目录不存在），`HTTP 5xx` 是抖动
    但重试耗尽后同样是结论，两者分成 [BaiduApiError] 与 [DirError]，调用方各自决定
    「整轮终止」还是「记一条 error 继续」；
  - 下载：直链要用 `pan.baidu.com` 这个 UA（与 Android 端 `BaiduYunClient` 一致），
    列表/元数据用浏览器 UA；
  - UA / 常量 / 页码上限都是实现细节，调用方只需要 `list` / `top_dirs` / `download_text`。
"""
from __future__ import annotations

import json
import time
import urllib.parse
from dataclasses import dataclass
from typing import Callable

from common import (DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_WAIT, DEFAULT_TIMEOUT, http_request,
                    shorten, with_retry)

# --- 与 Android 工程 / hls_yunpan 对齐的常量 ---------------------------------

FILE_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
MEDIA_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"

#: 下载直链必须用这个 UA（与 BaiduYunClient.DOWNLOAD_UA 一致）
DOWNLOAD_UA = "pan.baidu.com"

#: 与 app/build.gradle.kts 的 BAIDU_APP_NAME 对齐
DEFAULT_MEDIA_ROOT = "/apps/asitanokibou/movies"
#: 与 HlsPaths.PLAYLIST_NAME 对齐：不含它的目录不算影片目录
PLAYLIST_NAME = "playlist.m3u8"

#: 百度 list 接口单页上限
LIST_PAGE_SIZE = 1000

#: access_token 失效/过期的 errno —— 再跑下去每个请求都会失败，应当终止整轮
TOKEN_ERRNOS = frozenset({-6, 111})

#: 常见 errno 的人话解释（只用于报错，不影响判定）
ERRNO_HINTS = {
    -6: "access_token 无效或过期",
    -9: "文件不存在",
    -12: "目录不存在",
}


# --- 错误 --------------------------------------------------------------------

class BaiduApiError(Exception):
    """百度接口返回 errno != 0。"""

    def __init__(self, errno: int, errmsg: str = ""):
        super().__init__(f"errno={errno} {errmsg}".strip())
        self.errno = errno
        self.errmsg = errmsg

    @property
    def is_token_invalid(self) -> bool:
        return self.errno in TOKEN_ERRNOS

    @property
    def hint(self) -> str:
        return ERRNO_HINTS.get(self.errno, "未知错误")


class DirError(Exception):
    """单个目录/单次下载失败（网络层或响应格式）：调用方记 error 后继续。"""


# --- 模型 --------------------------------------------------------------------

@dataclass(frozen=True)
class NetdiskDir:
    """网盘里的一个目录。`name` 是网盘原文，不做任何归一化。"""

    name: str
    path: str
    #: 目录的修改时间（网盘给的秒级时间戳）；None = 响应里没给，调用方自己决定怎么排
    mtime: int | None = None


def entry_mtime(item: dict) -> int | None:
    """取网盘返回的修改时间。字段名在不同响应里不一致，都没有则 None（不瞎判）。"""
    for key in ("server_mtime", "local_mtime", "mtime"):
        value = item.get(key)
        if isinstance(value, int) and not isinstance(value, bool) and value > 0:
            return value
    return None


# --- 客户端 ------------------------------------------------------------------

class NetdiskClient:
    """网盘读写的最小接口。一个实例 = 一份 token + 一套重试/排序策略。"""

    def __init__(self, token: str, *, timeout: float = DEFAULT_TIMEOUT,
                 attempts: int = DEFAULT_MAX_ATTEMPTS, max_wait: float = DEFAULT_MAX_WAIT,
                 order: str = "name", desc: int = 1, abort=None):
        self.token = token
        self.timeout = timeout
        self.attempts = max(1, attempts)
        self.max_wait = max_wait
        self.order = order
        self.desc = desc
        self.abort = abort

    # -- 读 --------------------------------------------------------------

    def list(self, path: str, *, delay: float = 0.0,
             on_progress: Callable[[int], None] | None = None) -> list[dict]:
        """分页拉全一个目录的内容（原始 list 项）。

        errno != 0 抛 [BaiduApiError]；其余失败抛 [DirError]。[on_progress] 每页收一次
        累计条数，[delay] 是翻页之间的间隔（百度限流时用）。
        """
        items: list[dict] = []
        start = 0
        while True:
            query = urllib.parse.urlencode({
                "method": "list",
                "dir": path,
                "order": self.order,
                "desc": self.desc,
                "start": start,
                "limit": LIST_PAGE_SIZE,
                "access_token": self.token,
            })
            url = f"{FILE_URL}?{query}"
            status, body, err = with_retry(lambda: http_request("GET", url, self.timeout),
                                          self.attempts, self.max_wait, shorten(url), self.abort)
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
                raise BaiduApiError(int(errno), payload.get("errmsg") or "")

            page = payload.get("list") or []
            if not page:
                break
            items.extend(page)
            if on_progress is not None:
                on_progress(len(items))
            if len(page) < LIST_PAGE_SIZE:
                break
            start += len(page)
            if delay:
                time.sleep(delay)
        return items

    def top_dirs(self, source_path: str, *, delay: float = 0.0,
                 on_progress: Callable[[int], None] | None = None) -> list[NetdiskDir]:
        """[source_path] 下的一级目录。名字按原文去重（同名只收一次）。"""
        items = self.list(source_path, delay=delay, on_progress=on_progress)
        dirs: list[NetdiskDir] = []
        seen: set[str] = set()
        for item in items:
            if item.get("isdir") != 1:
                continue
            name = item.get("server_filename") or ""
            if not name or name in seen:
                continue
            seen.add(name)
            dirs.append(NetdiskDir(name=name,
                                   path=item.get("path") or f"{source_path.rstrip('/')}/{name}",
                                   mtime=entry_mtime(item)))
        return dirs

    def download_text(self, fsid: int, label: str = "文件") -> str:
        """按 fsid 换直链并下载文本内容（playlist.m3u8 / meta.json）。

        网络层失败（filemetas/HTTP/超时）抛 [DirError]；errno != 0 抛 [BaiduApiError]。
        """
        query = urllib.parse.urlencode({
            "method": "filemetas",
            "access_token": self.token,
            "dlink": 1,
            "fsids": f"[{fsid}]",
        })
        url = f"{MEDIA_URL}?{query}"
        status, body, err = with_retry(lambda: http_request("GET", url, self.timeout),
                                      self.attempts, self.max_wait, "filemetas", self.abort)
        if status == 0:
            raise DirError(f"取 {label} 直链失败: {err}")
        if status != 200:
            raise DirError(f"取 {label} 直链 HTTP {status}: {body[:200]}")
        try:
            payload = json.loads(body)
        except json.JSONDecodeError as e:
            raise DirError(f"filemetas 返回不是 JSON: {e}; 原文: {body[:200]}")
        errno = payload.get("errno", -1)
        if errno != 0:
            raise BaiduApiError(int(errno), payload.get("errmsg") or "")
        entries = payload.get("list") or []
        dlink = (entries[0].get("dlink") if entries else None) or ""
        if not dlink:
            raise DirError(f"{label} 的下载直链为空")

        sep = "&" if "?" in dlink else "?"
        url = f"{dlink}{sep}{urllib.parse.urlencode({'access_token': self.token})}"
        status, text, err = with_retry(lambda: http_request("GET", url, self.timeout, DOWNLOAD_UA),
                                      self.attempts, self.max_wait, f"下载 {label}", self.abort)
        if status == 0:
            raise DirError(f"下载 {label} 失败: {err}")
        if status != 200:
            raise DirError(f"下载 {label} HTTP {status}: {text[:200]}")
        return text
