# tools —— 网盘影片库的维护脚本

四个只依赖 Python 标准库的独立脚本，用来巡检网盘片库、影片详情服务（movie_api）与 HLS
播放列表之间的一致性。它们不参与 App 构建，也不会被 App 调用。

术语（`番号`、`分片`、`缺片`、`PCT`、`待办清单`、`清册` …）见 [GLOSSARY.md](./GLOSSARY.md)；
以后新写的文字以那里的词为准，不另造同义词。

## 一眼选工具

| 你要回答的问题                         | 脚本                               | 方向                  | 是否改数据            |
|---------------------------------|----------------------------------|---------------------|------------------|
| 网盘有目录，影片详情服务缺记录 / 缺字段？          | `find_missing_movie_details.py`  | 网盘 → API            | 只读               |
| API 有记录，网盘里没有对应番号目录？（孤儿记录）      | `find_missing_netdisk_movies.py` | API → 网盘            | 只读，`--delete` 才删 |
| 目录体积 / 时长对不对，`meta.json` 要不要写回？ | `calc_media_size.py`             | 读目录 → 写 `meta.json` | **写网盘**          |
| `playlist.m3u8` 里列出的分片，网盘上还在吗？  | `find_missing_segments.py`       | playlist → 目录       | 只读               |

## 共享模块

四个脚本只保留各自的口径与报告，重复的东西都在这三个模块里（`import` 即可，都在 `tools/` 下）：

| 模块             | interface                                                                                                                                                                                                                                                              | 谁在用                 |
|----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|
| `common.py`    | `die` / `read_env_file` / `resolve_token` / `http_request` / `with_retry` / `shorten` / `split_terms` / `match_terms` / `render_csv` / `write_csv` / `atomic_write` / `with_stamp` / `unique_path`，以及超时与重试默认值 | 全部 |
| `netdisk.py`   | `NetdiskClient(token, *, timeout, attempts, max_wait, order, desc, abort)` 的 `list(path)` / `top_dirs(path, *, delay, on_progress)` / `download_text(fsid, label)`；`NetdiskDir(name, path, mtime)`；`BaiduApiError` / `DirError`；`DEFAULT_MEDIA_ROOT` / `PLAYLIST_NAME` | 全部                  |
| `movie_api.py` | `MovieApiClient(base, *, timeout, attempts, max_wait, abort)` 的 `page(page, size, fan_codes)` / `detail(fan_code)` / `delete(fan_code)`；`MovieRecord(fan_code, raw)`；`MovieApiError`；`DEFAULT_API_BASE`                                                                | 两个 `find_missing_*` |

分工的边界：

- **共享层只装「事实」**：URL、UA、分页大小、errno 分类、退避策略、CSV 编码。
- **判定口径留在脚本里**：NFKC 归一、near-miss、`seg_` 分片号归一、`meta.json` schema、
  退出码、`--only/--limit/--sort/--report` 这些参数。
- `with_retry()` 返回 `(status, body, error)` 三元组且不抛异常，错误措辞随调用方走；
  这是既有行为，不要为了「更优雅」改成抛异常。
- 中止开关（`abort`）由脚本自己建 `threading.Event()` 后显式传进去，共享层不留全局状态。

## 共同约定

