# -*- coding: utf-8 -*-
"""douyin_cookie_qr.py 的单元测试（Python 标准库 unittest）。

运行：
    douyin-tools/.venv/Scripts/python -m unittest test_douyin_cookie_qr
"""

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import douyin_cookie_qr as mod


class CookieStrTest(unittest.TestCase):
    def test_basic_join(self):
        cookies = [
            {"name": "sessionid", "value": "abc123"},
            {"name": "ttwid", "value": "xyz"},
        ]
        self.assertEqual(mod.cookie_str_from_list(cookies), "sessionid=abc123; ttwid=xyz")

    def test_skip_empty_value(self):
        cookies = [
            {"name": "sessionid", "value": "abc123"},
            {"name": "deleted", "value": ""},
            {"name": "gone", "value": None},
        ]
        self.assertEqual(mod.cookie_str_from_list(cookies), "sessionid=abc123")

    def test_empty_list(self):
        self.assertEqual(mod.cookie_str_from_list([]), "")

    def test_value_containing_semicolon_and_space(self):
        # value 内包含 '=' 时不影响拼接（按 name=value 直接拼）
        cookies = [{"name": "a", "value": "x=y; z"}]
        self.assertEqual(mod.cookie_str_from_list(cookies), "a=x=y; z")


class QrImageTest(unittest.TestCase):
    def test_image_size_and_layout(self):
        cookie = "sessionid=abc123; ttwid=xyz; odin_tt=o; uid_tt=u"
        img = mod.build_qr_image(cookie)
        # 顶部 80px 提示区 + 二维码本体（version>=8, box_size=12 → 至少 600px 宽）
        self.assertGreaterEqual(img.width, 500)
        self.assertGreaterEqual(img.height, 500)
        # 图片宽高比符合 "二维码正方形 + 顶部 80px" 结构
        self.assertEqual(img.height, img.width + 80)

    def test_long_cookie_auto_upgrade_version(self):
        # 长 Cookie（约 1500 字符）应自动升级二维码版本，仍能生成且不报错
        long_cookie = "sessionid=" + "x" * 1500
        img = mod.build_qr_image(long_cookie)
        self.assertGreaterEqual(img.width, 500)

    def test_save_image(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "qr.png")
            img = mod.build_qr_image("sessionid=abc")
            img.save(path)
            self.assertTrue(os.path.exists(path))
            self.assertGreater(os.path.getsize(path), 0)


if __name__ == "__main__":
    unittest.main()
