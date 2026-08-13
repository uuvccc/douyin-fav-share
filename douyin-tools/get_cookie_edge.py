# -*- coding: utf-8 -*-
"""
get_cookie_edge.py — 直接从 Edge 浏览器本地 Cookie 库解密提取抖音 Cookie（v10 / DPAPI 方案）

原理:
  1. 从 Edge 的 Local State 文件读取 DPAPI 加密的 AES 主密钥
  2. 用 Windows DPAPI (CryptUnprotectData) 解开主密钥
  3. 读取 Cookie SQLite 库, 对每个值做 AES-256-GCM 解密

要求:
  - Windows
  - 已经用 Edge 登录过抖音
  - 需要管理员权限时的提示: 某些机器上 DPAPI 需要同用户身份, 直接本用户运行即可

依赖:
  pycryptodome  (pip install pycryptodome)
"""

import os
import json
import sqlite3
import shutil
import tempfile
import base64
import sys
import ctypes
import ctypes.wintypes as wt
from pathlib import Path

# Windows 控制台默认 GBK, 统一用 UTF-8 输出避免乱码
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

from Crypto.Cipher import AES

# ---------------------------------------------------------------------------
# 配置
# ---------------------------------------------------------------------------
EDGE_ROOT = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "Edge" / "User Data"
LOCAL_STATE = EDGE_ROOT / "Local State"
COOKIE_DB = EDGE_ROOT / "Default" / "Network" / "Cookies"

# 抖音相关 Cookie
TARGET_COOKIES = [
    "sessionid", "sessionid_ss", "sid_guard", "sid_tt",
    "uid_tt", "uid_tt_ss", "ttwid", "odin_tt",
]

OUTPUT = Path(__file__).parent / "douyin_cookies.txt"


# ---------------------------------------------------------------------------
# DPAPI 解密
# ---------------------------------------------------------------------------
class DATA_BLOB(ctypes.Structure):
    _fields_ = [
        ("cbData", wt.DWORD),
        ("pbData", ctypes.POINTER(ctypes.c_char)),
    ]


def dpapi_unprotect(encrypted: bytes) -> bytes:
    """调用 Windows CryptUnprotectData 解密 DPAPI 加密数据。"""
    blob_in = DATA_BLOB(len(encrypted), ctypes.create_string_buffer(encrypted, len(encrypted)))
    blob_out = DATA_BLOB()

    if not ctypes.windll.crypt32.CryptUnprotectData(
        ctypes.byref(blob_in), None, None, None, None, 0, ctypes.byref(blob_out)
    ):
        raise ctypes.WinError(ctypes.get_last_error())

    decrypted = ctypes.string_at(blob_out.pbData, blob_out.cbData)
    ctypes.windll.kernel32.LocalFree(blob_out.pbData)
    return decrypted


def get_master_key() -> bytes:
    """从 Local State 读取并解密 AES 主密钥。"""
    if not LOCAL_STATE.exists():
        raise FileNotFoundError(f"找不到 Local State: {LOCAL_STATE}")
    with open(LOCAL_STATE, "r", encoding="utf-8") as f:
        state = json.load(f)
    encrypted_key = state["os_crypt"]["encrypted_key"]
    encrypted_key = base64.b64decode(encrypted_key)

    if encrypted_key.startswith(b"DPAPI"):
        encrypted_key = encrypted_key[5:]
        return dpapi_unprotect(encrypted_key)
    elif encrypted_key.startswith(b"v20"):
        raise RuntimeError(
            "此版本 Edge 启用了 App-Bound 加密 (v20)，本地解密需要额外权限，\n"
            "建议改用 get_cookie_playwright.py 方案。"
        )
    else:
        raise RuntimeError(f"未知的密钥格式: {encrypted_key[:5]!r}")


# ---------------------------------------------------------------------------
# Cookie 值解密
# ---------------------------------------------------------------------------
def decrypt_value(master_key: bytes, value: bytes) -> str:
    """AES-256-GCM 解密 Cookie 值。非 v10 前缀的原样返回。"""
    if not value:
        return ""
    if not value.startswith(b"v10"):
        # 明文 Cookie（未加密），直接解码
        try:
            return value.decode("utf-8", errors="replace")
        except Exception:
            return value.hex()

    # v10: nonce(12) + ciphertext + tag(16)
    payload = value[3:]
    nonce, ciphertext, tag = payload[:12], payload[12:-16], payload[-16:]
    cipher = AES.new(master_key, AES.MODE_GCM, nonce=nonce)
    try:
        return cipher.decrypt_and_verify(ciphertext, tag).decode("utf-8", errors="replace")
    except Exception as e:
        return f"[解密失败: {e}]"


