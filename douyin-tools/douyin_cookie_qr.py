# -*- coding: utf-8 -*-
"""
douyin_cookie_qr.py — PC 端全自动获取抖音 Cookie 并生成二维码

用法：
    python douyin_cookie_qr.py

流程：
    1. Playwright 启动 Chromium（有头模式），桌面版 UA 打开抖音首页
    2. 自动点击右上角"登录"按钮，弹出二维码登录浮层
    3. 用户用手机抖音 App 扫码登录
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

# Windows 控制台默认 GBK 编码，打印 emoji/中文可能抛 UnicodeEncodeError；
# 强制 stdout/stderr 使用 UTF-8，保证各终端下脚本输出稳定。
if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

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

# 桌面版 UA：抖音桌面版右上角有固定的"登录"按钮，点击后必然弹出二维码浮层；
# 移动版首页没有自动弹出的登录浮层，且按钮选择器不稳定，故不使用移动 UA。
DESKTOP_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/125.0.0.0 Safari/537.36"
)

# 关键登录态字段（用于日志提示）
KEY_FIELDS = ("sessionid", "odin_tt", "passport_csrf_token", "sid_guard", "uid_tt", "sid_tt")

# 二维码只编码这些登录关键字段（全量 Cookie 仍完整写入 COOKIE_FILE）。
# 原因：抖音全量 Cookie 可达 5000+ 字符，超出 QR 码容量上限
# （version 40 / 纠错 M 约 2331 字节），而 App 端识别登录态仅需关键字段。
QR_FIELDS = (
    "sessionid", "sessionid_ss", "sid_guard", "sid_tt",
    "uid_tt", "uid_tt_ss", "ttwid", "odin_tt",
    "passport_csrf_token", "passport_csrf_token_time",
)


def cookie_str_from_list(cookies: list) -> str:
    """把 Playwright cookie 列表拼接成 "name1=value1; name2=value2" 字符串。

    跳过 value 为空（如已删除/HttpOnly 占位）的项，避免产生 "a=" 垃圾段。
    纯函数，便于单元测试。
    """
    pairs = [f"{c['name']}={c['value']}" for c in cookies if c.get("value")]
    return "; ".join(pairs)


def qr_cookie_str_from_list(cookies: list) -> str:
    """从 Playwright cookie 列表中提取二维码所需的登录关键字段。

    二维码容量有限（version 40 / 纠错 M 约 2331 字节），而抖音全量
    Cookie 可达 5000+ 字符，因此只编码 QR_FIELDS 中的关键字段，
    保证二维码可生成且 App 端足以识别登录态。
    纯函数，便于单元测试。
    """
    pairs = [
        f"{c['name']}={c['value']}"
        for c in cookies
        if c.get("value") and c["name"] in QR_FIELDS
    ]
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
    try:
        qr.make(fit=True)
    except ValueError as e:
        # QR 码版本上限 40，数据过长时 qrcode 库会抛 "Invalid version (was 41...)"
        raise ValueError(
            "Cookie 内容过长，无法生成二维码（超出 QR 码容量上限）。"
            "请使用 qr_cookie_str_from_list 只保留登录关键字段。"
        ) from e
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
    """保存二维码图片到 ~/.douyin_cookie_qr.png 并自动打开，返回图片路径。

    图片始终会保存成功；若系统未关联图片查看器导致无法自动打开，
    会回退用资源管理器打开所在目录，并提示手动打开图片。
    """
    img = build_qr_image(cookie_str)
    img.save(QR_IMAGE_PATH)
    opened = False
    if sys.platform == "darwin":
        if os.system(f"open {QR_IMAGE_PATH}") == 0:
            opened = True
    elif sys.platform == "win32":
        try:
            os.startfile(QR_IMAGE_PATH)  # noqa: S606 (Windows 打开图片的惯用方式)
            opened = True
        except OSError:
            # 系统未关联 .png 默认查看器时 os.startfile 抛 OSError，
            # 回退用资源管理器打开所在目录，用户可自行双击图片。
            try:
                os.startfile(os.path.dirname(QR_IMAGE_PATH))
            except OSError:
                pass
    else:
        if os.system(f"xdg-open {QR_IMAGE_PATH}") == 0:
            opened = True

    print(f"✅ 二维码已生成：{QR_IMAGE_PATH}")
    if opened:
        print("   已自动打开，请用 App 内「📷 扫码获取 Cookie」扫描。")
    else:
        print("   系统未能自动打开图片，请到上述路径手动打开后扫码。")
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
            user_agent=DESKTOP_UA,
            viewport={"width": 1280, "height": 800},
        )
        page = await context.new_page()

        print("正在打开抖音登录页...")
        await page.goto(LOGIN_URL, wait_until="domcontentloaded")
        await page.wait_for_timeout(3000)

        print("=" * 50)
        print("请用手机抖音 App 扫描屏幕上的二维码登录")
        print(f"等待登录中...（超时 {LOGIN_TIMEOUT} 秒）")
        print("=" * 50)

        # 等待浮层出现；若 5 秒后仍无登录浮层，自动点击"登录"按钮。
        # 桌面版右上角登录按钮文字固定，依次尝试多种选择器提高命中率。
        await page.wait_for_timeout(FLOATING_WAIT * 1000)
        clicked = False
        for selector in ('text=登录', 'button:has-text("登录")', 'a:has-text("登录")', '.login-button'):
            try:
                btn = await page.query_selector(selector)
                if btn and await btn.is_visible():
                    await btn.click()
                    await page.wait_for_timeout(2000)
                    clicked = True
                    break
            except Exception:
                continue
        if clicked:
            print("已自动点击登录按钮，等待扫码...")
        else:
            print("未找到登录按钮，若页面已显示二维码可直接扫码。")

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
                # 二维码容量有限，只编码登录关键字段（App 识别登录态足够）
                show_qr(qr_cookie_str_from_list(cookies))
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
