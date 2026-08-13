# CLAUDE.md

本文件为 AI 编码助手（及协作者）提供本项目「抖音收藏分享」Android 应用的设计上下文与约束。

## 项目概述

一个仅在 **Android 平台**使用的个人工具应用，完成三步流程：

1. **自动获取抖音 Cookie**：内嵌 WebView 登录抖音 → 检测到 `sessionid` 后自动提取登录态 Cookie 并本地保存。
2. **抓取所有收藏视频**（注意：是**收藏**，不是点赞/喜欢）：WebView 打开「我的收藏」页，注入 JS 捕获 `listcollection` 接口响应并自动滚动翻页，直到 `has_more = 0`。
3. **随机分享**：从收藏列表中随机选一条，复制 `https://www.douyin.com/video/{id}` 到剪贴板。

## 为什么采用 WebView 方案（关键决策，请勿轻易推翻）

### 问题背景

抖音网页版收藏接口为：

```
GET https://www.douyin.com/aweme/v1/web/aweme/listcollection/
```

该接口的请求必须携带 **`a_bogus` 签名参数**。签名由抖音页面内打包压缩的 JS 在运行时生成，未公开算法、每次请求动态变化。因此：

- ❌ **纯 HTTP 请求（OkHttp/Retrofit 直接调用）必然失败**，返回风控错误。
- ✅ **必须让抖音页面自己发出请求**，我们只负责"监听 + 翻页"。

### 为什么登录也走 WebView

- Android 端没有可供解密的浏览器本地 Cookie 库（PC 端 douyin-tools 的 `get_cookie_edge.py` 方案在 Android 上不可行）。
- **无法直接读取本机抖音 App 的登录态**：Android 沙箱隔离，外部应用读不到抖音 App 私有目录的 Cookie；抖音也未向个人开发者开放授权接口。
- 因此最稳的登录方式是：WebView 打开抖音网页 → 用户在页面上用**已登录的抖音 App 扫码登录**（免输账号密码）→ App 从 `CookieManager` 检测到 `sessionid` 后自动提取登录态保存。
- 登录页带加载状态、失败提示、刷新按钮，避免白屏时无反馈。

### 与 douyin-tools（PC 端 Python 工具）的关系

`douyin-tools/` 目录是 PC 端已跑通的参考实现，核心思路（Playwright 打开页面 → 监听 `listcollection` 响应 → 滚动翻页）与本 App 完全一致。**修改抓取逻辑时，应优先参考 douyin-tools 中的抓取细节**，尤其是：

- 收藏页 URL：`https://www.douyin.com/user/self?showTab=favorite_collection`
- 接口名：`listcollection`
- 停止条件：`has_more = false` 或连续多轮无新增

## 架构

```
app/src/main/java/com/example/myapplication/
├── MainActivity.kt         # 主界面：三卡片（登录/抓取/分享）+ 随机分享 + 导入 Cookie
├── LoginActivity.kt        # WebView 登录，检测 sessionid 并提取 Cookie
├── FetchActivity.kt        # WebView 打开收藏页，JS hook + 自动滚动翻页抓取
└── data/
    ├── FavoriteItem.kt     # 收藏视频模型（字段与 douyin-tools/favorites.json 兼容）
    └── SettingsStore.kt    # 本地持久化（SharedPreferences：Cookie + 收藏列表）
```

技术栈：Kotlin + ViewBinding + Material 3，单 Activity 多屏（`LoginActivity` / `FetchActivity` 为独立 Activity，通过 `ActivityResultLauncher` 联动）。

## 抓取流程时序（FetchActivity）

1. `injectCookies()`：将本地保存的 Cookie 注入 `CookieManager`。
2. `loadUrl(收藏页)`。
3. `onPageFinished` 后延迟 3 秒（等待首屏渲染）：
   - `injectHook()`：注入 JS hook `window.fetch` 与 `XMLHttpRequest`，捕获 URL 含 `listcollection` 的响应体，经 `DyBridge` 桥回传原生。
   - `seedFromDom()`：从已渲染 DOM 提取首屏 `video id`（兜底 hook 漏掉的第一页）。
   - `startScrolling()`：每 2.5 秒执行滚动 JS 触发翻页。
4. 停止条件：接口返回 `has_more = false`，或连续 8 轮无新增（有数据时）/ 3 轮无数据（空列表时）。
5. 保存到 `SettingsStore`。

## 约束与注意事项

- **Cookie 是敏感数据**：仅保存在本机应用私有 SharedPreferences，禁止上传/日志输出；`allowBackup=false` 防止备份泄露。
- 必须使用 **桌面 UA**（`Chrome/124 Windows`），保证收藏 tab 与接口存在于页面中。
- `addJavascriptInterface` 必须在 `loadUrl` 之前调用。
- 抖音页面结构/接口可能变化，`FetchActivity` 中的 JS（`HOOK_JS`、`DOM_SEED_JS`、`SCROLL_JS`）是失效时优先排查对象。
- 修改涉及收藏抓取的逻辑时，先对照 douyin-tools 的 Python 实现确认接口行为。

## 构建

```bash
./gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
