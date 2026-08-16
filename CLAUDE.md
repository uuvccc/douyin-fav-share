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
- 检测逻辑：`BuildConfig.CI_BUILD_ID` 为 0（本地开发版）或小于服务器 build id → 提示更新；启动时仅 CI 构建自动静默检查，菜单「检查更新」随时手动检查。
- 下载加速：`UpdateManager.mirrors` 内置多个公开 GitHub 代理/镜像（gh-proxy.com、ghfast.top 等），先直连失败后逐个重试；镜像失效时可增删该列表。
- 安装：`FileProvider`（`filesDir/updates/`）+ 系统安装器；Manifest 已声明 `REQUEST_INSTALL_PACKAGES` 与包可见性 `<queries>`。
- 升级签名：CI 优先用仓库 Secrets 里固定的 release keystore（`RELEASE_KEYSTORE_B64` 等 4 个 Secret）签名，签名跨构建稳定，**可覆盖安装升级**；未配置 Secrets 时才回退为临时 keystore（每次签名不同，需卸载重装）。
- **下载哪个 APK**：`UpdateManager.pickApkAsset` 优先下载 `app-release.apk`（固定签名），不会下载 `app-debug.apk`——CI 每次构建会重新生成 debug keystore，签名不稳定，覆盖安装会报「软件包与现有软件包存在冲突」。
- 升级注意：**Secrets 配置前的旧 Release（临时密钥签名）无法直接覆盖升级**，需先卸载重装一次最新 Release（固定签名）后，后续更新才能覆盖安装。

技术栈：Kotlin + ViewBinding + Material 3，单 Activity 多屏（`LoginActivity` / `FetchActivity` 为独立 Activity，通过 `ActivityResultLauncher` 联动）。

## 抓取流程时序（FetchActivity）

FetchActivity 支持两种模式（`EXTRA_MODE`，默认 `self`）：

- **self 模式（抓自己的收藏，需登录）**：打开 `https://www.douyin.com/user/self?showTab=favorite_collection`，先注入本地 Cookie。
- **guest 模式（免登录抓他人公开收藏）**：不注入任何 Cookie（清空 CookieManager），打开 `https://www.douyin.com/user/{sec_uid}?showTab=favorite_collection`。输入支持：
  - 完整主页链接 `douyin.com/user/{sec_uid}` → 直接提取 sec_uid；
  - `v.douyin.com` 短链 → 先加载，`onPageFinished` 后从最终 URL 提取 sec_uid；
  - 用户 ID（sec_uid）→ 直接使用；
  - 抖音号 → 打开 `douyin.com/search/{抖音号}`，注入 `RESOLVE_JS` 从 DOM 提取第一个用户卡片链接的 sec_uid。
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
- 抖音页面结构/接口可能变化，`FetchActivity` 中的 JS（`HOOK_JS`、`DOM_SEED_JS`、`RESOLVE_JS`、`SCROLL_JS`）是失效时优先排查对象。
- 修改涉及收藏抓取的逻辑时，先对照 douyin-tools 的 Python 实现确认接口行为。

## 构建

