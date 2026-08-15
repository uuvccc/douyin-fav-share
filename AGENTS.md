# AGENTS.md

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
├── MainActivity.kt         # 主界面：三卡片（登录/抓取/分享）+ 随机分享 + 导入 Cookie + 自动更新 + 抓取方式选择
├── LoginActivity.kt        # WebView 登录，检测 sessionid 并提取 Cookie
├── FetchActivity.kt        # WebView 抓取收藏（两种模式：self 登录抓自己 / guest 免登录抓他人公开收藏）
├── update/
│   └── UpdateManager.kt    # 自动更新：GitHub API 检测 + 镜像下载 + 系统安装
└── data/
    ├── FavoriteItem.kt     # 收藏视频模型（字段与 douyin-tools/favorites.json 兼容）
    └── SettingsStore.kt    # 本地持久化（SharedPreferences：Cookie + 收藏列表）
```

## 自动更新机制

- 版本来源：GitHub Release 标签 `build-<run_id>`；CI 构建时通过 `-PCI_BUILD_ID=<run_id>` 注入 `BuildConfig.CI_BUILD_ID`（本地构建为 0）。
- versionCode：CI 通过 `-PCI_VERSION_CODE=<run_number>` 注入（run_number 严格递增的小整数，保证每次 Release 版本号必然增大，可覆盖安装）。**不要**用 `CI_BUILD_ID.toInt()`（run_id 是超大数，截断为低 32 位可能变负数或不单调）。
- 检测逻辑：`BuildConfig.CI_BUILD_ID` 为 0（本地开发版）或小于服务器 build id → 提示更新；启动时仅 CI 构建自动静默检查，菜单「检查更新」随时手动检查。
- 下载加速：`UpdateManager.mirrors` 内置多个公开 GitHub 代理/镜像（gh-proxy.com、ghfast.top 等），先直连失败后逐个重试；镜像失效时可增删该列表。
- 安装：`FileProvider`（`filesDir/updates/`）+ 系统安装器；Manifest 已声明 `REQUEST_INSTALL_PACKAGES` 与包可见性 `<queries>`。
- 升级注意：配置了 `RELEASE_KEYSTORE_B64` 等 Secrets 时签名固定，可覆盖安装；未配置时 CI 每次生成的签名密钥不同，覆盖安装需卸载重装（见下「构建」）。

技术栈：Kotlin + ViewBinding + Material 3，单 Activity 多屏（`LoginActivity` / `FetchActivity` 为独立 Activity，通过 `ActivityResultLauncher` 联动）。

## 抓取流程时序（FetchActivity）

FetchActivity 支持两种模式（`EXTRA_MODE`，默认 `self`）：

- **self 模式（抓自己的收藏，需登录）**：打开 `https://www.douyin.com/user/self?showTab=favorite_collection`，先注入本地 Cookie。
- **guest 模式（免登录抓他人公开收藏）**：不注入任何 Cookie（清空 CookieManager），打开 `https://www.douyin.com/user/{sec_uid}?showTab=favorite_collection`。输入支持：
  - 完整主页链接 `douyin.com/user/{sec_uid}` → 直接提取 sec_uid；
  - `v.douyin.com` 短链 → 先加载，`onPageFinished` 后从最终 URL 提取 sec_uid；
  - 用户 ID（sec_uid）→ 直接使用；
  - 抖音号 → 打开 `douyin.com/search/{抖音号}`，**主路径是注入 `RESOLVE_HOOK_JS` 捕获搜索接口响应 JSON（`user_list[].user_info.sec_uid`，不依赖 DOM）**；`RESOLVE_JS` 从 DOM 提取用户卡片链接仅作兜底，找不到时点击「用户」筛选 tab 触发新的搜索请求。
  - 仅当对方开启「公开收藏」时才有数据；收藏 tab 通过 URL 参数 `showTab=favorite_collection` 激活。
  - **注意**：即使公开收藏，接口仍带 `a_bogus` 签名，所以同样必须走 WebView，只是不需要登录态。

自我/访客收藏页加载完成后（`onPageFinished` 后延迟 3 秒，等待首屏渲染）：

1. `injectHook()`：注入 JS hook `window.fetch` 与 `XMLHttpRequest`，捕获 URL 含 `listcollection` 的响应体，经 `DyBridge` 桥回传原生。
2. `seedFromDom()`：从已渲染 DOM 提取首屏 `video id`（兜底 hook 漏掉的第一页）。
3. `startScrolling()`：每 2.5 秒执行滚动 JS 触发翻页。
4. 停止条件：接口返回 `has_more = false`，或连续 8 轮无新增（有数据时）/ 3 轮无数据（空列表时）。
5. 保存到 `SettingsStore`。

## 约束与注意事项

- **Cookie 是敏感数据**：仅保存在本机应用私有 SharedPreferences，禁止上传/日志输出；`allowBackup=false` 防止备份泄露。guest 模式明确清空 CookieManager，保证「免登录」语义。
- 必须使用 **桌面 UA**（`Chrome/124 Windows`），保证收藏 tab 与接口存在于页面中。
- `addJavascriptInterface` 必须在 `loadUrl` 之前调用。
- 抖音页面结构/接口可能变化，`FetchActivity` 中的 JS（`HOOK_JS`、`DOM_SEED_JS`、`RESOLVE_HOOK_JS`、`RESOLVE_JS`、`SCROLL_JS`）是失效时优先排查对象。
- 修改涉及收藏抓取的逻辑时，先对照 douyin-tools 的 Python 实现确认接口行为。

## 测试

两类自动化测试，CI（`.github/workflows/build-release.yml`）在构建前自动执行，失败即中断发布：

1. **JVM 单元测试**（`app/src/test/`，JUnit4 + Robolectric）
   ```bash
   ./gradlew.bat testDebugUnitTest
   ```
   - 覆盖：`LoginActivity.parseCookies`、`FavoriteItem` JSON 序列化、`SettingsStore`（Robolectric 模拟 SharedPreferences）、`GuestInputParser`（访客输入解析）、`UpdateManager.buildIdFromTag`。
   - `GuestInputParser` 是把 `FetchActivity` 里的 sec_uid 提取/URL 解析/ DOM 解析逻辑抽出的纯对象，改解析逻辑时同步改这里。
   - **注意**：Robolectric 不支持 JDK 25（Android Studio 自带 JBR）。`app/build.gradle.kts` 里已用 `tasks.withType<Test> { javaLauncher = Java 17 }` 把单测固定跑在 Java 17 工具链（Gradle 通过 foojay 自动获取），勿回退。

2. **JS 注入脚本测试**（`js-tests/`，Python + Node）
   ```bash
   bash js-tests/run_js_tests.sh   # = python3 extract_js.py && node test_hooks.js
   ```
   - `extract_js.py` 从 `FetchActivity.kt` 提取全部 5 个 JS 常量（`HOOK_JS`/`DOM_SEED_JS`/`RESOLVE_HOOK_JS`/`RESOLVE_JS`/`SCROLL_JS`）到 `js-tests/build/`（该目录已 gitignore，不入库）。
   - `test_hooks.js` 做语法检查 + 行为验证（listcollection 捕获的 fetch/XHR 两条路径、sec_uid 解析、DOM 提取、用户 tab 点击、滚动翻页）。
   - **改任何 JS 常量后务必重跑**，否则语法/行为回归不会被发现。

## 构建

```bash
./gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`
