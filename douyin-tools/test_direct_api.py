# -*- coding: utf-8 -*-
"""
test_direct_api.py — 验证「真实 Cookie + 真实 a_bogus 签名」能否走裸 HTTP 直连抓收藏

背景
    listcollection 接口（/aweme/v1/web/aweme/listcollection/）必须带 a_bogus 签名
    （页面压缩混淆 JS 动态生成）+ msToken，纯 HTTP 直连通常被拒。本脚本用真实登录
    Cookie（~/.douyin_cookie.txt）分三步实测「签名 + cookie 走裸 HTTP 通不通」：

      步骤1  直连无签名 —— 预期被拒（403 / invalid request / WAF 拦截）
      步骤2  Playwright 带 cookie 打开收藏页，旁观捕获一条真实签名的
             listcollection 请求，再用 urllib 原样重放（两个变体：
             A = 浏览器实际发出的 cookie 头 / B = 文件 cookie 原始串），
             与浏览器内真实响应同屏对比
      步骤3  滚动触发第二页（新 max_cursor 的真实签名）再重放，
             验证「不同参数页的签名也能裸 HTTP 重放」

判定（不对称实验）
    - 重放 200 且 body 含 aweme_list → 决定性成功：直连可行
    - 浏览器内成功但重放失败 → 不具决定性（可能 TLS 指纹 / HTTP 版本被 WAF 拦、
      文件 cookie 不完整或过期、头缺失）——「成功有决定性、失败无决定性」，勿误判

安全
    绝不打印 cookie 值。URL 经 redact_url 掩码（a_bogus/msToken 只留前 40 字符），
    Header 只列名字。

用法
    douyin-tools/.venv/Scripts/python test_direct_api.py
    douyin-tools/.venv/Scripts/python test_direct_api.py --selftest   # 本地自测捕获闭环

依赖
    playwright（已装在 douyin-tools/.venv）；其余只用标准库。
    纯函数 / http_get 无浏览器依赖，可被 test_test_direct_api.py 单测。
"""

import asyncio
import gzip
import os
import re
import sys
import urllib.error
import urllib.request
import zlib

# Windows 控制台默认 GBK，强制 stdout/stderr 用 UTF-8（与 douyin_cookie_qr.py 一致）。
if sys.stdout and hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 复用 douyin_cookie_qr.py 的常量（单一来源）。该模块在函数内延迟导入 playwright，
# 这里 import 只加载纯函数与常量，测试无需浏览器。
import douyin_cookie_qr as dq

COOKIE_FILE = dq.COOKIE_FILE
DESKTOP_UA = dq.DESKTOP_UA

FAVORITES_URL = "https://www.douyin.com/user/self?showTab=favorite_collection"
LISTCOLLECTION_PATH = "/aweme/v1/web/aweme/listcollection/"
LISTCOLLECTION_BASE_URL = "https://www.douyin.com" + LISTCOLLECTION_PATH

# 步骤1 基础 web 参数（近似页面真实请求；本步骤目的只是确认「无签名被拒」，
# 参数不必与真实完全一致，被拒即达目的）。
STEP1_BASE_PARAMS = {
    "device_platform": "webapp",
    "aid": "6383",
    "channel": "channel_pc_web",
    "pc_client_type": "1",
    "version_code": "170400",
    "version_name": "17.4.0",
    "max_cursor": "0",
    "count": "20",
    "id_type": "4",
}

# 与 FetchActivity 的 SCROLL_JS 同思路：滚动所有可滚动容器到底部触发翻页。
SCROLL_ONCE_JS = """() => {
  const all = document.querySelectorAll('*');
  for (const el of all) {
    if (el.scrollHeight > el.clientHeight + 100) el.scrollTop = el.scrollHeight;
  }
  window.scrollTo(0, document.body.scrollHeight);
}"""

_REDACT_RE = re.compile(r"((?:^|&)(?:a_bogus|msToken)=)([^&]*)")


# ---------------------------------------------------------------------------
# 纯函数（无浏览器、无网络，可单测）
# ---------------------------------------------------------------------------

