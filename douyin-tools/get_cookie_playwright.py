# -*- coding: utf-8 -*-
"""
get_cookie_playwright.py — 弹出浏览器窗口, 扫码登录抖音后自动提取 Cookie

特点:
  - 不受 Edge 文件锁 / App-Bound 加密影响, 最可靠的方案
  - 登录态保存在本地 profile 目录, 第二次运行自动登录, 无需重新扫码
  - 检测到登录成功后自动保存 Cookie

用法:
  python get_cookie_playwright.py

依赖:
  playwright          (pip install playwright)
  playwright install chromium   (首次运行需下载浏览器内核, 只执行一次)
"""

import sys
import json
import time
from pathlib import Path

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

from playwright.sync_api import sync_playwright

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
BASE = Path(__file__).parent
PROFILE_DIR = BASE / "profile_data"      # 本地登录态(保存后下次自动登录)
OUTPUT = BASE / "douyin_cookies.txt"

LOGIN_URL = "https://www.douyin.com/"

# 需要的抖音 Cookie
TARGET_COOKIES = [
    "sessionid", "sessionid_ss", "sid_guard", "sid_tt",
    "uid_tt", "uid_tt_ss", "ttwid", "odin_tt",
]

LOGIN_TIMEOUT_SECONDS = 300   # 最长等待扫码时间


def has_login(page) -> bool:
    """检测是否已登录(存在 sessionid 即视为已登录)。"""
    cookies = page.context.cookies("https://www.douyin.com")
    return any(c["name"] == "sessionid" and c["value"] for c in cookies)


def main():
    with sync_playwright() as p:
        # 启动浏览器(非 headless, 弹出窗口)
        context = p.chromium.launch_persistent_context(
            user_data_dir=str(PROFILE_DIR),
            headless=False,
            viewport={"width": 1280, "height": 800},
        )
        page = context.pages[0] if context.pages else context.new_page()

        page.goto(LOGIN_URL)
        print("=" * 50)
        print("已打开抖音。")
        print("若未登录: 请在浏览器窗口扫码/登录, 登录完成后本工具自动检测并保存 Cookie。")
        print("若已登录: 将自动读取现有 Cookie。")
        print("=" * 50)

        if has_login(page):
            print("[✓] 检测到已登录状态, 无需重新登录。")
        else:
            print("[...] 等待登录中 (最长 %d 秒) ..." % LOGIN_TIMEOUT_SECONDS)

        # 轮询等待登录
        deadline = time.time() + LOGIN_TIMEOUT_SECONDS
        while not has_login(page):
            if time.time() > deadline:
                print("[!] 超时, 未检测到登录。")
                context.close()
                sys.exit(1)
            time.sleep(2)

        # 提取目标 Cookie
        cookies = page.context.cookies("https://www.douyin.com")
        result = {}
        for c in cookies:
            if c["name"] in TARGET_COOKIES:
                result[c["name"]] = c["value"]

        if not result:
            print("[!] 未找到目标 Cookie。")
            context.close()
            sys.exit(1)

        print(f"\n[✓] 提取到 {len(result)} 个 Cookie:")
        for name, val in result.items():
            shown = val if len(val) <= 40 else val[:20] + "..." + val[-10:]
            print(f"    {name} = {shown}")

        OUTPUT.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n[✓] 已保存到: {OUTPUT}")

        sessionid = result.get("sessionid")
        if sessionid:
            print("\n可以直接使用的 sessionid:")
            print(f"  sessionid={sessionid}")

        context.close()
        print("\n浏览器已关闭, 完成。")


if __name__ == "__main__":
    main()
