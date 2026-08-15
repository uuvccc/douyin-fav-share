#!/usr/bin/env bash
# 运行 FetchActivity 内嵌 JS 注入脚本的自动化测试（语法 + 行为）。
# 依赖：python3 与 node。
set -euo pipefail
cd "$(dirname "$0")"

python3 extract_js.py
node test_hooks.js
echo "JS tests passed."
