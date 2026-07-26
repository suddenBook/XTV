# Phase 0 — 开工前的证伪（现在只剩需要登录的部分）

已在 2026-07-26 查证完毕、**不需要你做**的部分见 `DECISION.md` 的"已确认"一节。
下面是**只有你能做**的（需要登录 X / 建开发者 app）。预计 45–60 分钟。

---

## 步骤 1 — 在 console.x.com 建 app（~20 分钟）

1. 登录 `https://console.x.com`（或 developer.x.com portal）。
2. 开通 **Pay-Per-Use**。**记录下来**：
   - [ ] 是否必须绑卡？有没有免费额度 / 迎新赠金？
   - [ ] **能不能设消费上限或用量告警？** ← 这个直接决定 app 里 `SpendGuard` 要做多严
3. 建一个 app，**App type 选 Native App**（公开客户端，走 PKCE）。
4. 设置 **Callback URI**。按优先级试，**逐字记录它拒绝了什么**：
   1. `xtv://auth`（自定义 scheme）
   2. `http://127.0.0.1:8080/cb`（环回；有报告称 X 拒绝）
   3. `https://<你的 github pages>/xtv/cb`（**这个一定能过**）

   > 三个都行的话选 1。只有 3 能过也**完全没问题**——授权流跑在我们自己的 WebView 里，
   > 用 `shouldOverrideUrlLoading()` 在导航前拦下 `?code=...`，那个地址不需要真的存在服务器。
5. 勾选 scopes：`tweet.read`、`users.read`、`bookmark.read`、`like.read`、`offline.access`
   （最后一个是拿 refresh token 用的，**别漏**，否则 token 过期要重新登录）。
   - [ ] 记录哪些 scope 不给授权

## 步骤 2 — 手动跑一次 OAuth 拿 token（~15 分钟）

一次性的，只为拿到 token 去跑探测脚本。app 里最终由 `OAuthFlow.kt` 做同样的事。

```bash
# 1) 生成 PKCE 参数
V=$(openssl rand -base64 60 | tr -d '=+/\n' | cut -c1-64)
C=$(printf '%s' "$V" | openssl dgst -sha256 -binary | openssl base64 | tr '+/' '-_' | tr -d '=')
echo "verifier=$V"; echo "challenge=$C"

# 2) 浏览器里打开（替换 CLIENT_ID 和 REDIRECT）
echo "https://x.com/i/oauth2/authorize?response_type=code&client_id=CLIENT_ID\
&redirect_uri=REDIRECT&scope=tweet.read%20users.read%20bookmark.read%20like.read%20offline.access\
&state=xtv&code_challenge=$C&code_challenge_method=S256"

# 3) 同意后浏览器会跳到 REDIRECT?code=... —— 从地址栏抄出 code（页面 404 无所谓）

# 4) 换 token
curl -sS -X POST https://api.x.com/2/oauth2/token \
  -H 'Content-Type: application/x-www-form-urlencoded' \
  -d "grant_type=authorization_code&client_id=CLIENT_ID&redirect_uri=REDIRECT&code=<CODE>&code_verifier=$V"
```

- [ ] 记录 `expires_in`（access token 寿命）和是否真的返回了 `refresh_token`

## 步骤 3 — 跑探测脚本（~10 分钟，脚本全自动）

```bash
export XTV_TOKEN='<access_token>'
cd tools/phase0
./apiv2_probe.sh bookmarks     # 便宜，先跑这个
./apiv2_probe.sh likes         # 便宜
./apiv2_probe.sh following     # ★ 会花约 $1.50，脚本会先提示并等 5 秒
```

脚本自动回答四个问题并打印结论：

| 问题 | 为什么要紧 |
|---|---|
| bookmarks 总条数 | 传闻卡 800。若属实，产品文案说"最近的收藏" |
| `variants` 有没有可播 mp4 | 没有的话视频频道不成立 |
| **各频道媒体密度** | ★ 唯一真正决定 Following 月成本是 $15 还是 $60 的数字 |
| **成人内容媒体保有率** | ★ 排第一的风险。显著低于总体密度 = 官方 API 在剥离成人内容 → **停下来重新讨论** |

产出的 `out/**/page*.json` 就是 Phase 1 的 fixture，**别删**。

---

## 出口

把结果填进 `DECISION.md`。两个红灯任意一个亮起就**不要进 Phase 1**：

- 🔴 成人内容的媒体被官方 API 剥掉 → 产品前提受损
- 🔴 `variants` 里没有可播 URL → 视频频道不成立

其余都是"记下来、调参数"，不是阻断。
