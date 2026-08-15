# Douyin Fav Share · 抖音收藏分享

一个仅在 **Android 平台**使用的个人工具应用，三步完成抖音收藏的自动获取与随机分享：

1. **自动获取抖音 Cookie**：内嵌 WebView 登录抖音（可用已登录的抖音 App 扫码），检测到 `sessionid` 后自动提取登录态 Cookie 并本地保存，也支持从 PC 端工具导入。
2. **抓取所有收藏视频**（收藏，不是点赞/喜欢）：WebView 打开「我的收藏」页，注入 JS 捕获 `listcollection` 接口响应并自动滚动翻页，直到 `has_more = 0`。也支持**免登录**抓取他人**公开收藏**：只需粘贴对方主页链接 / 用户 ID / 抖音号。
3. **随机分享**：从收藏列表中随机选一条，复制 `https://www.douyin.com/video/{id}` 到剪贴板。

## 为什么用 WebView 方案

抖音网页版收藏接口 `https://www.douyin.com/aweme/v1/web/aweme/listcollection/` 必须携带 **`a_bogus` 签名**，该签名由页面内打包 JS 运行时生成、未公开算法。因此：

- ❌ 纯 HTTP 请求（OkHttp 直接调用）必然被风控拒绝
- ✅ 必须让抖音页面自己发出请求，App 只负责监听响应 + 滚动翻页

登录同理：Android 沙箱隔离导致外部应用无法读取抖音 App 的登录态，也没有个人开发者授权接口，最稳方案是 WebView 内扫码登录后从 `CookieManager` 读取登录态。

## 项目结构

```
app/                          # Android 应用（Kotlin + ViewBinding + Material 3）
├── MainActivity.kt           # 主界面：登录 / 抓取 / 随机分享
├── LoginActivity.kt          # WebView 登录，自动提取 Cookie
├── FetchActivity.kt          # WebView 抓取收藏（JS hook + 自动滚动翻页）
└── data/                     # 数据模型与本地存储

douyin-tools/                 # PC 端 Python 参考工具
├── get_cookie_edge.py        # 从 Edge 提取 Cookie（参考实现）
├── get_cookie_playwright.py  # Playwright 扫码登录提取 Cookie（参考实现）
└── fetch_collections.py      # 抓取收藏（参考实现，核心思路与 App 一致）
```

详细设计决策见 [`AGENTS.md`](AGENTS.md) / [`CLAUDE.md`](CLAUDE.md)。

## 构建

```bash
./gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

## 使用

1. 安装 APK，点「登录抖音」，用已登录的抖音 App 扫页面二维码完成登录
2. 点「开始抓取收藏」（抓取时保持页面可见）：
   - **抓取我的收藏**：需登录，可抓取自己的全部收藏
   - **抓取他人公开收藏**：免登录，粘贴对方主页链接 / 用户 ID / 抖音号即可；仅当对方开启了「公开收藏」时才能抓到
3. 随时点「🎲 随机选一条并复制链接」，分享到任意聊天

## 安全说明

Cookie 属敏感登录态，仅保存在本机应用私有存储（`SharedPreferences`），禁止上传与日志输出；已设置 `allowBackup=false` 防止备份泄露。请勿分享或导出本应用数据。

## 免责声明

本项目仅供个人学习与工具使用。抖音页面结构、接口签名可能随时变化，工具失效属正常现象，请勿用于商业用途或违反抖音服务条款的行为。
