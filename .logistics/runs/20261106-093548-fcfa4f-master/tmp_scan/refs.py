import os, re, sys, json, collections

ROOT = os.path.abspath('.')
EXCLUDE_DIRS = {'.git', 'node_modules', 'dist', 'build', 'target', '__pycache__', '.logistics', 'docs', 'artifacts', 'avatar', 'secrets', 'certs'}
CODE_EXT = {'.js', '.mjs', '.cjs', '.vue', '.ts', '.py', '.java', '.json', '.sql', '.ps1', '.yml', '.yaml', '.html', '.md'}

files = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
    for fn in filenames:
        ext = os.path.splitext(fn)[1].lower()
        if ext in CODE_EXT:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT).replace('\\', '/')
            files.append(rel)

text = {}
for rel in files:
    try:
        text[rel] = open(rel, encoding='utf-8', errors='ignore').read()
    except Exception:
        text[rel] = ''

def refs(name, self_rel, exts=None):
    hits = []
    pat = re.compile(r'\b' + re.escape(name) + r'\b')
    for rel, t in text.items():
        if rel == self_rel:
            continue
        if exts and os.path.splitext(rel)[1].lower() not in exts:
            continue
        if pat.search(t):
            hits.append(rel)
    return hits

targets = []
for rel in files:
    if rel.startswith('frontend/src/') and rel.endswith(('.js', '.vue')):
        targets.append(rel)
    elif rel.startswith('frontend/electron/') and rel.endswith(('.js', '.mjs')):
        targets.append(rel)
    elif rel.startswith('frontend/shared/') and rel.endswith(('.js',)):
        targets.append(rel)
    elif rel.startswith('admin/src/') and rel.endswith(('.js', '.vue')):
        targets.append(rel)

print('== frontend/admin module reference scan (basename token) ==')
for rel in targets:
    base = os.path.splitext(os.path.basename(rel))[0]
    if base in ('index', 'main', 'App'):
        continue
    hits = refs(base, rel)
    if len(hits) <= 1:
        print('%-70s refs=%d %s' % (rel, len(hits), hits[:3]))
