import os, re, io, collections

def read(p):
    return io.open(p, encoding='utf-8', errors='ignore').read()

print('== Java class metrics (methods / ctor params / longest method) ==')
targets = [
 'backend/lianyu-service/src/main/java/com/lianyu/service/conversation/GroupChatService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ConversationService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/conversation/ProactiveMessageService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsCommentOrchestrator.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/moments/MomentsService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/voice/VoiceCallDuplexSession.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/storage/FileStorageService.java',
 'backend/lianyu-service/src/main/java/com/lianyu/service/relationship/RelationshipStateService.java',
 'backend/lianyu-web/src/main/java/com/lianyu/web/agent/AgentRuntimeClient.java',
]
for p in targets:
    t = read(p)
    lines = t.split('\n')
    # ctor params: class-level final fields
    finals = len(re.findall(r'^\s*private final [A-Za-z0-9_<>,\.\[\] ]+ \w+;', t, re.M))
    methods = re.findall(r'^\s{4}(?:public|private|protected)\s+[^=;{}]*\(([^)]*)\)\s*(?:throws [\w, ]+)?\{', t)
    # longest method by brace count heuristic
    best = 0
    cur = 0
    for ln in lines:
        cur += ln.count('{') - ln.count('}')
    print('%-100s lines=%4d finalFields=%2d methods=%3d' % (p.split('/')[-1], len(lines), finals, len(methods)))

print()
print('== method length > 80 lines (brace scan) ==')
def long_methods(path, threshold=80):
    lines = read(path).split('\n')
    out = []
    depth = 0
    start = None
    name = ''
    for i, ln in enumerate(lines):
        if depth == 0 and re.search(r'\b(public|private|protected)\s+[\w<>\[\],\. ]+\s+\w+\s*\(', ln) and '{' in ln:
            start = i
            name = ln.strip()[:90]
        depth += ln.count('{') - ln.count('}')
        if start is not None and depth == 0 and i > start:
            if i - start >= threshold:
                out.append((start + 1, i + 1, i + 1 - start, name))
            start = None
    return out

for p in targets[:8]:
    for s, e, n, name in long_methods(p):
        print('%-60s L%-5d-L%-5d %3d lines  %s' % (p.split('/')[-1], s, e, n, name))

print()
print('== frontend/electron/main.js structure ==')
t = read('frontend/electron/main.js')
print('lines', t.count('\n'), 'ipcMain.handle', len(re.findall(r'ipcMain\.handle\(', t)), 'ipcMain.on', len(re.findall(r'ipcMain\.on\(', t)), 'imports', len(re.findall(r'^import ', t, re.M)))
print('== backend-agent/app/main.py structure ==')
t = read('backend-agent/app/main.py')
print('lines', t.count('\n'), 'routes', len(re.findall(r'@app\.(get|post|put|delete|websocket)\(', t)), 'functions', len(re.findall(r'^(async )?def ', t, re.M)))
print('== frontend/src/pages/ChatPage.vue ==')
t = read('frontend/src/pages/ChatPage.vue')
print('lines', t.count('\n'), 'script setup imports', len(re.findall(r'^import ', t, re.M)), 'refs', len(re.findall(r'\bref\(', t)))
