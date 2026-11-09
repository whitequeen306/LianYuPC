import os, re, json, io, sys

ROOT = '.'
EXCLUDE = {'.git','node_modules','dist','build','target','__pycache__','.logistics','.tmp','docs'}
def walk(prefixes, exts):
    out=[]
    for base in prefixes:
        for dp, dn, fns in os.walk(base):
            dn[:] = [d for d in dn if d not in EXCLUDE]
            for fn in fns:
                if os.path.splitext(fn)[1].lower() in exts:
                    out.append(os.path.join(dp,fn).replace('\\','/'))
    return out

def load_all(paths):
    d={}
    for p in paths:
        try: d[p]=open(p,encoding='utf-8',errors='ignore').read()
        except Exception: pass
    return d

def js_deps(pkg_path, prefixes):
    pkg=json.load(io.open(pkg_path,encoding='utf-8'))
    deps={**pkg.get('dependencies',{}),**pkg.get('devDependencies',{})}
    files=walk(prefixes,{'.js','.mjs','.cjs','.vue','.ts','.html'})
    texts=load_all(files)
    print('== deps for', pkg_path, 'scan files', len(files))
    for name in sorted(deps):
        pat=re.compile(r'(from\s+[\'"]'+re.escape(name)+r'[\'"/]|require\([\'"]'+re.escape(name)+r'[\'"/]|import\s+[\'"]'+re.escape(name)+r'[\'"/])')
        hits=[f for f,t in texts.items() if pat.search(t)]
        # also css/asset import e.g. 'element-plus/dist/index.css'
        if not hits:
            pat2=re.compile(re.escape(name)+r'/')
            hits=[f for f,t in texts.items() if pat2.search(t)]
        print('%-28s %-8s %s' % (name, 'USED' if hits else 'UNUSED', hits[:2]))

if __name__=='__main__':
    which=sys.argv[1]
    if which=='frontend':
        js_deps('frontend/package.json',['frontend/src','frontend/electron','frontend/shared','frontend/scripts'])
    elif which=='admin':
        js_deps('admin/package.json',['admin/src','admin/electron'])
