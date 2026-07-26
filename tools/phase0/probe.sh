#!/usr/bin/env bash
# Phase 0 —— 官方 X API v2 探测（走 xurl，token 自动刷新）
#
# 前置：xurl 已认证（见 docs/PHASE0.md）。验证：xurl /2/users/me
#
# 用法：
#   ./probe.sh bookmarks      # $0.001/条
#   ./probe.sh likes          # $0.001/条
#   ./probe.sh following      # $0.005/条 —— 会真花钱，脚本先警告
#
# 回答四个问题：
#   * bookmarks 是否真卡在 ~800 条
#   * variants 里有没有可播 mp4
#   * ★ 各频道媒体密度 —— 唯一决定 Following 月成本是 $15 还是 $60 的数字
#   * ★ 成人内容的媒体是否被官方 API 剥掉 —— 排第一的产品风险
set -euo pipefail
cd "$(dirname "$0")"

MODE="${1:-bookmarks}"
OUT="out/$MODE"; mkdir -p "$OUT"

# media.fields 里的 url 是图片的唯一来源（视频只有 variants）—— 漏了它图片就是空的。
FIELDS='expansions=attachments.media_keys,author_id&media.fields=url,variants,preview_image_url,duration_ms,alt_text,type,width,height&tweet.fields=created_at,possibly_sensitive&user.fields=username,name,profile_image_url'

ME=$(xurl /2/users/me 2>&1 || true)
UID_=$(printf '%s' "$ME" | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])' 2>/dev/null) || {
  echo "!! xurl 未认证或 token 无效。先跑： xurl auth oauth2 --headless"; echo "$ME" | head -5; exit 1; }
echo "user id = $UID_"

case "$MODE" in
  bookmarks) PATH_="/2/users/$UID_/bookmarks";                       PRICE=0.001; DEF=20 ;;
  likes)     PATH_="/2/users/$UID_/liked_tweets";                    PRICE=0.001; DEF=10 ;;
  following) PATH_="/2/users/$UID_/timelines/reverse_chronological"; PRICE=0.005; DEF=3  ;;
  *) echo "用法: $0 [bookmarks|likes|following]"; exit 2 ;;
esac
MAX_PAGES="${MAX_PAGES:-$DEF}"

if [ "$MODE" = following ]; then
  # 用 python 直接格式化整句：printf %f 在逗号小数点的 locale 下会失败
  python3 -c "print(f'!! following 是 \$$PRICE/条，最多 $MAX_PAGES 页 ≈ \${$MAX_PAGES*100*$PRICE:.2f}。5 秒内 Ctrl-C 取消。')"
  sleep 5
fi

TOK=""; PAGE=0; TOTAL=0
while [ "$PAGE" -lt "$MAX_PAGES" ]; do
  PAGE=$((PAGE+1)); BODY="$OUT/page$PAGE.json"
  xurl "$PATH_?max_results=100&$FIELDS$TOK" > "$BODY" 2>/dev/null || true

  read -r N NEXT ERR <<<"$(python3 - "$BODY" <<'PY'
import json,sys
try: d=json.load(open(sys.argv[1]))
except Exception: print(0,"-","BADJSON"); raise SystemExit
# X 的错误可能是 RFC7807 顶层对象（402 credits depleted / 401 / 429），
# 也可能是 200 里带 errors 数组。两者都必须显式报出来 ——
# 绝不能把它们当成 "0 条" 混进统计，那正是 "200 零结果" 与真故障混淆的经典 bug。
if isinstance(d, dict) and d.get("status") and int(d["status"]) >= 400:
    print(0, "-", f"HTTP{d['status']}:{(d.get('title') or '').replace(' ','_')}"); raise SystemExit
print(len(d.get("data") or []), (d.get("meta") or {}).get("next_token") or "-",
      "ERRORS" if d.get("errors") else "-")
PY
)"
  case "$ERR" in
    HTTP*) echo "page $PAGE: !! $ERR —— 请求被拒，不是空结果"; cat "$BODY"; echo
           echo "   探测中止：先解决这个再继续，否则统计全是假的。"; exit 3 ;;
  esac
  TOTAL=$((TOTAL+N))
  # 显示 token 尾部而非头部：X 的 next_token 共享很长的公共前缀，
  # 截头部会让不同的 token 看起来一模一样（误判成"分页卡住了"）。
  echo "page $PAGE: items=$N total=$TOTAL next=…${NEXT: -10} $ERR"
  [ "$ERR" != "-" ] && { echo "   -> 看 $BODY"; head -c 300 "$BODY"; echo; }
  [ "$NEXT" = "-" ] && { echo "== next_token 消失 → 翻完了"; break; }
  TOK="&pagination_token=$NEXT"; sleep 1.2
done

echo
python3 - "$OUT" "$MODE" "$PRICE" <<'PY'
import json,glob,os,sys,collections
out,mode,price = sys.argv[1], sys.argv[2], float(sys.argv[3])
tweets=with_media=sensitive=sens_with_media=vids=playable=nodur=0
mtypes=collections.Counter(); nourl=[]
for f in sorted(glob.glob(os.path.join(out,"page*.json"))):
    try: d=json.load(open(f))
    except Exception: continue
    media={m.get("media_key"):m for m in ((d.get("includes") or {}).get("media") or [])}
    for t in (d.get("data") or []):
        tweets+=1
        got=[media[k] for k in ((t.get("attachments") or {}).get("media_keys") or []) if k in media]
        sens=bool(t.get("possibly_sensitive")); sensitive+=sens
        if got:
            with_media+=1; sens_with_media+=sens
        for m in got:
            mtypes[m.get("type")]+=1
            if m.get("type") in ("video","animated_gif"):
                vids+=1
                vs=m.get("variants") or []
                if any(v.get("content_type")=="video/mp4" and v.get("url") for v in vs): playable+=1
                else: nourl.append(m.get("media_key"))
                nodur += m.get("duration_ms") is None
dens=(with_media/tweets*100) if tweets else 0
print("="*60)
print(f"频道               : {mode}")
print(f"推文总数           : {tweets}")
print(f"带媒体             : {with_media}    -> ★ 媒体密度 {dens:.1f}%")
print(f"媒体类型           : {dict(mtypes)}")
print(f"视频/GIF           : {vids}，有可播 mp4: {playable}")
if nourl: print(f"  !! 无 mp4 变体    : {nourl[:5]}  <- 视频频道可能不成立")
print(f"duration_ms 缺失   : {nodur}   (>0 则 durationMs 必须可空)")
print("-"*60)
print(f"possibly_sensitive : {sensitive} 条，其中带媒体 {sens_with_media} 条")
if sensitive:
    sd=sens_with_media/sensitive*100
    print(f"  -> 敏感内容媒体保有率 {sd:.1f}%   (总体 {dens:.1f}%)")
    print("  !! 显著偏低——官方 API 可能在剥离成人内容媒体。停下核实。" if sd < dens*0.6
          else "  -> 未见系统性剥离，产品前提成立。")
else:
    print("  -> 本批无 possibly_sensitive 标记，换一批已知成人内容再测")
print("-"*60)
if tweets and dens:
    per=price/(dens/100)
    print(f"每条 ${price}  ->  ★ 每个有用媒体约 ${per:.4f}")
    if mode=="following":
        print(f"   一晚 40 个媒体 ≈ ${per*40:.2f}，每晚一场约 ${per*40*30:.0f}/月")
print("="*60)
print(f"原始 JSON 在 {out}/ —— Phase 1 的 fixture，别删")
PY
