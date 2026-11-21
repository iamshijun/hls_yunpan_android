#!/usr/bin/env python3
"""影片详情服务（movie_api）访问层：列表分页、按番号批量过滤、单条详情、删除。

接口约定（来自 movies_service/movie_api）：
  - `GET  /api/movies?page=&size=[&fan_code=a,b,c]` -> `{data: [...], total: N}`
  - `GET  /api/movies/{fan_code}` -> 单条记录；404 = 没有这条记录
  - `DELETE /api/movies/{fan_code}` -> 200/204 成功；404 = 本来就不存在

这里只负责「把一次 HTTP 变成结构化结果并把错误分类」：翻页收齐、去重、判「收没收全」
都是各工具自己的判断（它们要据此决定是告警、拒绝删除还是只出报告），不属于这一层。
"""
from __future__ import annotations

import json
import urllib.parse
from dataclasses import dataclass
from typing import Any

from common import (DEFAULT_MAX_ATTEMPTS, DEFAULT_MAX_WAIT, DEFAULT_TIMEOUT, http_request,
                    shorten, with_retry)

#: 与 AppSettings.DEFAULT_MOVIE_API_BASE_URL 对齐
DEFAULT_API_BASE = "https://www.asitanokibou.xyz/movies"
#: 影片接口路径（列表 + 单条详情同前缀，删除是 DELETE /api/movies/{fan_code}）
MOVIES_PATH = "/api/movies"
#: 影片列表接口单页条数（服务端可能再截断，不够就靠 total 兜底）
DEFAULT_PAGE_SIZE = 100


class MovieApiError(Exception):
    """一次影片接口请求失败（传输/HTTP/JSON）。消息可直接给人看。"""


@dataclass(frozen=True)
class MovieRecord:
    """影片记录。`raw` 是服务端原样的 JSON 对象，字段校验按它来。"""

    fan_code: str
    raw: dict

    @classmethod
    def from_row(cls, row: dict) -> "MovieRecord | None":
        """从列表/详情行构造；没有 fan_code 的行没法与网盘对齐，丢掉。"""
        code = str(row.get("fan_code") or "").strip()
        return cls(fan_code=code, raw=row) if code else None

    @property
    def title(self) -> str:
        return str(self.raw.get("title") or "")

    @property
    def year(self) -> str:
        return str(self.raw.get("year") or "")

    def field(self, name: str) -> Any:
        """任意字段取值（字段是否算「有值」由调用方定义）。"""
        return self.raw.get(name)


class MovieApiClient:
    """一个实例 = 一个 base URL + 一套重试策略。"""

    def __init__(self, base: str, *, timeout: float = DEFAULT_TIMEOUT,
                 attempts: int = DEFAULT_MAX_ATTEMPTS, max_wait: float = DEFAULT_MAX_WAIT,
                 abort=None):
        self.base = (base or "").strip().rstrip("/")
        self.timeout = timeout
        self.attempts = max(1, attempts)
        self.max_wait = max_wait
        self.abort = abort

    # -- 读 --------------------------------------------------------------

    def page(self, page: int, size: int, fan_codes: list[str] | None = None,
             ) -> tuple[list[MovieRecord], int]:
        """取列表的一页，返回 (记录, 服务端 total)。[fan_codes] 走服务端精确过滤。"""
        params: dict[str, Any] = {"page": page, "size": max(1, size)}
        if fan_codes:
            params["fan_code"] = ",".join(fan_codes)
        payload = self._get_json(f"{MOVIES_PATH}?{urllib.parse.urlencode(params)}")
        rows = payload.get("data") or []
        records = [r for r in (MovieRecord.from_row(row) for row in rows if isinstance(row, dict)) if r]
        total = payload.get("total")
        return records, total if isinstance(total, int) and total > 0 else 0

    def detail(self, fan_code: str) -> MovieRecord | None:
        """单条详情；404 返回 None（「没有这条记录」是有效结论，不是错误）。"""
        path = f"{MOVIES_PATH}/{urllib.parse.quote(fan_code, safe='')}"
        payload = self._get_json(path, allow_404=True)
        if payload is None:
            return None
        # 详情接口直接返回记录对象；也兼容 {data: {...}} 的包装
        row = payload.get("data") if isinstance(payload.get("data"), dict) else payload
        if not isinstance(row, dict):
            raise MovieApiError(f"详情响应不是对象，而是 {type(row).__name__}")
        return MovieRecord(fan_code=fan_code, raw=row)

    # -- 写 --------------------------------------------------------------

    def delete(self, fan_code: str) -> str:
        """删除一条记录。返回 "deleted"；本来就 404 则返回 "absent"（同样算达成目标）。"""
        path = f"{MOVIES_PATH}/{urllib.parse.quote(fan_code, safe='')}"
        url = f"{self.base}{path}"
        status, body, err = with_retry(lambda: http_request("DELETE", url, self.timeout),
                                      self.attempts, self.max_wait, shorten(url), self.abort)
        if status == 0:
            raise MovieApiError(f"删除请求失败: {err}")
        if status in (200, 204):
            return "deleted"
        if status == 404:
            return "absent"
        raise MovieApiError(f"删除失败 HTTP {status}: {body[:200]}")

    # -- 内部 ------------------------------------------------------------

    def _get_json(self, path_and_query: str, *, allow_404: bool = False) -> dict | None:
        url = f"{self.base}{path_and_query}"
        status, body, err = with_retry(lambda: http_request("GET", url, self.timeout),
                                      self.attempts, self.max_wait, shorten(url), self.abort)
        if status == 0:
            raise MovieApiError(f"请求失败: {err}")
        if status == 404 and allow_404:
            return None
        if status != 200:
            raise MovieApiError(f"HTTP {status}: {body[:200]}")
        try:
            payload = json.loads(body or "{}")
        except json.JSONDecodeError as e:
            raise MovieApiError(f"响应不是 JSON: {e}; 原文: {body[:200]}")
        if not isinstance(payload, dict):
            raise MovieApiError(f"响应不是 JSON 对象，而是 {type(payload).__name__}")
        return payload
