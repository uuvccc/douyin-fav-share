#!/usr/bin/env node
/**
 * 对 FetchActivity.kt 内嵌的 JS 注入脚本做自动化回归测试。
 *
 * 用法：
 *   python js-tests/extract_js.py
 *   node js-tests/test_hooks.js
 *
 * 覆盖：
 *   - 语法检查（5 个常量）
 *   - HOOK_JS：捕获 listcollection 接口（fetch + XHR 两条路径）
 *   - RESOLVE_HOOK_JS：从搜索接口响应提取 sec_uid（fetch + XHR 两条路径，幂等）
 *   - DOM_SEED_JS：从 DOM 提取 video id
 *   - RESOLVE_JS：从用户链接提取 sec_uid / 点击「用户」tab
 *   - SCROLL_JS：滚动可滚动容器
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const BUILD = path.join(__dirname, 'build');

function read(name) {
  return fs.readFileSync(path.join(BUILD, name), 'utf-8');
}

function freshWindow() {
  return {
    __test: {},
    scrollTo() {},
  };
}

let passed = 0;
let failed = 0;
const failures = [];

function test(name, fn) {
  try {
    fn();
    passed++;
    console.log('  PASS ' + name);
  } catch (e) {
    failed++;
    failures.push(name + ': ' + e.message);
    console.log('  FAIL ' + name + ' -> ' + e.message);
  }
}

function wait(fn) {
  return new Promise((resolve) => setTimeout(() => { fn(); resolve(); }, 15));
}

// ---------------------------------------------------------------------------
// 1. 语法检查
// ---------------------------------------------------------------------------
const NAMES = ['HOOK_JS', 'DOM_SEED_JS', 'RESOLVE_HOOK_JS', 'RESOLVE_JS', 'SCROLL_JS'];
NAMES.forEach((n) => {
  test('syntax check ' + n, () => {
    const src = read(n + '.js');
    assert.ok(src.length > 0, 'empty source');
    // 语法校验：new vm.Script 会抛 SyntaxError
    new vm.Script(src);
  });
});

// ---------------------------------------------------------------------------
// 2. HOOK_JS —— fetch 路径捕获 listcollection
// ---------------------------------------------------------------------------
async function testHookFetch() {
  test('HOOK_JS captures listcollection via fetch', async () => {
    let collected = null;
    const win = freshWindow();
    win.DyBridge = { onCollection: (json) => { collected = json; } };

    const realFetch = (url) => Promise.resolve({
      clone: () => ({ text: () => Promise.resolve(JSON.stringify({
        has_more: false,
        aweme_list: [
          { aweme_id: '7301', desc: '  hi  ', author: { nickname: '作者A' }, aweme_type: 0 },
          { aweme_id: '7302', desc: '', author: null, aweme_type: 2 },
        ],
      })) }),
    });
    win.fetch = realFetch;
    global.window = win;
    global.fetch = realFetch;
    global.XMLHttpRequest = XHRStub;
    vm.runInThisContext(read('HOOK_JS.js'));

    win.fetch('https://www.douyin.com/aweme/v1/web/aweme/listcollection/?cursor=0');
    await wait(() => {
      const obj = JSON.parse(collected);
      assert.strictEqual(obj.has_more, false);
      assert.strictEqual(obj.items.length, 2);
      assert.strictEqual(obj.items[0].aweme_id, '7301');
      assert.strictEqual(obj.items[0].desc, 'hi'); // trim 生效
      assert.strictEqual(obj.items[0].author, '作者A');
      assert.strictEqual(obj.items[1].author, ''); // 无作者兜底空串
    });
  });
}

// ---------------------------------------------------------------------------
// 3. HOOK_JS —— XHR 路径捕获 listcollection
// ---------------------------------------------------------------------------
function XHRStub() {
  this.__dyUrl = '';
  this.responseText = '';
  this.listeners = {};
}
XHRStub.prototype.open = function (method, url) { this.__dyUrl = url; };
XHRStub.prototype.send = function () {
  const self = this;
  // 模拟异步回调，触发已注册的 load 监听
  setImmediate(() => { (self.listeners.load || []).forEach((fn) => fn()); });
};
XHRStub.prototype.addEventListener = function (type, fn) {
  (this.listeners[type] = this.listeners[type] || []).push(fn);
};

async function testHookXhr() {
  test('HOOK_JS captures listcollection via XHR', async () => {
    let collected = null;
    const win = freshWindow();
    win.DyBridge = { onCollection: (json) => { collected = json; } };
    global.window = win;
    global.fetch = undefined;
    global.XMLHttpRequest = XHRStub;
    vm.runInThisContext(read('HOOK_JS.js'));

    const xhr = new XHRStub();
    xhr.open('GET', 'https://www.douyin.com/aweme/v1/web/aweme/listcollection/?cursor=20');
    xhr.responseText = JSON.stringify({
      has_more: true,
      aweme_list: [{ aweme_id: '999', desc: 'd', author: { nickname: 'N' }, aweme_type: 0 }],
    });
    xhr.send();
    await wait(() => {
      const obj = JSON.parse(collected);
      assert.strictEqual(obj.has_more, true);
      assert.strictEqual(obj.items[0].aweme_id, '999');
    });
  });
}

// ---------------------------------------------------------------------------
// 4. RESOLVE_HOOK_JS —— fetch 路径提取 sec_uid
// ---------------------------------------------------------------------------
async function testResolveFetch() {
  test('RESOLVE_HOOK_JS extracts sec_uid via fetch', async () => {
    let resolved = null;
    const win = freshWindow();
    win.DyBridge = { onResolved: (s) => { resolved = s; } };
    const realFetch = (url) => Promise.resolve({
      clone: () => ({ text: () => Promise.resolve(JSON.stringify({
        data: { user_list: [{ user_info: { sec_uid: 'MS4wLjAB_sec_123' } }] },
      })) }),
    });
    win.fetch = realFetch;
    global.window = win;
    global.fetch = realFetch;
    global.XMLHttpRequest = XHRStub;
    vm.runInThisContext(read('RESOLVE_HOOK_JS.js'));

    win.fetch('https://www.douyin.com/aweme/v1/web/general/search/single/?keyword=abc');
    await wait(() => {
      assert.strictEqual(resolved, 'MS4wLjAB_sec_123');
    });
  });
}

// ---------------------------------------------------------------------------
// 5. RESOLVE_HOOK_JS —— XHR 路径提取 sec_uid + 幂等（只回调一次）
// ---------------------------------------------------------------------------
async function testResolveXhr() {
  test('RESOLVE_HOOK_JS extracts sec_uid via XHR and is idempotent', async () => {
    let calls = 0;
    let resolved = null;
    const win = freshWindow();
    win.DyBridge = { onResolved: (s) => { calls++; resolved = s; } };
    global.window = win;
    global.fetch = undefined;
    global.XMLHttpRequest = XHRStub;
    vm.runInThisContext(read('RESOLVE_HOOK_JS.js'));

    const xhr = new XHRStub();
    xhr.open('GET', 'https://www.douyin.com/aweme/v1/web/general/search/single/?keyword=x');
    xhr.responseText = JSON.stringify({
      data: { users: [{ user_info: { sec_uid: 'MS4wLjAB_sec_456' } }] },
    });
    xhr.send();
    await wait(() => {
      assert.strictEqual(resolved, 'MS4wLjAB_sec_456');
      assert.strictEqual(calls, 1);
    });
  });
}

// ---------------------------------------------------------------------------
// 6. DOM_SEED_JS —— 从 DOM 提取 video id（去重）
// ---------------------------------------------------------------------------
test('DOM_SEED_JS extracts unique video ids from DOM', () => {
  const win = freshWindow();
  win.document = {
    querySelectorAll: (sel) => [
      { getAttribute: () => '/video/730111' },
      { getAttribute: () => '/video/730111' }, // 重复
      { getAttribute: () => '/video/730222' },
    ],
  };
  global.window = win;
  const sandbox = { window: win, document: win.document };
  const ret = vm.runInNewContext(read('DOM_SEED_JS.js'), sandbox);
  // vm 上下文对象跨 realm，deepStrictEqual 会因原型不同而失败，改用 JSON 比对
  assert.strictEqual(JSON.stringify(ret), JSON.stringify({ ids: ['730111', '730222'] }));
});

// ---------------------------------------------------------------------------
// 7. RESOLVE_JS —— 从用户链接提取 sec_uid
// ---------------------------------------------------------------------------
test('RESOLVE_JS extracts secUid from a user link', () => {
  const win = freshWindow();
  win.document = {
    querySelectorAll: () => [
      { getAttribute: () => '/user/self' },
      { getAttribute: () => '/user/MS4wLjAB_domsec_1' },
    ],
  };
  const ret = vm.runInNewContext(read('RESOLVE_JS.js'), { window: win, document: win.document });
  assert.strictEqual(ret.secUid, 'MS4wLjAB_domsec_1');
});

test('RESOLVE_JS clicks user tab when no user link found', () => {
  let clicked = 0;
  const win = freshWindow();
  win.document = {
    querySelectorAll: () => [
      { getAttribute: () => '/search/foo' },
      { getAttribute: () => '/search/bar' },
    ],
  };
  // 找不到用户链接时进入候选元素扫描：需要一个 textContent 为「用户」的元素
  const userEl = { textContent: '用户', offsetParent: {}, childElementCount: 0, click: () => { clicked++; } };
  // 覆盖 querySelectorAll 以返回候选元素（div/span/li/a 选择器）
  win.document.querySelectorAll = (sel) => {
    if (sel === 'a[href*="/user/"]') return [
      { getAttribute: () => '/search/foo' },
      { getAttribute: () => '/search/bar' },
    ];
    return [userEl];
  };
  const ret = vm.runInNewContext(read('RESOLVE_JS.js'), { window: win, document: win.document });
  assert.strictEqual(ret.clickedUserTab, true);
  assert.strictEqual(clicked, 1);
});

// ---------------------------------------------------------------------------
// 8. SCROLL_JS —— 滚动可滚动容器
// ---------------------------------------------------------------------------
test('SCROLL_JS scrolls scrollable containers', () => {
  let scrolled = 0;
  const scrollable = { scrollHeight: 1000, clientHeight: 200, scrollTop: 0 };
  const fixed = { scrollHeight: 100, clientHeight: 200, scrollTop: 0 };
  let winScroll = 0;
  const win = { scrollTo: (x, y) => { winScroll = y; } };
  win.document = {
    body: { scrollHeight: 500 },
    querySelectorAll: () => [scrollable, fixed],
  };
  vm.runInNewContext(read('SCROLL_JS.js'), { window: win, document: win.document });
  assert.strictEqual(scrollable.scrollTop, 1000);
  assert.strictEqual(fixed.scrollTop, 0); // 不可滚动的不动
  assert.strictEqual(winScroll, 500);
});

// ---------------------------------------------------------------------------
// 运行异步测试
// ---------------------------------------------------------------------------
(async function main() {
  console.log('Running hook tests...');
  await testHookFetch();
  await testHookXhr();
  await testResolveFetch();
  await testResolveXhr();

  console.log('');
  console.log(`Result: ${passed} passed, ${failed} failed`);
  if (failed > 0) {
    failures.forEach((f) => console.log('  - ' + f));
    process.exit(1);
  }
  process.exit(0);
})();