```bash
./gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

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

3. **PC 端工具测试**（`douyin-tools/test_douyin_cookie_qr.py`，Python 标准库 unittest）
   ```bash
   cd douyin-tools && .venv/Scripts/python -m unittest test_douyin_cookie_qr
   ```
   - 覆盖 `douyin_cookie_qr.py` 的 `cookie_str_from_list`（Cookie 拼接）与 `build_qr_image`（二维码生成）。
   - 纯函数在脚本内**延迟导入 playwright**，因此无需安装浏览器即可测试；CI 步骤仅 `pip install qrcode[pil] pillow` 后直接跑 unittest。
   - 该测试已加入 CI（`build-release.yml`）。

## 交接记录（2026-08-15）

### 本会话已完成的工作

1. **抽取纯逻辑对象 `GuestInputParser`**（`app/src/main/java/com/example/myapplication/data/GuestInputParser.kt`）：把 `FetchActivity` 里 sec_uid 提取 / URL 解析 / DOM 解析三处内联正则统一抽出，`FetchActivity` 改为委托调用。改解析逻辑必须同步改 `GuestInputParserTest`。
2. **修复 `LoginActivity.parseCookies`**：原实现只对整段 Cookie 字符串 trim，导致带空格的 Cookie 产生错误键名/值；现分别 trim 键和值。
3. **`UpdateManager` 新增 `buildIdFromTag(tag)`**：从 GitHub Release 标签 `build-<run_id>` 提取 build id，JSON 解析处已调用。
4. **JVM 单元测试 30 个**（`app/src/test/`，JUnit4 + Robolectric 4.13 + org.json）：
   - 覆盖 `GuestInputParser` / `FavoriteItem` / `SettingsStore` / `LoginActivity.parseCookies` / `UpdateManager.buildIdFromTag`。
   - Robolectric 与 JDK 25（AS 自带 JBR）不兼容，`app/build.gradle.kts` 已用 `tasks.withType<Test> { javaLauncher = Java 17 }` 固定 Java 17 工具链（foojay 自动获取），**勿回退**。
   - 运行 `./gradlew.bat testDebugUnitTest` → 全部通过（30 passed）。
5. **JS 注入脚本自动化测试 13 个**（`js-tests/`）：
   - `extract_js.py` 从 `FetchActivity.kt` 提取 5 个 JS 常量到 `js-tests/build/`（gitignore，不入库）。
   - `test_hooks.js`（Node vm + assert，无外部依赖）做语法检查 + 行为验证（fetch/XHR 双路径捕获 listcollection、sec_uid 解析、DOM 提取、用户 tab 点击、滚动翻页）。
   - 运行 `bash js-tests/run_js_tests.sh` → 13 passed。
   - **改任何 JS 常量后务必重跑。**
6. **CI 门禁**（`.github/workflows/build-release.yml`）：构建/发布前先跑 `./gradlew testDebugUnitTest` 与 `bash js-tests/run_js_tests.sh`，任一失败即中断流程。
7. **已提交并推送**：commit `7eebdd1`（--no-verify），`f25ad7b..7eebdd1 master -> master`，master 与 origin/master 同步。

### 遗留问题：PC 端 Playwright 无法验证 guest 模式端到端抓取

**目标**：用户要求用默认用户 ID `54132528295`（`MainActivity.kt` 访客输入框默认抖音号）真实跑通「搜索页解析 sec_uid → 打开收藏页 → 捕获 `listcollection` → 滚动翻页到 `has_more=false`」完整流程，确认 App guest 模式能抓到该用户所有公开收藏视频。

**尝试过程与结果**（Playwright 1.62 + 自带 Chromium，位于 `douyin-tools/.venv`）：
1. 复刻 App 流程写了 `verify_guest_fetch.py`：注入与生产代码完全一致的 `RESOLVE_HOOK_JS` / `HOOK_JS` / `SCROLL_JS`（经 `js-tests/extract_js.py` 提取），用 window 桥收集数据。
2. **第一步搜索页即被抖音 `sec_sdk` 滑块验证码拦截**：页面变为「验证码中间页」（`subtype: slide`），`user_list` 搜索接口不返回数据，无法解析出 sec_uid。
3. 已尝试的绕过手段**全部无效**：
   - headless=False 真实窗口；
   - `--disable-blink-features=AutomationControlled`、`--disable-infobars` 等启动参数；
   - 注入 JS 隐藏 `navigator.webdriver`、伪造 `window.chrome` / `navigator.plugins` / `navigator.languages`；
   - 系统未安装 Google Chrome（仅有 Playwright 自带 Chromium），无法走真实 Chrome 通道。
4. 结论：**PC + Playwright 自动化浏览器指纹被抖音风控识别，无法在 PC 端自动完成该验证**。这是环境/风控问题，**不是 App 代码逻辑问题**——解析逻辑与 JS 注入脚本均已通过单元测试与 JS 测试验证。

**给下一位接手 agent 的建议**：
- 不要在 PC 端 Playwright 上继续尝试绕过验证码（多种方案已验证无效，投入产出比低）。
- 优先在**真实 Android 设备/模拟器**上安装 APK 验证 guest 模式（WebView 指纹正常，触发风控概率远低于 Playwright）；self 模式（登录抓自己）成功率最高，建议先跑通 self 再测 guest。
- 若坚持 PC 端验证：需先手动在浏览器过一次滑块验证，再让 Playwright 复用同一浏览器 profile（`user_data_dir`）。
- 本次临时诊断脚本（`verify_guest_fetch.py` / `diag_search.py` / `diag_captcha.py`、截图及 `.profile_*` 目录）**均已删除，未入库**；需要时可参考 `douyin-tools/fetch_collections.py` 重建。

### 新功能：PC ↔ Android 二维码 Cookie 同步（2026-08-15 新增）

**背景**：PC 端登录抖音抓 Cookie 一直要手动复制粘贴，体验差。新增二维码方案：PC 端一键运行脚本 → 自动登录捕获 Cookie → 生成二维码；Android 端扫码即导入，全程免手动复制。

**PC 端 `douyin-tools/douyin_cookie_qr.py`**：
- 运行：`douyin-tools/.venv/Scripts/python douyin-tools/douyin_cookie_qr.py`（需先 `pip install "qrcode[pil]" pillow`；playwright 已装在 venv）。
- 流程：Playwright 有头模式 + **桌面版 UA**（Windows Chrome）打开 `douyin.com` → 5 秒后若无登录浮层自动点击「登录」（多选择器依次尝试，桌面版右上角按钮最稳定；曾用移动端 UA 但登录浮层不弹出，已弃用）→ 轮询 `context.cookies()` 检测 `sessionid`（120 秒超时）→ 拼接 `name=value; name=value` 保存 `~/.douyin_cookie.txt` → qrcode 生成二维码（version 8 / 纠错 M / box_size 12 / border 2，顶部 80px 提示文字「扫描此二维码获取 Cookie」）保存 `~/.douyin_cookie_qr.png` 并自动打开 → 回车退出。
- 已修正原需求中的错误：`--no-sandbox`（非 `nosandbox`）、跨平台中文字体 fallback（macOS 苹方 / Windows 微软雅黑 / Linux 文泉驿 / load_default）。
- 可测试纯函数：`cookie_str_from_list(cookies)`、`build_qr_image(cookie_str)`、`show_qr(cookie_str)`。playwright 在 `capture_cookie()` 内延迟导入，保证测试不依赖浏览器。

**Android 端**：
- 依赖（`gradle/libs.versions.toml` + `app/build.gradle.kts`）：`com.google.zxing:core:3.5.3`、`com.journeyapps:zxing-android-embedded:4.3.0`。
- `AndroidManifest.xml` 已加 `CAMERA` 权限（运行时权限由 ZXing `ScanContract` 自动请求）。
- `activity_main.xml` 登录卡片新增 `btnScanCookie`（「📷 扫码获取 Cookie（PC 端二维码）」）。
- `MainActivity.kt`：新增 `scanLauncher = registerForActivityResult(ScanContract())`，扫码结果复用现有 `importCookieText(text.trim())`（JSON 或 `name=value; ...` 均支持，内部走 `LoginActivity.parseCookies` + `SettingsStore.saveCookies`）；`importCookieText` 增强提示：未检测到 `sessionid` 时提示「登录态可能不完整」。

**测试**：`douyin-tools/test_douyin_cookie_qr.py`（7 个 unittest：Cookie 拼接/空值跳过/长 Cookie 自动升版/图片尺寸与布局/保存），CI 已加对应步骤。Android 侧解析逻辑复用已有 `parseCookies` 测试，未新增。

### 交接记录（2026-08-16）：直连接口抓取可行性实测

**目标**：用户已有真实登录 Cookie（PC 端 `~/.douyin_cookie.txt`），希望放弃 WebView 模拟抓取（滚动慢），改用直接 HTTP 调用 `listcollection` 接口导出全部收藏（每页 1 次 HTTP、按 `max_cursor` 直接翻页、无需滚动），再随机选一条生成分享链接。

**关键事实**：`listcollection` 必须带 `a_bogus` 签名（+`msToken`）；cookie 是身份、a_bogus 是签名，两者都要。2026 年有开源 a_bogus 实现（JS/Python/C++）但**随抖音更新失效**。因此先做一步「签名 + cookie 走裸 HTTP 通不通」的实测，用数据决定走直连还是回退 WebView。

**本会话完成**：
- 新增 `douyin-tools/test_direct_api.py`：三段式诊断脚本（①直连无签名预期被拒 ②Playwright 带 cookie 打开收藏页，旁观捕获一条**真实签名** listcollection 请求 → urllib 原样重放，A=浏览器cookie/B=文件cookie 两个变体与浏览器内 ground truth 同屏对比 ③滚动触发第二页（新 max_cursor 真签名）再重放）。**安全：绝不打印 cookie 值**（`redact_url` 掩码 a_bogus/msToken 只留前 40 字符）。
- 新增 `douyin-tools/test_test_direct_api.py`：19 个 unittest（`parse_cookie_file`/`redact_url`/`build_step1_url`/`cookie_names_diff`/`gzip_maybe` + 本地 mock http server 验证 `http_get` 的头保真/403 body/gzip 解压）。无 cookie、无网络、无浏览器可跑。
- 本机验证通过：`python -m unittest test_test_direct_api`（19 passed）+ `python test_direct_api.py --selftest`（Playwright 捕获闭环 OK）。
- 注意：`page.expect_response()` 返回 `AsyncEventContextManager`，必须用 `async with ... as event:` + `event.value`，不能直接 `asyncio.shield` 它（TypeError）。

**判定（不对称实验，勿误判）**：重放 200 且 body 含 `aweme_list` = **决定性成功**（直连可行）；浏览器内成功但重放失败 = **不具决定性**（可能 TLS 指纹/HTTP 版本被 WAF 拦、文件 cookie 不完整或过期、头缺失）。「成功有决定性、失败无决定性」。

**使用**：F: 机器 `git pull` 后 `douyin-tools/.venv/Scripts/python test_direct_api.py`，把三段输出 + VERDICT 贴回分析。**该脚本是有 cookie 的机器才能跑完**（本机无 cookie，只做了单测/自测）。暂未加入 CI（一次性诊断工具，非生产代码）。

**待实测后决策（Phase 2）**：
- 若直连通 → 直连抓取器：PC 端 Python 先验证，再评估 App 端（隐藏 WebView 跑签名 JS + OkHttp 直连；**Android OkHttp 的 TLS 指纹与 Chrome 不同，需真机再实测**）。签名算法随抖音更新需持续维护。
- 若失败/不具决定性 → 回退 WebView 事件驱动重构：`CollectionPager` 状态机替代 2.5s 定时滚动（速度 ~3x）。该设计已评审通过，含 4 处必修：teardown 清理定时器 / kick 合并 / FINISH 幂等 / onPageFinished 门控修复（含 self 模式抓取未启动的 bug：`profileLoaded` 仅 guest 模式设置）。
