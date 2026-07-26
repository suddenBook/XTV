# XTV — Phase 0 决策记录

> 目的：把"为什么这么建"钉死，免得三个月后自己都想不起来为什么没走某条路。
> 状态：**部分完成** —— 不需要登录的部分已查证；需要 token 的部分待填。

---

## 已确认（2026-07-26，来源均为官方 docs.x.com，无需登录）

### 数据通道：官方 X API v2，pay-per-use

各 endpoint 页面的 "View Pricing" 控件逐字给出：

| Endpoint | 单价 | Owned Read $0.001？ | 限额 |
|---|---|---|---|
| `GET /2/users/{id}/bookmarks` | $0.005 | **有** | 180/15min |
| `GET /2/users/{id}/liked_tweets` | $0.005 | **有** | 75/15min |
| `GET /2/users/{id}/timelines/reverse_chronological` | $0.005 | **没有** | 180/15min |

"Owned Read price: $0.001 per resource when accessing your own data as the app owner" 这行
只出现在前两个页面。changelog 条目 "X API pricing update: Owned Reads now $0.001"
（Apr 16 2026 发布，Apr 20 生效）列出的 owned-read endpoint 里也确实没有 `timelines/*`。

计费单位是**返回的每条推文**（控件标题 "Post: Read"，滑块单位 resources，0–50k）。

### 已排除的路径

| 路径 | 为什么不用 |
|---|---|
| **逆向 GraphQL**（auth_token + 签名） | 认证请求需要客户端计算 `x-client-transaction-id`，初始化要解析 X 的线上前端构建，2025-04 到 2026-07 至少坏了 7 次，且 X 同时跑两套资源方案灰度。queryId 每 2-4 周轮换。主账号有封禁风险。**只作为 `docs/research/` 里的备案。** |
| **"For You" 算法时间线** | 官方 API 里不存在。v2 只有三个时间线 endpoint，reverse_chronological 明写 "Excludes algorithmic ranking"。 |
| **Nitter / RSS 桥** | 上游 Nitter 2024 年已停摆；RSSHub 需要把同一份 session cookie 交给第三方服务器，且**没有 bookmarks 路由**——主频道拿不到。 |
| **关注列表扇出爬取** | 几百个账号轮询会撞限流，流量特征像自动化，抬高封号风险。 |
| **离线预下载库** | 不消除认证和限流，只是把问题搬进一个新的同步子系统，附赠存储配额、断点续传、删除策略、隐私暴露。 |

### 频道组合（已定）

Bookmarks + Likes + Following，全部走官方 API。Following 按条计费且浪费率高，
**已接受约 $60/月的估算**，但该数字取决于未实测的媒体密度，`SpendGuard` 硬封顶兜底。

### 认证（已定）

OAuth 2.0 PKCE，Native App 公开客户端。授权页跑在 WebView 里，用
`WebViewClient.shouldOverrideUrlLoading()` 在导航发生前拦截回调 URL 取 code —— **回调地址不需要真实服务器**。
官方文档确认 Native App 支持 PKCE，但完全未记载回调 URI 格式规则（只要求精确匹配），
且有多份开发者报告称 X 拒绝 localhost/127.0.0.1 —— 拦截法绕开整个问题。

### 目标硬件（adb 实测）

| | Google TV Streamer | 小米 MiTV-MOOR2 |
|---|---|---|
| API / RAM | 34 / 3.96 GB | 30 / 1.83 GB |
| 面板 | 物理 4K，**UI Override 1920x1080**，density 320 | 1080p，density 320 |
| WebView | Chromium 150.0.7871.124，多进程 | 150.0.7871.125 |
| touchscreen feature | **无** → WebView 内 D-pad 空间导航默认开启 | 无 |
| 时钟 | 与主机偏差 1s，auto_time 开 | — |
| Autofill | Google 服务已配置 | — |

每 app 堆上限 `heapgrowthlimit=384m`。**UI 在 1080p 渲染** → 图片统一取 `?name=large`（2048 上限），
不要 `4096x4096`（4K 位图 33MB/张，三层预加载会吃掉 1/4 堆）。

`minSdk = 30` 可覆盖两台；`setRecentsScreenshotEnabled` 需 API 33 版本守卫。

---

## Phase 0 实测结果（2026-07-26，账号 @Pistachios93325 / 1991855919240794112）

