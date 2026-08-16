# 抖音个人数据工具

提取**你自己抖音账号**的登录 Cookie 与收藏列表的工具集。所有脚本在本地运行，不会上传任何数据。

## ⚠️ 安全须知

- `douyin_cookies.txt` 等同你的账号登录态，**不要**发送给任何第三方，也已被 `.gitignore` 排除。
- 用完建议在抖音里重新登录一次，让旧 Cookie 失效。

## 环境准备

```bash
python -m venv .venv
.venv\Scripts\pip install -r requirements.txt
.venv\Scripts\python -m playwright install chromium
```

## 工具一览

| 脚本 | 作用 | 依赖 |
|------|------|------|
| `douyin_cookie_qr.py` | **一键**自动登录捕获 Cookie 并生成二维码（App 扫码即导入） | playwright + qrcode + pillow |
| `get_cookie_edge.py` | 从 Edge 本地库解密提取 Cookie（全自动，需关闭 Edge） | pycryptodome |
| `get_cookie_playwright.py` | 弹窗扫码登录后提取 Cookie（最稳，不受加密影响） | playwright |
| `fetch_collections.py` | 抓取全部收藏视频链接 | playwright + 已生成的 Cookie |

### 0. 一键生成 Cookie 二维码（推荐，供 App 扫码导入）

只需**一条命令**，自动完成「打开抖音 → 手机扫码登录 → 捕获 Cookie → 生成二维码」全流程：

```bash
.venv\Scripts\python douyin_cookie_qr.py
```

脚本会自动衔接两次扫码，你只需跟着提示分别扫两次：

1. 浏览器窗口里的**抖音登录二维码** → 用已登录的抖音 App 扫码完成登录；
2. 捕获 Cookie 后自动生成**导入二维码**（保存为 `~/.douyin_cookie_qr.png` 并自动打开）→ 用 App 内「📷 扫码获取 Cookie」扫它即导入登录态。

> 注意：跑完脚本登录态也保存在 `~/.douyin_cookie.txt`，属于敏感数据，勿外传。

### 1. 获取 Cookie（备选）

**方式 A：Edge 直接解密**（如果你用 Edge 登录过抖音）

```bash
.venv\Scripts\python get_cookie_edge.py --auto-close   # 自动关闭 Edge
```

**方式 B：浏览器扫码**（不受版本加密影响，最稳）

```bash
.venv\Scripts\python get_cookie_playwright.py
```

两种方式都会生成 `douyin_cookies.txt`。

### 2. 抓取收藏

```bash
.venv\Scripts\python fetch_collections.py
```

> 注意：需要图形界面（headless 模式下页面懒加载不会翻页，会漏数据）。

生成：
- `favorites_links.txt` — 纯链接列表
- `favorites.json` — 完整详情（视频ID、描述、作者）

## 目录结构

```
douyin-tools/
├── douyin_cookie_qr.py         # 一键登录 → 捕获 Cookie → 生成二维码
├── test_douyin_cookie_qr.py    # douyin_cookie_qr.py 单元测试（unittest）
├── get_cookie_edge.py          # Cookie 获取：Edge 解密
├── get_cookie_playwright.py    # Cookie 获取：浏览器扫码
├── fetch_collections.py        # 收藏抓取
├── requirements.txt
├── .gitignore                  # 已排除 Cookie/Profile/结果
└── README.md
```

## 实现说明

- **收藏 ≠ 喜欢**。抖音网页版"收藏"（星标）走 `/aweme/v1/web/aweme/listcollection/` 接口；"喜欢"（红心）走 `/aweme/v1/web/aweme/favorite/`，两者不要混淆。
- `listcollection` 接口带 `a_bogus` 签名，纯 HTTP 请求会被拒绝，必须通过 Playwright 让页面自己发请求。
- 翻页字段是 `cursor`，通过 JS 滚动收藏列表容器触发加载，直到接口返回 `has_more=0`。
- 页面侧边栏的"收藏总数"包含视频/图文/合集/音乐/短剧等所有类型；`fetch_collections.py` 抓取的是视频+图文部分（`listcollection` 返回的范围）。

## 免责声明

本工具仅供获取**你自己账号**的数据，请遵守抖音用户协议与相关法律法规。
