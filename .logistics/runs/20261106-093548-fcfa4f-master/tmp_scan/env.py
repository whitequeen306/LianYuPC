import os, re, io, collections

EXCLUDE={'.git','node_modules','dist','build','target','__pycache__','.logistics','.tmp','docs','artifacts','avatar'}
def files(exts):
    out=[]
    for dp,dn,fns in os.walk('.'):
        dn[:]=[d for d in dn if d not in EXCLUDE]
        for fn in fns:
            if os.path.splitext(fn)[1].lower() in exts:
                out.append(os.path.join(dp,fn).replace('\\','/'))
    return out

usage=collections.defaultdict(set)
pat_spring=re.compile(r'\$\{([A-Z][A-Z0-9_]{2,})(?::[^}]*)?\}')
for f in files({'.yml','.yaml','.properties','.xml','.java'}):
    t=open(f,encoding='utf-8',errors='ignore').read()
    for m in pat_spring.finditer(t):
        usage[m.group(1)].add(f)
pat_js=re.compile(r'process\.env\.([A-Z][A-Z0-9_]{2,})|process\.env\[[\'"]([A-Z][A-Z0-9_]{2,})[\'"]\]')
for f in files({'.js','.mjs','.cjs'}):
    t=open(f,encoding='utf-8',errors='ignore').read()
    for m in pat_js.finditer(t):
        usage[m.group(1) or m.group(2)].add(f)
pat_py=re.compile(r'os\.(?:getenv|environ\.get)\(\s*[\'"]([A-Z][A-Z0-9_]{2,})[\'"]')
for f in files({'.py'}):
    t=open(f,encoding='utf-8',errors='ignore').read()
    for m in pat_py.finditer(t):
        usage[m.group(1)].add(f)
pat_sh=re.compile(r'\$\{([A-Z][A-Z0-9_]{2,})(?::?[-}])')
for f in files({'.sh','.ps1'}):
    t=open(f,encoding='utf-8',errors='ignore').read()
    for m in pat_sh.finditer(t):
        usage[m.group(1)].add(f)

doc=set(re.findall(r'^([A-Z][A-Z0-9_]+)', io.open('.env.example',encoding='utf-8').read(), re.M))

print('== documented in .env.example but never read (%d)' % len([k for k in doc if k not in usage]))
for k in sorted(doc):
    if k not in usage: print('  DOC_ONLY', k)
print()
print('== read but not in .env.example (top 40 by distinct files)')
missing=[(k,len(v),sorted(v)[:2]) for k,v in usage.items() if k not in doc]
missing.sort(key=lambda x:-x[1])
for k,n,v in missing[:40]:
    print('  UNDOC %-38s files=%d %s' % (k,n,v))
print('total undoc', len(missing))
