# -*- coding: utf-8 -*-
"""
fetch_collections.py — 抓取抖音收藏（星标）列表的全部视频链接

原理:
  - 抖音网页版收藏页真实接口是 /aweme/v1/web/aweme/listcollection/（带 a_bogus 签名, 纯 HTTP 会被拒）
  - 用 Playwright 打开收藏页, 监听该接口的响应, 通过 JS 滚动到底部触发翻页
  - 翻页字段是 cursor; 直到接口返回 has_more=0

要求:
  - 已通过 get_cookie_edge.py / get_cookie_playwright.py 生成 douyin_cookies.txt
  - 需要图形界面 (headless 模式下页面懒加载不会触发翻页, 会漏数据)

依赖:
  playwright  (pip install playwright; python -m playwright install chromium)

用法:
  python fetch_collections.py

输出:
  favorites.json       — 完整详情 (视频ID、描述、作者)
  favorites_links.txt  — 纯链接列表
"""

import json
import sys
import pathlib

sys.stdout.reconfigure(encoding="utf-8")

from playwright.sync_api import sync_playwright

BASE = pathlib.Path(__file__).parent
COOKIE_FILE = BASE / "douyin_cookies.txt"
FAVORITES_URL = "https://www.douyin.com/user/self?showTab=favorite_collection"

# 登录态保留目录(避免重复注入/临时文件堆积)
PROFILE_DIR = BASE / ".profile"
OUTPUT_JSON = BASE / "favorites.json"
OUTPUT_LINKS = BASE / "favorites_links.txt"


def load_cookies() -> dict:
    if not COOKIE_FILE.exists():
        raise FileNotFoundError(
            f"缺少 {COOKIE_FILE}\n请先用 get_cookie_edge.py 或 get_cookie_playwright.py 生成。"
        )
    return json.loads(COOKIE_FILE.read_text(encoding="utf-8"))


def main():
    cookies = load_cookies()

    all_items = []
    api_calls = 0

    with sync_playwright() as p:
        ctx = p.chromium.launch_persistent_context(
            user_data_dir=str(PROFILE_DIR),
            headless=False,  # 必须真实窗口, 否则懒加载不触发
            viewport={"width": 1440, "height": 900},
        )
        # 注入登录 Cookie
        for name, value in cookies.items():
            try:
                ctx.add_cookies([{
                    "name": name, "value": value,
                    "domain": ".douyin.com", "path": "/",
                }])
            except Exception:
                pass

        page = ctx.new_page()

        def on_response(resp):
            nonlocal api_calls
            if "listcollection" not in resp.url:
                return
            api_calls += 1
            try:
                body = resp.json()
            except Exception:
                return
            lst = body.get("aweme_list") or []
            has_more = body.get("has_more")
            print(f"  [api#{api_calls}] {len(lst)} 条 | has_more={has_more}")
            for a in lst:
                all_items.append({
                    "aweme_id": a.get("aweme_id"),
                    "desc": (a.get("desc") or "").strip(),
                    "url": f"https://www.douyin.com/video/{a.get('aweme_id')}",
                    "author": ((a.get("author") or {}).get("nickname")) or "",
                    "aweme_type": a.get("aweme_type"),
                })

        page.on("response", on_response)
        page.goto(FAVORITES_URL, wait_until="domcontentloaded", timeout=60000)
        page.wait_for_timeout(6000)

        # 滚动所有可滚动容器到底部, 触发 listcollection 翻页
        no_new = 0
        last_count = 0
        for _ in range(80):
            page.evaluate("""() => {
                const all = document.querySelectorAll('*');
                for (const el of all) {
                    if (el.scrollHeight > el.clientHeight + 100) {
                        el.scrollTop = el.scrollHeight;
                    }
                }
                window.scrollTo(0, document.body.scrollHeight);
            }""")
            page.wait_for_timeout(2500)
            if len(all_items) == last_count:
                no_new += 1
            else:
                no_new = 0
                last_count = len(all_items)
            if no_new >= 6:
                print(f"  连续 {no_new} 轮无新数据, 停止滚动")
                break

        page.wait_for_timeout(2000)
        ctx.close()

    # 去重
    seen, uniq = set(), []
    for item in all_items:
        if item["aweme_id"] not in seen:
            seen.add(item["aweme_id"])
            uniq.append(item)

    OUTPUT_JSON.write_text(json.dumps(uniq, ensure_ascii=False, indent=2), encoding="utf-8")
    OUTPUT_LINKS.write_text("\n".join(i["url"] for i in uniq) + "\n", encoding="utf-8")

    print(f"\n接口调用: {api_calls} 次")
    print(f"去重后收藏: {len(uniq)} 条")
    print(f"详情: {OUTPUT_JSON}")
    print(f"链接: {OUTPUT_LINKS}")


if __name__ == "__main__":
    main()
