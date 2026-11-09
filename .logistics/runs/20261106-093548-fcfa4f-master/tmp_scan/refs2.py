import os, re, sys, json, collections

ROOT = os.path.abspath('.')
EXCLUDE_DIRS = {'.git', 'node_modules', 'dist', 'build', 'target', '__pycache__', '.logistics', 'artifacts', 'avatar', 'secrets', 'certs'}
CODE_EXT = {'.js', '.mjs', '.cjs', '.vue', '.ts', '.py', '.java', '.json', '.sql', '.ps1', '.yml', '.yaml', '.html'}

files = []
for dirpath, dirnames, filenames in os.walk(ROOT):
    dirnames[:] = [d for d in dirnames if d not in EXCLUDE_DIRS]
    for fn in filenames:
        ext = os.path.splitext(fn)[1].lower()
        if ext in CODE_EXT:
            p = os.path.join(dirpath, fn)
            rel = os.path.relpath(p, ROOT).replace('\\', '/')
            files.append(rel)

TOKEN = re.compile(r'[A-Za-z_$][A-Za-z0-9_$]*')
idents = {}
for rel in files:
    try:
        t = open(rel, encoding='utf-8', errors='ignore').read()
    except Exception:
        t = ''
    idents[rel] = set(TOKEN.findall(t))

def refs(name, self_rel):
    return [rel for rel, s in idents.items() if rel != self_rel and name in s]

def report(title, pred, skip=('index', 'main', 'App')):
    print('== ' + title + ' ==')
    for rel in sorted(x for x in files if pred(x)):
        base = os.path.splitext(os.path.basename(rel))[0]
        if base in skip:
            continue
        hits = refs(base, rel)
        if len(hits) <= 0:
            print('ORPHAN  %-72s refs=0' % rel)
        elif len(hits) == 1:
            print('SINGLE  %-72s refs=1 -> %s' % (rel, hits[0]))

if len(sys.argv) > 1 and sys.argv[1] == 'front':
    report('frontend/admin modules', lambda r: (r.startswith('frontend/src/') and r.endswith(('.js', '.vue'))) or (r.startswith('frontend/electron/') and r.endswith(('.js', '.mjs'))) or (r.startswith('frontend/shared/') and r.endswith('.js')) or (r.startswith('admin/src/') and r.endswith(('.js', '.vue'))))
elif len(sys.argv) > 1 and sys.argv[1] == 'java':
    report('java classes', lambda r: r.startswith('backend/') and r.endswith('.java') and '/mapper/' not in r and '/entity/' not in r, skip=())
elif len(sys.argv) > 1 and sys.argv[1] == 'py':
    report('backend-agent modules', lambda r: r.startswith('backend-agent/app/') and r.endswith('.py'), skip=('__init__',))