class CookieBundle:
    """解析后的 Cookie。raw 原样保留（重放用），pairs 供浏览器注入，names 只做对比。"""

    __slots__ = ("raw", "pairs", "names")

    def __init__(self, raw, pairs, names):
        self.raw = raw
        self.pairs = pairs
        self.names = names


def parse_cookie_file(path=COOKIE_FILE) -> CookieBundle:
    """读取 ~/.douyin_cookie.txt（"name=value; name=value"），解析为 CookieBundle。

    文件缺失给友好报错；空文件抛 ValueError。值内可含 '='（用 partition 只切第一个）。
    """
    if not os.path.exists(path):
        raise FileNotFoundError(
            f"缺少 Cookie 文件：{path}\n"
            "请先在 PC 端运行 douyin_cookie_qr.py 扫码登录生成。"
        )
    with open(path, encoding="utf-8") as f:
        raw = f.read().strip()
    if not raw:
        raise ValueError(f"Cookie 文件为空：{path}")
    pairs = []
    for seg in raw.split(";"):
        seg = seg.strip()
        if not seg:
            continue
        name, _, value = seg.partition("=")
        name, value = name.strip(), value.strip()
        if name and value:
            pairs.append((name, value))
    return CookieBundle(raw, pairs, frozenset(n for n, _ in pairs))


def redact_url(url: str, keep: int = 40) -> str:
    """把 url 里 a_bogus/msToken 的参数值截断为前 keep 字符（安全打印）。

    只在原始查询串上做正则替换，不动其它参数，避免 parse_qsl 对 '+' 等字符的
    二次编码造成失真。
    """
    def _m(m):
        v = m.group(2)
        if len(v) <= keep:
            return m.group(1) + v
        return m.group(1) + v[:keep] + "…"

    return _REDACT_RE.sub(_m, url)


def build_step1_url() -> str:
    """拼步骤1 的 URL：完整 web 参数，无 a_bogus / msToken。"""
    from urllib.parse import urlencode

    return LISTCOLLECTION_BASE_URL + "?" + urlencode(STEP1_BASE_PARAMS)


def cookie_names_diff(file_names, browser_names):
    """返回 (只在文件, 只在浏览器) 的名字集合差；只比名字不比值，绝不接触值。"""
    return set(file_names) - set(browser_names), set(browser_names) - set(file_names)


def gzip_maybe(body: bytes, content_encoding: str) -> bytes:
    """按响应 Content-Encoding 解压 gzip/deflate；无压缩则原样返回。"""
    ce = (content_encoding or "").lower()
    if "gzip" in ce:
        return gzip.decompress(body)
    if "deflate" in ce:
        try:
            return zlib.decompress(body)
        except zlib.error:
            return zlib.decompress(body, -zlib.MAX_WBITS)
    return body


def _cookie_names_from_header(cookie_header: str) -> frozenset:
    """从 Cookie 请求头解析出名字集合（不解析值）。"""
    return frozenset(
        seg.partition("=")[0].strip() for seg in cookie_header.split(";") if seg.strip()
    )


def _decode_body(b: bytes) -> str:
    for enc in ("utf-8-sig", "utf-8"):
        try:
            return b.decode(enc)
        except UnicodeDecodeError:
            continue
    return b.decode("utf-8", errors="replace")


# ---------------------------------------------------------------------------
# HTTP 重放
# ---------------------------------------------------------------------------

class HttpResult:
    __slots__ = ("status", "body", "headers")

    def __init__(self, status, body, headers):
        self.status = status
        self.body = body
        self.headers = headers