### 认证
- ✅ OAuth 2.0 PKCE 走通，用 `xurl`（Native App / public client，回调 `http://localhost:8080/callback`）
- ✅ **X 接受环回回调地址**——官方 MCP 文档就要求注册这个。此前搜到的"X 拒绝 localhost"不成立
- ✅ App permissions 设为 **Read**（最小权限，XTV 全程只读）
- ⚠️ **pay-per-use 是预付费的，无免费额度**：额度用尽返回 `HTTP 402 credits-depleted`（RFC7807 顶层对象，不是 200+errors）

### 三个频道实测

| 指标 | Bookmarks | Likes | **Following** |
|---|---|---|---|
| 总条数 | **0**（干净 200，`result_count:0`） | 9 | 296（3 页，未到底） |
| 媒体密度 | — | **100%** | **69.3% / 70.7%**（两次独立采样） |
| 媒体类型 | — | video 9 | video 170 / photo 56 |
| 视频有可播 mp4 | — | 9/9 | 170/170 |
| `duration_ms` 缺失 | — | 0 | 0 |
| **成人内容媒体保有率** | — | **100%**（5/5） | **83.8–88.7%**（>总体密度） |
| 每个有用媒体成本 | — | $0.001 | **$0.0071** |

### 🟢 排第一的风险已解除
`possibly_sensitive` 的推文**媒体完整返回，variants 有可播 mp4**，保有率甚至高于总体密度
（因为成人内容更倾向于带图/视频）。**官方 API 不剥离成人内容，产品前提成立。**

### JSON 结构（Phase 1 解析器直接用）
- `includes` 只有 `media` 和 `users` 两个 key
- media 字段：`media_key, type, width, height, url, variants, preview_image_url, duration_ms`
- **图片的 `url` 必须在 `media.fields` 里显式请求**，漏了它图片就是空的（踩过）。
  返回形如 `https://pbs.twimg.com/media/XXX.jpg`，原生 **width 2048**——正好是 1080p TV 需要的尺寸，不用再加 `?name=`
- 视频 `variants` = mp4 阶梯（2176k/832k/288k）+ **一个没有 `bit_rate` 键的 HLS m3u8**。
  选流用 `max(variants, key=lambda v: v.get("bit_rate", 0))` 才安全
- 一条推文挂几个媒体：1 个占 67，0 个占 28，**4 个占 2，2 个占 2**——多图推文真实存在但只占约 4%
- 本批样本里**没有 `animated_gif`**，GIF 分支暂无 fixture
- 分页正常：296 条零重复、页间零重叠。⚠️ `next_token` **共享很长的公共前缀**，
  按前缀截断显示会误判成"分页卡住"（踩过）

### ★ 决定性的量级发现
```
你的 Following 时间线： 99 条 / 32 分钟 = 约 186 条/小时 = 约 4,457 条/天
  全量拉取                    -> $22/天 = $669/月     ← 完全不可行
  凑一晚 40 个媒体（需 57 条）  -> $0.29/晚 = $8.6/月   ← 可行
  而这 57 条只覆盖最近 18 分钟的时间线
```

**时间线产出内容的速度比你消费快约 80 倍，你永远不可能"追平"。** 这有两个后果：

1. **成本远低于预期**：$8.6/月，不是接受的 $60。SpendGuard 上限设 **$20/月**足够宽裕。
2. **推翻了 plan 里"从最旧未看开始播"这个已批准的决定。** 那条建议假设未看队列是有限的、能放完的；
   在 80:1 的火管面前，最旧未看是几天前的内容，你永远推不到当下。
   **必须改成从最新往回取固定预算（约 60 条），播完即止。**
   "有限的一卷 + 明确的结束时刻"这个设计因此不只是体验选择，而是**成本上的必然**。

### 结论
- ✅ 🟢 成人内容媒体未被剥离
- ✅ 🟢 `variants` 有可播 mp4 URL
- ✅ Following 每媒体 $0.0071 → 每晚一场约 **$8.6/月** → SpendGuard 上限设 **$20/月**
- ⚠️ Bookmarks = 0、Likes = 9 → **这两个频道目前是空的**，Following 是唯一真实内容源（用户已确认以 Following 为主力）
- ✅ **进入 Phase 1**
