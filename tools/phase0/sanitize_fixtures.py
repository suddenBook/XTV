#!/usr/bin/env python3
"""Turn raw Phase 0 API captures into committable test fixtures.

The raw captures under tools/phase0/out/ are real posts from a real Following timeline: author
handles, post text, and media URLs that resolve to actual (often adult) content. This repo is
public, so those never get committed. What the parser tests actually care about is *structure* —
media types, the variant ladder, multi-media posts, sensitive flags, missing fields, error shapes —
and all of that survives sanitisation.

Identifiers are rewritten through a stable counter so cross-references (a post's media_keys ->
includes.media, author_id -> includes.users) stay intact; a parser bug that breaks the join will
still be caught.

Usage:  ./sanitize_fixtures.py out/following/page1.json ../../app/src/test/resources/fixtures/following_page1.json
"""
import json
import re
import sys
from pathlib import Path

_ids: dict[str, str] = {}


def fake_id(real: str, prefix: str) -> str:
    """Stable per-run pseudonym. Same input -> same output, so joins keep working."""
    if real not in _ids:
        _ids[real] = f"{prefix}{len(_ids):04d}"
    return _ids[real]


def fake_text(real: str) -> str:
    """Preserve rough length and the presence of a trailing t.co link; drop everything else."""
    if not real:
        return ""
    had_link = "https://t.co/" in real
    words = max(1, len(real.split()))
    body = " ".join(f"word{i}" for i in range(min(words, 12)))
    return body + (" https://t.co/xxxxxxxxxx" if had_link else "")


def sanitize_media(m: dict) -> dict:
    out = dict(m)
    key = m.get("media_key", "")
    # media_key encodes type ("3_" photo, "13_" video, "7_" gif) — keep the prefix, it is structural.
    prefix = key.split("_", 1)[0] if "_" in key else "0"
    out["media_key"] = f"{prefix}_{fake_id(key, 'm')}"
    if "url" in out:
        out["url"] = f"https://pbs.twimg.invalid/media/{fake_id(key, 'ph')}.jpg"
    if "preview_image_url" in out:
        out["preview_image_url"] = f"https://pbs.twimg.invalid/thumb/{fake_id(key, 'pv')}.jpg"
    if "variants" in out:
        out["variants"] = [
            {
                **v,
                # Keep content_type and bit_rate verbatim: variant selection is what we test.
                # Note some variants (the HLS one) legitimately have no bit_rate key at all.
                "url": (
                    f"https://video.twimg.invalid/{fake_id(key, 'vd')}/"
                    f"{v.get('bit_rate', 'hls')}.{'m3u8' if 'mpegURL' in v.get('content_type', '') else 'mp4'}"
                ),
            }
            for v in out["variants"]
        ]
    if "alt_text" in out:
        out["alt_text"] = fake_text(out["alt_text"])
    return out


def sanitize_user(u: dict) -> dict:
    out = dict(u)
    uid = u.get("id", "")
    out["id"] = fake_id(uid, "u")
    out["username"] = f"creator{out['id'][1:]}"
    out["name"] = f"Creator {out['id'][1:]}"
    if "profile_image_url" in out:
        out["profile_image_url"] = f"https://pbs.twimg.invalid/profile/{out['id']}.jpg"
    return out


def sanitize_post(t: dict) -> dict:
    out = dict(t)
    out["id"] = fake_id(t.get("id", ""), "t")
    out["text"] = fake_text(t.get("text", ""))
    if "author_id" in out:
        out["author_id"] = fake_id(out["author_id"], "u")
    if "edit_history_tweet_ids" in out:
        out["edit_history_tweet_ids"] = [out["id"]]
    if "attachments" in out and "media_keys" in out["attachments"]:
        out["attachments"] = dict(out["attachments"])
        out["attachments"]["media_keys"] = [
            f"{k.split('_', 1)[0]}_{fake_id(k, 'm')}" for k in out["attachments"]["media_keys"]
        ]
    return out


def sanitize(doc: dict) -> dict:
    out: dict = {}
    if "data" in doc:
        out["data"] = [sanitize_post(t) for t in doc["data"]]
    if "includes" in doc:
        inc = {}
        if "media" in doc["includes"]:
            inc["media"] = [sanitize_media(m) for m in doc["includes"]["media"]]
        if "users" in doc["includes"]:
            inc["users"] = [sanitize_user(u) for u in doc["includes"]["users"]]
        out["includes"] = inc
    if "meta" in doc:
        meta = dict(doc["meta"])
        for k in ("newest_id", "oldest_id"):
            if k in meta:
                meta[k] = fake_id(meta[k], "t")
        if "next_token" in meta:
            meta["next_token"] = "NEXTTOKEN0000000001"
        out["meta"] = meta
    # RFC7807 error documents (402/401/429) pass through unchanged — no PII, and their exact shape
    # is the thing under test.
    for k in ("errors", "detail", "status", "title", "type"):
        if k in doc:
            out[k] = doc[k]
    return out


def main() -> None:
    src, dst = Path(sys.argv[1]), Path(sys.argv[2])
    doc = json.loads(src.read_text())
    clean = sanitize(doc)
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(clean, indent=2, ensure_ascii=False) + "\n")

    leaks = [
        p for p in re.findall(r"https?://[^\s\"]+", dst.read_text())
        if "invalid" not in p and "t.co/xxxx" not in p
    ]
    if leaks:
        raise SystemExit(f"REFUSING: real URLs survived sanitisation: {leaks[:3]}")
    print(f"{src} -> {dst}  ({len(clean.get('data', []))} posts, "
          f"{len(clean.get('includes', {}).get('media', []))} media)")


if __name__ == "__main__":
    main()