- **运行**：`python3 tools/<脚本>.py ...`，都支持 `-h`。四个脚本共用同目录下三个模块
  （见[共享模块](#共享模块)），整目录要一起用，不能只拷单个 `.py`。
- **access_token**：优先级 `--token` > `--env-file` 里的 `ACCESS_TOKEN` > `$BAIDU_ACCESS_TOKEN`。
  token 过期时只会被百度拒绝，脚本会明确报错退出 —— 重新获取后再跑，没有自动刷新。
- **网盘根目录**：默认 `/apps/asitanokibou/movies`，与 `app/build.gradle.kts` 的
  `BAIDU_APP_NAME` 对齐；换 App 名要同步改。
- **影片详情服务**：默认 `https://www.asitanokibou.xyz/movies`。
- **重试**：`--max-attempts`（默认 5）/ `--max-wait`（默认 7s）。超时、连接失败、
  429/5xx 按指数退避重试；其余 4xx 是确定结论，不重试。「重试耗尽」永远不会被当成「缺失」。
- **CSV**：一律 UTF-8 BOM（Excel 双击不乱码）+ CRLF。
- **报告重跑**：`calc_media_size.py --report` 与 `find_missing_segments.py --out` 同一口径
  —— 内容逐字节相同就不写也不轮转，有变化先把旧文件归档成 `<stem>.<旧mtime>.csv`；
  中断/中止一律另写 `<stem>.partial-<时间戳>.csv`，绝不占用原路径。
- **写操作只有两处**：`calc_media_size.py` 上传 `meta.json`、`find_missing_netdisk_movies.py`
  加 `--delete` 删 API 记录。其余脚本全是只读。
- **「缺失」一律精确同名**：不比大小写、不做 NFKC、不剥前后缀。宁可多报一条让人眼看，
  也不为了好看把两个不同的番号/分片并成一个。（与 `--only/--exclude` 的匹配规则无关；
  那边的通配符只用来**选**番号，不改变判定。）
- **`--only` / `--exclude` 的匹配**：条目里没有通配符时按**子串**（`abc-123` 命中
  `abc-123-4k`）；带 `*` `?` `[` 时按 **glob 整名**匹配（`mgmj-*` 命中 `mgmj-410`，
  `*-hd` 命中 `abc-123-hd`）。两者都**忽略大小写**，所以要「含有」就写 `*abc*`。
  逗号（半角或全角）分隔可给多个，参数可重复给。

---

## find_missing_movie_details.py

网盘里有影片目录，但详情服务里**没有这条记录**或**必填字段为空**的番号。

- **判定**：按批（默认 30 个/批）调 `GET /api/movies?page=1&size=30&fan_code=a,b,c` 比对。
  响应里没有该番号 → `完全缺失`；有记录但必填字段为空 → `字段缺失`；本批请求失败 →
  `查询失败`（结论未知，**不**算缺失，重跑即可）。
- **常用参数**：

| 参数                      | 说明                                                                                                                                              |
|-------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `--require`             | 必填字段，逗号分隔，取自 `title,year,cover,thumbnail,duration,description,casts,labels`（默认 `title`；留空则只查记录是否存在）。批量模式查不了 `description`，要查它得配 `--mode detail` |
| `--mode batch\          | detail`                                                                                                                                         | `batch`（默认）走列表接口，一部片一个请求的 1/30；`detail` 逐条打 `/api/movies/{fan_code}`，慢但能查任意字段 |
| `--batch-size`          | 每批番号数，默认 30                                                                                                                                     |
| `--only` / `--exclude`  | 按番号名筛选：无通配符=子串，带 `*` `?` `[`=glob 整名匹配（如 `mgmj-*`），均忽略大小写；逗号分隔、可重复 |
| `--out` / `--json-out`  | 导出 CSV / JSON（默认只导出「要动手补的」三类，加 `--include-ok` 连正常的一起带）                                                                                          |
| `--quiet`               | 只打汇总，不逐条列                                                                                                                                       |
| `--workers` / `--delay` | 并发数（默认 4）/ 网盘翻页间隔秒数                                                                                                                             |

- **示例**：

  ```bash
  python3 tools/find_missing_movie_details.py \
      --env-file ~/py_workspace/movies_service/hls_yunpan/.env --out missing.csv

  python3 tools/find_missing_movie_details.py --env-file ... --require title,cover,year
  python3 tools/find_missing_movie_details.py --env-file ... --mode detail --require description
  ```

- **CSV 列**：`fan_code, status(完全缺失/字段缺失/查询失败), missing_fields, netdisk_path, detail`
- **退出码**：`0` 巡检跑完（有缺失也是 0）；`1` 参数错 / token 缺失 / 没拿到任何目录。

---

## find_missing_netdisk_movies.py

上一条的反方向：详情服务里有记录，但网盘里**没有同名番号目录**的孤儿记录。
先出报告，确认后再 `--delete` 真删。

- **判定**：API 全量翻页收齐（`{data,total}`）→ 网盘一级目录全量 → 以 API 的 `fan_code`
  做完全匹配。目录存在但缺 `playlist.m3u8` / `meta.json` **不算**孤儿（前者是转码没完成，
  后者跑 `calc_media_size.py` 就行），不该删记录。
- **安全阀**：
  - 不加 `--delete` 只报告；删之前请先跑一遍看清单；
  - API 全量没收齐（翻页漏行）时**拒绝删除**，只出报告并 exit 2；
  - 网盘里有「只差首尾空白」的同名目录时不当缺失（极可能是真匹配），逐条打告警。
- **常用参数**：`--delete`（不可逆）、`--api-size`（列表每页条数，默认 100）、
  `--out`、`--quiet`、`--timeout`、`--max-attempts`、`--max-wait`、`--delay`。
- **示例**：

  ```bash
  # 第一步：只看清单
  python3 tools/find_missing_netdisk_movies.py \
      --env-file ~/py_workspace/movies_service/hls_yunpan/.env --out orphans.csv
  # 第二步：清单确认无误，再删
  python3 tools/find_missing_netdisk_movies.py --env-file ... --delete
  ```

- **CSV 列**：`fan_code, title, year, status(缺失/已删/删失败/跳过), detail`
- **退出码**：`0` 没有缺失；`1` 有缺失（已报告）；`2` API 列表拉不全 / 参数错；`3` 有删除失败。

---

## calc_media_size.py

遍历网盘影片目录，算出每部的体积与时长，把 `meta.json`（schema v2）写回影片目录。
网盘 list 接口不返回目录大小，所以体积是脚本自己累加第一层文件得出的。

- **口径**：
  - 体积只算目录**第一层**，排除 `meta.json` 自身与 `@eaDir` / `#recycle` / `Thumbs.db`
    这类垃圾文件；发现子目录只告警不递归（宁可数字被质疑，也不要静默算错）；
  - 时长 = `playlist.m3u8` 里所有 `#EXTINF` 之和；解析不出（不是 m3u8 / master playlist）
    时写 `duration_seconds: null` —— 那是**有效终态**，不会无限重算；
  - 已有合法 `meta.json` 且（要时长时）有 `duration_seconds` 键 → 跳过；`--force` 才重算；
    旧 `meta.json` 读不出来时**不覆盖**（网络抖一下不该踩掉好数据）。
- **常用参数**：

| 参数                                                        | 说明                           |
|-----------------------------------------------------------|------------------------------|
| `--only` / `--exclude`                                    | 按番号名筛选（子串或 `mgmj-*` 这类 glob），逗号分隔、可重复、忽略大小写 |
| `--dry-run`                                               | 只算不上传                        |
| `--force` / `--ignore-existing`                           | 忽略已有 `meta.json` 重算并覆盖       |
| `--no-duration`                                           | 不算时长（不下载 playlist，请求量减半）     |
| `--verify`                                                | 上传后回读比对                      |
| `--report` / `--report-only`                              | 随跑汇总成 CSV / 只出 CSV（不算体积、不上传） |
| `--workers`                                               | 并发目录数，默认 2（百度对列表接口有限流）       |
| `--meta-name` / `--upload-timeout` / `--order` / `--desc` | 少见，见 `-h`                    |

- **示例**：

  ```bash
  python3 tools/calc_media_size.py --env-file ~/py_workspace/movies_service/hls_yunpan/.env
  python3 tools/calc_media_size.py --env-file ... --only ABC-123 --dry-run   # 先拿一部试水
  python3 tools/calc_media_size.py --env-file ... --only "mgmj-*"             # 整批前缀（通配符）
  python3 tools/calc_media_size.py --env-file ... --report report.csv        # 顺带出清册
  python3 tools/calc_media_size.py --env-file ... --report-only report.csv    # 只出清册
  ```

- **报告 CSV 12 列**：`fan_code, status(ok/missing/incomplete/invalid/no_size/error),
  size_bytes, size_human, duration_seconds, duration_human, file_count, dir_count, stale,
  computed_at, path, detail`；行序按 `fan_code` 升序。
- **`--report` 不覆盖旧报告**：内容逐字节相同 → 不重写；有变化 → 旧的归档为
  `<stem>.<旧文件mtime>.csv` 再写新的；中断/中止 → 写 `<stem>.partial-<时间戳>.csv`，
  绝不占用目标路径。
- **退出码**：`0` 全部成功（含跳过）；`1` 跑完但有失败目录/告警，或报告里有非 ok 行；
  `2` 参数错 / token 缺失失效 / 媒体根目录读不到。Ctrl-C 为 130。

---

## find_missing_segments.py

巡检 `playlist.m3u8` 里列出的分片，在网盘目录里是否真的存在。**只读**：不下载分片内容、
不写网盘，每部影片约 3 个请求。

- **判定**：playlist 的非 `#` 非空行即分片名（去 `?query`、取 basename），与目录列表
  **精确同名**比对；目录列表按 1000/页翻页拉全，2000+ 片的片子不会漏。
- **分片号（slug）**：本项目分片统一 `seg_<数字>.ts`，slug = 去掉 `seg_` 与扩展名后的
  十进制数（`seg_001023.ts` → `1023`）；认不出的命名原样报文件名。去重后数字升序在前。
- **哪些不算缺片**：目录里没有 `playlist.m3u8`（不是影片目录）、master playlist、
  不是 m3u8、引用了绝对 URL 的分片 —— 都归为「无法检查」，不进 CSV，只在汇总里计数。
- **严重缺片（`--severe-threshold`，默认 `50`）**：一个参数、两个模式 —— 值带 `%` 是
  **比例**（缺失的分片文件名数 / playlist 的分片文件名总数，都去重；`30%`），不带 `%`
  是**分片数**（`50` = 缺 50 片）。达到阈值（`>=`）就判「严重缺片」：不是补几个分片
  能解决的，脚本不出补片清单，改判「建议整片重新下载」（`verdict=redownload`）。两个模式
  一次只生效一个，不是 OR；`0` / `0%` = 关闭。100% 缺失也走同一档，但会额外提示
  「可能 playlist 与目录不是同一批」（重转码/改名/半截上传更常见）。比例恒按单部影片
  自身算，与 `--only` / `--exclude` / `--limit` / `--sort` 互不影响。
- **常用参数**：

| 参数                     | 说明                                                                                             |
|------------------------|------------------------------------------------------------------------------------------------|
| `--limit N`            | 排序后只检查前 N 部（`0` = 不限，默认 0）；只影响覆盖范围                                                             |
| `--sort`               | 检查顺序：`time-desc`（默认，目录 mtime 新→旧）/ `time-asc` / `name-asc` / `name-desc`；只决定先检查谁，CSV 行序永远是番号升序 |
 | `--only` / `--exclude` | 按番号名筛选（子串或 `mgmj-*` 这类 glob），逗号分隔、可重复、忽略大小写 |
 | `--out`                | 报告路径，默认 `report.csv`；写 `-` 打车 stdout（人看的文字一律走 stderr）。内容与旧文件一致就不重写，变了先把旧的归档成 `report.<旧mtime>.csv` |
 | `--workers`            | 并发番号数，默认 4                                                                                     |
| `--severe-threshold`   | 缺片达到阈值即判「严重缺片 / 建议整片重下」：`50` = 50 片，`30%` = 30%；默认 `50`，`0`/`0%` 关闭            |

- **示例**：

  ```bash
  # 全库巡检（报告默认写 report.csv）
  python3 tools/find_missing_segments.py \
      --env-file ~/py_workspace/movies_service/hls_yunpan/.env
  # 只看最近改动的 20 部
  python3 tools/find_missing_segments.py --env-file ... --limit 20
  # 单部复核
  python3 tools/find_missing_segments.py --env-file ... --only abc-123
  # 整批复核（通配符）
  python3 tools/find_missing_segments.py --env-file ... --only "mgmj-*" --workers 4
  # 缺一大半的片子改判「建议重新下载」：30% 起（默认是 50 片）
  python3 tools/find_missing_segments.py --env-file ... --severe-threshold 30%
  # 把 CSV 交给管道 / 重定向
  python3 tools/find_missing_segments.py --env-file ... --out - | tee report.csv
  ```

- **CSV 列**：`fan_code, missing_slugs, verdict`（表头恒定三列，与是否给阈值无关）：
  `missing_slugs` 形如 `100,1023,2333`（csv 自动加引号，表格软件里仍是一列）；
  `verdict` 取 `patch`（补这几个分片）或 `redownload`（建议整片重下，此行 `missing_slugs`
  **留空**）。只有有缺片/严重缺片的番号出行。
- **退出码**：`0` 巡检干净；`1` 有缺片（含严重缺片），或存在无法检查/失败的番号
  （覆盖不完整也不算通过）；
  `2` 参数错 / token 缺失失效 / 媒体根目录读不了 / 报告写不了；`130` Ctrl-C（此时写
  `<stem>.partial-<时间戳>.csv`）。

---

## 一次完整巡检的顺序

```bash
ENV=~/py_workspace/movies_service/hls_yunpan/.env

# 1. 体积/时长清册（顺带把 meta.json 补齐）
python3 tools/calc_media_size.py --env-file $ENV --report report.csv

# 2. 网盘有、API 缺 → 去 movie_admin 补记录
python3 tools/find_missing_movie_details.py --env-file $ENV --out missing_in_api.csv

# 3. API 有、网盘无 → 先看清单，确认后再 --delete
python3 tools/find_missing_netdisk_movies.py --env-file $ENV --out orphans.csv

# 4. 分片是否齐 → 缺片号拿去重新转码/补片
python3 tools/find_missing_segments.py --env-file $ENV --limit 50
```

四个脚本都可以反复跑：只读的没有副作用，`calc_media_size.py` 自带断点续跑
（已有合法 `meta.json` 就跳过），`find_missing_segments.py` 的报告默认写 `report.csv`、
内容没变不重写、变了先把旧的归档（与 `--report` 同一口径）。
