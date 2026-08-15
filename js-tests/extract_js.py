#!/usr/bin/env python3
"""从 FetchActivity.kt 提取所有 private const val XXX = 三引号 JS 常量，
写到 js-tests/build/ 目录，供 Node 测试脚本做语法检查与行为验证。

用法：
    python js-tests/extract_js.py
"""
import io
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(REPO, 'app', 'src', 'main', 'java',
                   'com', 'example', 'myapplication', 'FetchActivity.kt')
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'build')

PAT = re.compile(r'private const val (\w+) = """\n(.*?)\n\s*"""', re.S)


def main() -> int:
    if not os.path.exists(SRC):
        print(f'ERROR: source not found: {SRC}', file=sys.stderr)
        return 1
    os.makedirs(OUT, exist_ok=True)
    src = io.open(SRC, encoding='utf-8').read()
    found = 0
    for m in PAT.finditer(src):
        name, js = m.group(1), m.group(2)
        with io.open(os.path.join(OUT, name + '.js'), 'w', encoding='utf-8') as f:
            f.write(js)
        print(f'WROTE {name}.js ({len(js)} chars)')
        found += 1
    if found == 0:
        print('ERROR: no JS constants found', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