def http_get(url: str, headers: dict, cookie_header: str, timeout: float = 15.0) -> HttpResult:
    """urllib 直连 GET，尽量复刻浏览器请求头（重放捕获到的真实签名 URL）。

    - 显式设 UA（urllib 默认 'Python-urllib/3.x' 与签名绑定的 Chrome UA 不一致 → 必败）。
    - Accept-Encoding 强制 identity：a_bogus 只由参数+UA+时间戳算出，不依赖请求头，
      这样既不会破坏签名，也避免 gzip 乱码（仍留 gzip_maybe 兜底）。
    - build_opener(ProxyHandler({})) 强制直连，避免系统代理换出口 IP 触发 WAF。
    - HTTPError 也读 body：抖音非 2xx 通常还是错误 JSON，不读就丢了信息。
    """
    h = {k: v for k, v in headers.items()
         if not k.startswith(":")                       # HTTP/2 伪头(:authority等)urllib 不认
         and k.lower() not in ("host", "content-length", "content-type", "connection",
                               "accept-encoding", "cookie", "transfer-encoding",
                               "proxy-connection")}
    h["Accept-Encoding"] = "identity"
    h["Cookie"] = cookie_header
    h["User-Agent"] = h.get("User-Agent") or DESKTOP_UA

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    req = urllib.request.Request(url, headers=h)
    try:
        with opener.open(req, timeout=timeout) as r:
            body = r.read()
            return HttpResult(r.status,
                              _decode_body(gzip_maybe(body, r.headers.get("Content-Encoding", ""))),
                              {k.lower(): v for k, v in r.headers.items()})
    except urllib.error.HTTPError as e:
        body = e.read()
        return HttpResult(e.code,
                          _decode_body(gzip_maybe(body, e.headers.get("Content-Encoding", ""))),
                          {k.lower(): v for k, v in e.headers.items()})
    except urllib.error.URLError as e:
        return HttpResult(0, f"[URLError] {e.reason}", {})
    except Exception as e:  # socket.timeout 等
        return HttpResult(0, f"[Error] {type(e).__name__}: {e}", {})


# ---------------------------------------------------------------------------
# 浏览器旁观捕获
# ---------------------------------------------------------------------------

class _NoResponse(Exception):
    """抛给 expect_response 的 async with 块的信号：等不到响应，直接放弃。"""


async def _wait_for_value(value_coro, timeout_s: float):
    """等 expect_response 的 event.value；超时返回 None。

    event.value 是 async 属性，访问即得协程；用 shield 包一层，超时只取消外层，
    不取消底层的 Playwright Future，便于同一 event 再等一次（滚动兜底）。
    """
    try:
        return await asyncio.wait_for(asyncio.shield(value_coro), timeout=timeout_s)
    except (asyncio.TimeoutError, TimeoutError):
        return None