def is_db_locked() -> bool:
    """检测 Cookie 库是否被 Edge 进程独占锁定。"""
    try:
        with open(COOKIE_DB, "rb"):
            return False
    except PermissionError:
        return True
    except OSError:
        return False


def try_open_cookies(master_key: bytes) -> dict:
    """尝试用只读/immutable 方式打开 Cookie 库（可绕过非独占锁）。"""
    results = {}
    try:
        uri = "file:" + COOKIE_DB.as_posix() + "?mode=ro&immutable=1"
        conn = sqlite3.connect(uri, uri=True)
        cur = conn.cursor()
        cur.execute(
            "SELECT host_key, name, encrypted_value "
            "FROM cookies WHERE host_key LIKE '%douyin.com'"
        )
        for host, name, enc_value in cur.fetchall():
            if name in TARGET_COOKIES:
                results.setdefault(name, decrypt_value(master_key, enc_value))
        conn.close()
        return results
    except sqlite3.OperationalError:
        # 复制到临时目录再读（此时文件应已被释放锁）
        tmp_db = Path(tempfile.gettempdir()) / "douyin_cookies_tmp.db"
        shutil.copy2(COOKIE_DB, tmp_db)
        try:
            conn = sqlite3.connect(str(tmp_db))
            cur = conn.cursor()
            cur.execute(
                "SELECT host_key, name, encrypted_value "
                "FROM cookies WHERE host_key LIKE '%douyin.com'"
            )
            for host, name, enc_value in cur.fetchall():
                if name in TARGET_COOKIES:
                    results.setdefault(name, decrypt_value(master_key, enc_value))
            conn.close()
        finally:
            tmp_db.unlink(missing_ok=True)
        return results


def close_edge():
    """优雅关闭 Edge（taskkill 不强制杀, 等待进程退出）。"""
    print("正在关闭 Edge ...")
    os.system("taskkill /IM msedge.exe /T /F >nul 2>&1")
    import time
    for _ in range(20):
        if not is_db_locked():
            return True
        time.sleep(0.5)
    return not is_db_locked()


def read_cookies(auto_close: bool = False) -> dict:
    """读取 Edge Cookie 库，返回抖音域名的 Cookie。"""
    if not COOKIE_DB.exists():
        raise FileNotFoundError(f"找不到 Edge Cookie 库: {COOKIE_DB}")

    master_key = get_master_key()

    if is_db_locked():
        print("[!] Edge 正在运行, Cookies 文件被锁定。")
        if auto_close:
            if close_edge():
                print("[✓] Edge 已关闭, 重新读取 ...")
            else:
                raise RuntimeError("无法释放 Cookies 文件锁, 请手动关闭 Edge 后重试。")
        else:
            raise RuntimeError(
                "Edge 正在运行, Cookies 文件被独占锁定, 无法读取。\n"
                "    请手动关闭 Edge 后重试, 或使用 --auto-close 自动关闭。"
            )

    return try_open_cookies(master_key)


def main():
    print("=" * 50)
    print("读取 Edge 抖音 Cookie ...")
    print(f"Cookie 库: {COOKIE_DB}")
    print("=" * 50)

    auto_close = "--auto-close" in sys.argv

    try:
        cookies = read_cookies(auto_close=auto_close)
    except RuntimeError as e:
        print(f"\n[!] {e}")
        print("\n    也可以手动关闭 Edge 后直接重跑: python get_cookie_edge.py")
        sys.exit(1)

    if not cookies:
        print("\n[!] 没有找到抖音的登录 Cookie。")
        print("    请确认: 1) 用 Edge 登录过抖音 (www.douyin.com)")
        print("            2) 登录后打开过抖音主页 (cookie 才会写入)")
        sys.exit(1)

    print(f"\n[✓] 提取到 {len(cookies)} 个 Cookie:")
    for name, val in cookies.items():
        shown = val if len(val) <= 40 else val[:20] + "..." + val[-10:]
        print(f"    {name} = {shown}")

    OUTPUT.write_text(json.dumps(cookies, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n[✓] 已保存到: {OUTPUT}")

    sessionid = cookies.get("sessionid")
    if sessionid:
        print("\n可以直接使用的 sessionid:")
        print(f"  sessionid={sessionid}")


if __name__ == "__main__":
    main()
