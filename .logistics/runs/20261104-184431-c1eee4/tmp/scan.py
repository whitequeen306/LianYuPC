#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""Targeted reliability pattern scan over the scope file list."""
import re, sys, os, json

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..'))
FILES = [l.strip() for l in open(os.path.join(os.path.dirname(__file__), 'files.txt'), encoding='utf-8') if l.strip()]

PATTERNS = json.load(open(os.path.join(os.path.dirname(__file__), 'patterns.json'), encoding='utf-8'))

def read(p):
    try:
        with open(os.path.join(ROOT, p), 'r', encoding='utf-8', errors='replace') as f:
            return f.read()
    except Exception:
        return None

cache = {}
def get(p):
    if p not in cache:
        cache[p] = read(p)
    return cache[p]

out = []
for pat in PATTERNS:
    name = pat['name']
    rx = re.compile(pat['regex'], re.S if pat.get('multiline') else 0)
    exts = pat.get('exts')
    limit = pat.get('limit', 40)
    hits = 0
    shown = 0
    out.append('### %s  regex=%s' % (name, pat['regex']))
    for p in FILES:
        if exts and not p.lower().endswith(tuple(exts)):
            continue
        txt = get(p)
        if txt is None:
            continue
        for m in rx.finditer(txt):
            hits += 1
            if shown >= limit:
                continue
            line = txt.count('\n', 0, m.start()) + 1
            start = txt.rfind('\n', 0, m.start()) + 1
            end = txt.find('\n', m.end())
            if end < 0:
                end = len(txt)
            snippet = ' '.join(txt[start:end].strip().split())
            if len(snippet) > 200:
                snippet = snippet[:200] + '...'
            out.append('%s:%d | %s' % (p, line, snippet))
            shown += 1
    out.append('--- %s: total=%d shown=%d' % (name, hits, shown))
    out.append('')

open(os.path.join(os.path.dirname(__file__), 'scan_out.txt'), 'w', encoding='utf-8').write('\n'.join(out))
print('done', sum(1 for _ in open(os.path.join(os.path.dirname(__file__), 'scan_out.txt'), encoding='utf-8')))