async def _main(bundle):
    print("=" * 56)
    print("步骤 1：直连无签名（预期被拒）")
    print("=" * 56)
    url1 = build_step1_url()
    print(f"  URL: {redact_url(url1)}")
    print(f"  Cookie: {len(bundle.pairs)} 项（值不显示）| UA: Chrome/125 Windows")
    r = http_get(url1, {"User-Agent": DESKTOP_UA, "Referer": FAVORITES_URL}, bundle.raw)
    print(f"  → HTTP {r.status}")
    print(f"  body[:300]: {r.body[:300]!r}")
    print()

    print("=" * 56)
    print("步骤 2/3：浏览器旁观捕获真签名 → 裸 HTTP 重放")
    print("=" * 56)
    print("  注意：会弹出 Chromium 窗口（登录用浏览器）")

    from playwright.async_api import async_playwright

    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=False, args=["--no-sandbox"])
        context = await browser.new_context(
            user_agent=DESKTOP_UA, viewport={"width": 1280, "height": 800})
        try:
            await context.add_cookies([
                {"name": n, "value": v, "domain": ".douyin.com", "path": "/"}
                for n, v in bundle.pairs
            ])
        except Exception as e:
            print(f"  [警告] 注入 cookie 失败：{e}")
        page = await context.new_page()
        print("  打开收藏页（旁观捕获，不拦截请求）…")

        # ---- 步骤 2：捕获第一页真实签名并重放 ----
        first = page.expect_response(
            lambda r: LISTCOLLECTION_PATH in r.url, timeout=30000)
        try:
            async with first as event:
                await page.goto(FAVORITES_URL, wait_until="domcontentloaded", timeout=60000)
                resp = await _wait_for_value(event.value, 20)
                if resp is None:
                    print("  首屏未自动请求，滚动一次再等…")
                    await page.evaluate(SCROLL_ONCE_JS)
                    resp = await _wait_for_value(event.value, 15)
                if resp is None:
                    raise _NoResponse()
        except _NoResponse:
            print("❌ 未捕获到 listcollection 请求，无法继续。")
            print("   可能原因：cookie 失效 / 登录态过期 / 页面未打开收藏 tab / 抖音风控验证码。")
            await browser.close()
            return

        body1 = await resp.text()
        req = resp.request
        hdrs1 = await req.all_headers()   # 注意：all_headers() 才含 cookie 头
        browser_cookie = hdrs1.get("cookie", "")
        print(f"  ✅ 捕获到真实请求：{redact_url(req.url)}")
        print(f"  [浏览器内] HTTP {resp.status} | body[:300]: {body1[:300]!r}")

        # 探测页面是否暴露可调用的 a_bogus 签名函数。若暴露，Android 端可用
        # 「页内直连」：WebView 内用自己的签名函数给任意 max_cursor 生成 a_bogus，
        # 再 fetch 拉下一页——不滚动、不重实现签名算法、也没有 TLS 指纹风险。
        try:
            signer = await page.evaluate(
                """() => {
                  const s = window.byted_acrawler;
                  const checks = {};
                  for (const k of ['byted_acrawler', '_webmsxyw', 'webmsxyw', '__acrawler', '_sign']) {
                    try { checks[k] = typeof window[k]; } catch (e) { checks[k] = 'err'; }
                  }
                  const matchedKeys = Object.keys(window).filter(k => /acrawler|webmsxyw/i.test(k)).slice(0, 20);
                  // 尝试真正调用签名函数：能否给任意 URL 生成 a_bogus（Android 页内直连核心机制）
                  let call = 'skipped';
                  try {
                    const testUrl = location.origin + '/aweme/v1/web/aweme/listcollection/?max_cursor=0&count=20&id_type=4';
                    let out = null;
                    if (typeof s === 'function') out = s({ url: testUrl });
                    else if (s && typeof s.sign === 'function') out = s.sign({ url: testUrl });
                    call = out == null
                      ? ('callable=' + (typeof s === 'function' || !!(s && s.sign)) + '，返回 null')
                      : (typeof out === 'string' ? out : JSON.stringify(out)).slice(0, 60);
                  } catch (e) { call = '调用抛错: ' + String((e && e.message) || e).slice(0, 80); }
                  return {
                    checks, matchedKeys,
                    signKeys: s && typeof s === 'object' ? Object.keys(s).slice(0, 8) : [],
                    call,
                  };
                }""")
            print(f"  [签名函数探测] {signer}")
        except Exception as e:
            print(f"  [签名函数探测] 失败：{e}")
        print()

        replay_headers = {k: v for k, v in hdrs1.items() if k.lower() != "cookie"}
        results = {}
        for label, cookie_hdr in (("A=浏览器cookie", browser_cookie), ("B=文件cookie", bundle.raw)):
            rr = http_get(req.url, replay_headers, cookie_hdr)
            results[label] = rr
            print(f"  重放 {label} → HTTP {rr.status}")
            print(f"      body[:300]: {rr.body[:300]!r}")

        only_file, only_browser = cookie_names_diff(
            bundle.names, _cookie_names_from_header(browser_cookie))
        if only_file or only_browser:
            print("  Cookie 名字差异（值不显示）：")
            if only_file:
                print(f"      只在文件里：{sorted(only_file)}")
            if only_browser:
                print(f"      只在浏览器里：{sorted(only_browser)}")

        b_variant = results.get("B=文件cookie")
        print()
        if b_variant and b_variant.status == 200 and "aweme_list" in b_variant.body:
            print("  ✅ 决定性成功：真实 a_bogus + 文件 cookie 走裸 HTTP 返回了收藏列表！")
            print("     → 直连接口可行，WebView 仅剩「本地生成新签名」这一个理由。")
        elif b_variant and b_variant.status == 0:
            print("  ⚠️ 重放未到达服务器（客户端报错）——这是脚本/环境问题，不是签名问题。")
            print(f"      {b_variant.body[:200]}")
        elif "aweme_list" in body1:
            print("  ⚠️ 不具决定性：浏览器内成功，但裸 HTTP 重放失败。")
            print("     → 可能原因：TLS 指纹 / HTTP 版本被 WAF 拦、文件 cookie 不完整或过期、头缺失。")
            print("     → 「成功有决定性、失败无决定性」——请勿据此判定直连不可行。")
        else:
            print("  ⚠️ 浏览器内也未拿到数据，签名重放无法判定。")
        print()

        # ---- 步骤 3：滚动触发第二页真实签名并重放 ----
        print("  滚动触发第二页（新 max_cursor 的真实签名）…")
        second = page.expect_response(
            lambda r: LISTCOLLECTION_PATH in r.url, timeout=30000)
        try:
            async with second as event:
                await page.evaluate(SCROLL_ONCE_JS)
                resp2 = await _wait_for_value(event.value, 15)
                if resp2 is None:
                    raise _NoResponse()
        except _NoResponse:
            resp2 = None
        if resp2 is None:
            print("  未捕获到第二页请求，跳过步骤 3。")
        else:
            body2 = await resp2.text()
            hdrs2 = await resp2.request.all_headers()
            print(f"  [浏览器内] HTTP {resp2.status} | {redact_url(resp2.request.url)}")
            print(f"      body[:200]: {body2[:200]!r}")
            r2 = http_get(resp2.request.url,
                          {k: v for k, v in hdrs2.items() if k.lower() != "cookie"},
                          bundle.raw)
            print(f"  重放 → HTTP {r2.status} | body[:200]: {r2.body[:200]!r}")
            if r2.status == 200 and "aweme_list" in r2.body:
                print("  ✅ 第二页（不同 max_cursor）也直连成功 → 任意翻页可行。")
            elif r2.status == 0:
                print(f"  ⚠️ 重放未到达服务器（客户端报错）：{r2.body[:150]}")
            elif "aweme_list" in body2:
                print("  ⚠️ 不具决定性：浏览器内成功但重放失败（TLS 指纹等）。")
        await browser.close()


