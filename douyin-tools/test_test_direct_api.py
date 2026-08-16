# -*- coding: utf-8 -*-
"""test_direct_api.py 的单元测试（标准库 unittest，无网络、无浏览器）。

运行：
    douyin-tools/.venv/Scripts/python -m unittest test_test_direct_api -v

覆盖：
    - parse_cookie_file：正常拼接 / 跳过空段 / 值含 '=' / 文件缺失 / 空文件
    - redact_url：a_bogus/msToken 掩码，其余参数不动
    - build_step1_url：含 web 参数、不含签名参数
    - cookie_names_diff：只比名字集合
    - gzip_maybe：gzip/deflate 解压、原样返回
    - http_get：本地 mock http server 验证 200/403/gzip、请求头保真、UA 默认值、拒连
"""

import gzip
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import test_direct_api as mod


# ---------------------------------------------------------------------------
# mock 本地 HTTP server（无网络、无浏览器）
# ---------------------------------------------------------------------------

def _start_mock_server(handler_factory):
    """handler_factory(path) -> (status, body, headers|None)。body 为 str 或 bytes。"""
    captured = {"path": None, "headers": None, "count": 0}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            captured["count"] += 1
            captured["path"] = self.path
            captured["headers"] = {k.lower(): v for k, v in self.headers.items()}
            status, body, hdrs = handler_factory(self.path)
            if isinstance(body, str):
                body = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            for k, v in (hdrs or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = HTTPServer(("127.0.0.1", 0), Handler)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, port, captured


def _write_cookie(tmpdir, text):
    p = os.path.join(tmpdir, "cookie.txt")
    with open(p, "w", encoding="utf-8") as f:
        f.write(text)
    return p


# ---------------------------------------------------------------------------
# parse_cookie_file
# ---------------------------------------------------------------------------

class ParseCookieFileTest(unittest.TestCase):
    def test_normal(self):
        with tempfile.TemporaryDirectory() as d:
            b = mod.parse_cookie_file(_write_cookie(d, "sessionid=abc; ttwid=xyz"))
        self.assertEqual(b.raw, "sessionid=abc; ttwid=xyz")
        self.assertEqual(b.pairs, [("sessionid", "abc"), ("ttwid", "xyz")])
        self.assertEqual(b.names, frozenset({"sessionid", "ttwid"}))

    def test_skip_empty_segments_and_value_contains_eq(self):
        with tempfile.TemporaryDirectory() as d:
            b = mod.parse_cookie_file(_write_cookie(d, "sessionid=abc; empty=; gone; odd=x=y;"))
        self.assertEqual(b.pairs, [("sessionid", "abc"), ("odd", "x=y")])

    def test_missing_file(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(FileNotFoundError):
                mod.parse_cookie_file(os.path.join(d, "missing.txt"))

    def test_empty_file(self):
        with tempfile.TemporaryDirectory() as d:
            with self.assertRaises(ValueError):
                mod.parse_cookie_file(_write_cookie(d, "   \n  "))


# ---------------------------------------------------------------------------
# redact_url
# ---------------------------------------------------------------------------

class RedactUrlTest(unittest.TestCase):
    def test_truncates_a_bogus_and_msToken_only(self):
        url = ("https://www.douyin.com/aweme/v1/web/aweme/listcollection/"
               "?max_cursor=0&count=20&a_bogus=" + "A" * 100 + "&msToken=" + "M" * 80)
        out = mod.redact_url(url)
        self.assertIn("a_bogus=" + "A" * 40 + "…", out)
        self.assertIn("msToken=" + "M" * 40 + "…", out)
        self.assertIn("max_cursor=0", out)
        self.assertIn("count=20", out)
        # 原始值不得残留
        self.assertNotIn("A" * 41, out)
        self.assertNotIn("M" * 41, out)

    def test_short_value_not_truncated(self):
        self.assertIn("a_bogus=short", mod.redact_url("https://a.com/x?a_bogus=short"))

    def test_url_without_query_unchanged(self):
        self.assertEqual(mod.redact_url("https://a.com/x"), "https://a.com/x")

    def test_url_without_signature_params_unchanged(self):
        url = "https://www.douyin.com/aweme/v1/web/aweme/listcollection/?max_cursor=1&count=20"
        self.assertEqual(mod.redact_url(url), url)


# ---------------------------------------------------------------------------
# build_step1_url
# ---------------------------------------------------------------------------

class BuildStep1UrlTest(unittest.TestCase):
    def test_web_params_no_signature(self):
        url = mod.build_step1_url()
        self.assertTrue(url.startswith(mod.LISTCOLLECTION_BASE_URL + "?"))
        self.assertIn("aid=6383", url)
        self.assertIn("max_cursor=0", url)
        self.assertIn("count=20", url)
        self.assertIn("id_type=4", url)
        self.assertNotIn("a_bogus", url)
        self.assertNotIn("msToken", url)


# ---------------------------------------------------------------------------
# cookie_names_diff
# ---------------------------------------------------------------------------

class CookieNamesDiffTest(unittest.TestCase):
    def test_diff(self):
        only_file, only_browser = mod.cookie_names_diff({"a", "b", "c"}, {"a", "c", "d"})
        self.assertEqual(only_file, {"b"})
        self.assertEqual(only_browser, {"d"})

    def test_equal(self):
        self.assertEqual(mod.cookie_names_diff({"a", "b"}, {"b", "a"}), (set(), set()))


# ---------------------------------------------------------------------------
# gzip_maybe
# ---------------------------------------------------------------------------

class GzipMaybeTest(unittest.TestCase):
    def test_gzip(self):
        payload = json.dumps({"aweme_list": []}).encode()
        self.assertEqual(mod.gzip_maybe(gzip.compress(payload), "gzip"), payload)

    def test_deflate(self):
        import zlib
        payload = b"deflate-payload"
        self.assertEqual(mod.gzip_maybe(zlib.compress(payload), "deflate"), payload)

    def test_plain(self):
        self.assertEqual(mod.gzip_maybe(b"hello", ""), b"hello")


# ---------------------------------------------------------------------------
# probe_short_link（分享短链探测）
# ---------------------------------------------------------------------------

class ProbeShortLinkTest(unittest.TestCase):
    def test_detects_share_info_short(self):
        body = json.dumps({"aweme_list": [
            {"aweme_id": "1", "share_info": {"share_url": "https://v.douyin.com/AbCdEf/"}}
        ], "has_more": False})
        r = mod.probe_short_link(body)
        self.assertTrue(r["has_short"])
        self.assertEqual(r["share_info_share_url"], "https://v.douyin.com/AbCdEf/")
        self.assertEqual(r["count"], 1)

    def test_detects_top_level_share_url(self):
        body = json.dumps({"aweme_list": [
            {"aweme_id": "1", "share_url": "https://v.douyin.com/XyZ123/"}
        ]})
        r = mod.probe_short_link(body)
        self.assertTrue(r["has_short"])
        self.assertEqual(r["share_url"], "https://v.douyin.com/XyZ123/")

    def test_long_link_is_not_short(self):
        body = json.dumps({"aweme_list": [
            {"aweme_id": "1", "share_info": {"share_url": "https://www.douyin.com/video/1"}}
        ]})
        r = mod.probe_short_link(body)
        self.assertFalse(r["has_short"])
        self.assertNotEqual(r["share_info_share_url"], "")

    def test_missing_fields(self):
        r = mod.probe_short_link('{"aweme_list":[{"aweme_id":"1","desc":"x"}]}')
        self.assertFalse(r["has_short"])
        self.assertEqual(r["share_info_share_url"], "")
        self.assertEqual(r["share_url"], "")

    def test_empty_list(self):
        r = mod.probe_short_link('{"aweme_list":[],"has_more":false}')
        self.assertEqual(r["count"], 0)
        self.assertFalse(r["has_short"])


# ---------------------------------------------------------------------------
# http_get（本地 mock server）
# ---------------------------------------------------------------------------

class HttpGetTest(unittest.TestCase):
    def test_200_and_header_fidelity(self):
        def handler(path):
            return 200, json.dumps({"ok": True}), None

        server, port, captured = _start_mock_server(handler)
        try:
            r = mod.http_get(
                f"http://127.0.0.1:{port}/api?x=1",
                {"User-Agent": mod.DESKTOP_UA, "Referer": "https://www.douyin.com/"},
                "sessionid=abc; ttwid=xyz",
            )
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(r.status, 200)
        self.assertIn("ok", r.body)
        self.assertEqual(captured["headers"]["user-agent"], mod.DESKTOP_UA)
        self.assertEqual(captured["headers"]["accept-encoding"], "identity")
        self.assertEqual(captured["headers"]["cookie"], "sessionid=abc; ttwid=xyz")
        self.assertEqual(captured["headers"]["referer"], "https://www.douyin.com/")

    def test_default_ua_when_headers_omit(self):
        def handler(path):
            return 200, "ok", None

        server, port, captured = _start_mock_server(handler)
        try:
            mod.http_get(f"http://127.0.0.1:{port}/", {}, "")
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(captured["headers"]["user-agent"], mod.DESKTOP_UA)

    def test_403_body_read(self):
        def handler(path):
            return 403, json.dumps({"error": "invalid request"}), None

        server, port, captured = _start_mock_server(handler)
        try:
            r = mod.http_get(f"http://127.0.0.1:{port}/", {}, "c=1")
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(r.status, 403)
        self.assertIn("invalid request", r.body)

    def test_gzip_response_decompressed(self):
        def handler(path):
            return 200, gzip.compress(json.dumps({"g": 1}).encode()), {"Content-Encoding": "gzip"}

        server, port, captured = _start_mock_server(handler)
        try:
            r = mod.http_get(f"http://127.0.0.1:{port}/", {}, "")
        finally:
            server.shutdown()
            server.server_close()
        self.assertIn("g", r.body)

    def test_pseudo_headers_stripped(self):
        # Playwright 的 all_headers() 含 HTTP/2 伪头(:authority 等)，urllib 不认，
        # http_get 必须剥离，否则抛 ValueError 根本发不出请求（真实重放曾因此失败）。
        def handler(path):
            return 200, "ok", None

        server, port, captured = _start_mock_server(handler)
        try:
            r = mod.http_get(
                f"http://127.0.0.1:{port}/api",
                {":authority": "www.douyin.com", ":method": "GET", ":path": "/api",
                 ":scheme": "https", "user-agent": mod.DESKTOP_UA},
                "c=1",
            )
        finally:
            server.shutdown()
            server.server_close()
        self.assertEqual(r.status, 200)
        hdrs = captured["headers"]
        self.assertNotIn(":authority", hdrs)
        self.assertNotIn(":method", hdrs)
        self.assertNotIn(":scheme", hdrs)
        self.assertEqual(hdrs.get("user-agent"), mod.DESKTOP_UA)

    def test_connection_refused(self):
        import socket
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
        s.close()
        r = mod.http_get(f"http://127.0.0.1:{port}/", {}, "")
        self.assertEqual(r.status, 0)
        self.assertIn("URLError", r.body)


if __name__ == "__main__":
    unittest.main()
