#!/usr/bin/env python3
"""
把源码树打包成 v0.1 那种 bundle 格式（#### FILE: <路径>），
方便继续用旧构建脚本的「现场解包」流程。

用法：python3 tools/pack-bundle.py > bundle.txt
"""
import os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKIP_DIRS = {'.git', '.gradle', 'build', '.idea', 'tools', 'dist'}
TEXT_EXT = {'.kt', '.kts', '.xml', '.md', '.properties', '.txt', '.yml', '.yaml', '.py', '.pro'}

files = []
for base, dirs, names in os.walk(ROOT):
    dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
    for name in names:
        path = os.path.join(base, name)
        rel = os.path.relpath(path, ROOT)
        if os.path.splitext(name)[1] in TEXT_EXT or name in {'xposed_init'}:
            files.append((rel.replace(os.sep, '/'), path))

out = sys.stdout
for rel, path in sorted(files):
    out.write("#### FILE: %s\n" % rel)
    with open(path, encoding='utf-8') as fh:
        out.write(fh.read())
    if not rel.endswith('\n'):
        out.write("\n")
out.write("#### END\n")
sys.stderr.write("packed %d files\n" % len(files))