# ---------------------------------------------------------------------------
# 本地自测：不起真实抖音，验证「先注册 expect_response → goto → 捕获」时序闭环
# ---------------------------------------------------------------------------

async def _selftest():
    from http.server import BaseHTTPRequestHandler, HTTPServer
    import threading

    html = (
        "<!doctype html><html><body><script>"
        "fetch('/aweme/v1/web/aweme/listcollection/?a_bogus=abc&msToken=xyz');"
        "</script></body></html>"
    )
    captured = {"api_hit": False}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if LISTCOLLECTION_PATH in self.path:
                captured["api_hit"] = True
                body = b'{"aweme_list":[{"aweme_id":"1"}],"has_more":false}'
            else:
                body = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        from playwright.async_api import async_playwright

        async with async_playwright() as p:
            browser = await p.chromium.launch(headless=True, args=["--no-sandbox"])
            context = await browser.new_context(user_agent=DESKTOP_UA)
            page = await context.new_page()
            promise = page.expect_response(
                lambda r: LISTCOLLECTION_PATH in r.url, timeout=10000)
            try:
                async with promise as event:
                    await page.goto(f"http://127.0.0.1:{port}/", wait_until="domcontentloaded")
                    resp = await _wait_for_value(event.value, 10)
                    if resp is None:
                        raise _NoResponse()
            except _NoResponse:
                resp = None
            if resp is None:
                print("❌ [selftest] 未捕获到请求")
            else:
                body = await resp.text()
                hdrs = await resp.request.all_headers()
                print(f"✅ [selftest] 捕获到：HTTP {resp.status} | body: {body!r}")
                print(f"   请求头含 cookie 键？{'cookie' in {k.lower() for k in hdrs}}")
            await browser.close()
    finally:
        server.shutdown()
    if captured["api_hit"]:
        print("✅ [selftest] 捕获闭环验证通过")
    else:
        print("❌ [selftest] server 未收到 listcollection 请求")


def main():
    if "--selftest" in sys.argv:
        asyncio.run(_selftest())
        return
    try:
        bundle = parse_cookie_file()
    except (FileNotFoundError, ValueError) as e:
        print(f"❌ {e}")
        sys.exit(1)
    print(f"Cookie 文件：{COOKIE_FILE}")
    print(f"  共 {len(bundle.pairs)} 项，值不显示。")
    print()
    asyncio.run(_main(bundle))


if __name__ == "__main__":
    main()
