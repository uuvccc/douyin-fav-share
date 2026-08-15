# -*- coding: utf-8 -*-
"""
douyin_cookie_qr.py — PC 端全自动获取抖音 Cookie 并生成二维码

用法：
    python douyin_cookie_qr.py

流程：
    1. Playwright 启动 Chromium（有头模式），移动端 UA 打开抖音首页
    2. 若 5 秒内未出现登录浮层，自动点击"登录"按钮
    3. 用户用手机抖音 App 扫码登录（或手机号验证码登录）
    4. 轮询 context.cookies() 检测 sessionid（120 秒超时）
    5. 登录成功后拼接 Cookie 字符串，保存 ~/.douyin_cookie.txt
    6. 生成带提示文字的二维码图片，保存 ~/.douyin_cookie_qr.png 并自动打开
    7. 用户按回车后关闭浏览器退出

依赖：
    pip install playwright "qrcode[pil]" pillow && playwright install chromium
"""

import asyncio
import os
import sys
import time

import qrcode
from PIL import Image, ImageDraw, ImageFont
# 注意：playwright 在 capture_cookie() 内延迟导入。
# 这样 cookie_str_from_list / build_qr_image 等纯函数及其单元测试
# 无需安装 playwright 即可运行（CI 轻量测试不装浏览器）。

COOKIE_FILE = os.path.expanduser("~/.douyin_cookie.txt")
QR_IMAGE_PATH = os.path.expanduser("~/.douyin_cookie_qr.png")

LOGIN_URL = "https://www.douyin.com/"
LOGIN_TIMEOUT = 120          # 登录等待超时（秒）
FLOATING_WAIT = 5            # 打开页面后等待登录浮层出现的时间（秒）
POLL_INTERVAL = 1            # 轮询 Cookie 的间隔（秒）

# 移动端 UA：抖音网页版在移动端 UA 下交互更友好（扫码登录入口更直接）
MOBILE_UA = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Mobile Safari/537.36"
)

# 关键登录态字段（用于日志提示）
KEY_FIELDS = ("sessionid", "odin_tt", "passport_csrf_token", "sid_guard", "uid_tt", "sid_tt")


def cookie_str_from_list(cookies: list) -> str:
    """把 Playwright cookie 列表拼接成 "name1=value1; name2=value2" 字符串。

    跳过 value 为空（如已删除/HttpOnly 占位）的项，避免产生 "a=" 垃圾段。
    纯函数，便于单元测试。
    """
    pairs = [f"{c['name']}={c['value']}" for c in cookies if c.get("value")]
    return "; ".join(pairs)


def _find_font(size: int):
    """按平台选择中文字体，找不到则回退默认字体。"""
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",            # macOS 苹方
        "C:/Windows/Fonts/msyh.ttc",                     # Windows 微软雅黑
        "C:/Windows/Fonts/simhei.ttf",                   # Windows 黑体
        "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",  # Linux 文泉驿
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def build_qr_image(cookie_str: str) -> Image.Image:
    """把 Cookie 字符串生成带顶部提示文字的二维码图片。

    - 二维码参数：version=8, 纠错 M, box_size=12, border=2（约 600px+）
    - 顶部留 80px 白色区域，文字 "扫描此二维码获取 Cookie"
    - cookie 过长时 qr.make(fit=True) 自动升级版本号
    纯函数，便于单元测试。
    """
    qr = qrcode.QRCode(
        version=8,
        error_correction=qrcode.constants.ERROR_CORRECT_M,
        box_size=12,
        border=2,
    )
    qr.add_data(cookie_str)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")

    final = Image.new("RGB", (img.width, img.height + 80), "white")
    final.paste(img, (0, 40))

    draw = ImageDraw.Draw(final)
    text = "扫描此二维码获取 Cookie"
    font = _find_font(24)
    bbox = draw.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0]
    draw.text(((img.width - text_w) // 2, 8), text, fill="black", font=font)
    return final


def show_qr(cookie_str: str) -> str:
    """保存二维码图片到 ~/.douyin_cookie_qr.png 并自动打开，返回图片路径。"""
    img = build_qr_image(cookie_str)
    img.save(QR_IMAGE_PATH)
    if sys.platform == "darwin":
        os.system(f"open {QR_IMAGE_PATH}")
    elif sys.platform == "win32":
        os.startfile(QR_IMAGE_PATH)  # noqa: S606 (Windows 打开图片的惯用方式)
    else:
        os.system(f"xdg-open {QR_IMAGE_PATH}")
    print(f"✅ 二维码已生成并打开：{QR_IMAGE_PATH}")
    return QR_IMAGE_PATH


async def capture_cookie() -> str | None:
    """核心流程：打开抖音 → 等待扫码登录 → 捕获 Cookie → 展示二维码。

    返回 Cookie 字符串；登录超时或异常返回 None。
    """
    # 延迟导入：让纯函数测试无需 playwright 即可运行
    from playwright.async_api import async_playwright

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=False, args=["--no-sandbox"])
        context = await browser.new_context(
            user_agent=MOBILE_UA,
            viewport={"width": 430, "height": 932},
            device_scale_factor=2,
        )
        page = await context.new_page()

        print("正在打开抖音登录页...")
        await page.goto(LOGIN_URL, wait_until="domcontentloaded")
        await page.wait_for_timeout(3000)

        print("=" * 50)
        print("请用手机抖音 App 扫描屏幕上的二维码登录")
        print(f"等待登录中...（超时 {LOGIN_TIMEOUT} 秒）")
        print("=" * 50)

        # 等待浮层出现；若 5 秒后仍无登录浮层，自动点击"登录"按钮
        await page.wait_for_timeout(FLOATING_WAIT * 1000)
        try:
            login_btn = await page.query_selector("text=登录")
            if login_btn:
                await login_btn.click()
                await page.wait_for_timeout(2000)
                print("已自动点击登录按钮")
        except Exception:
            pass

        # 轮询检测登录状态：出现 sessionid 即视为登录成功
        start = time.time()
        while True:
            if time.time() - start > LOGIN_TIMEOUT:
                print("登录超时，请重新运行脚本")
                await browser.close()
                return None

            cookies = await context.cookies()
            cookie_map = {c["name"]: c["value"] for c in cookies if c.get("value")}
            if cookie_map.get("sessionid"):
                cookie_str = cookie_str_from_list(cookies)
                print(f"\n✅ 登录成功！cookie 长度: {len(cookie_str)} 字符")
                for k in KEY_FIELDS:
                    if cookie_map.get(k):
                        print(f"    {k} = {cookie_map[k][:12]}…")
                with open(COOKIE_FILE, "w", encoding="utf-8") as f:
                    f.write(cookie_str)
                print(f"Cookie 已保存到 {COOKIE_FILE}")
                show_qr(cookie_str)
                input("\n按回车键关闭浏览器并退出...")
                await browser.close()
                return cookie_str

            await page.wait_for_timeout(POLL_INTERVAL * 1000)


def main():
    result = asyncio.run(capture_cookie())
    if not result:
        sys.exit(1)


if __name__ == "__main__":
    main()
