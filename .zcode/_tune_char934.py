# -*- coding: utf-8 -*-
"""临时调角色 934 的主动发言行为（测试用，测完回滚）+ 清会话 884 冷却。
SQL 经文件走 stdin，避免反引号穿过双层 shell 被命令替换。"""
import json
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
BACKUP = Path(__file__).with_name('char934_settings_backup.json')
REMOTE_SQL = '/tmp/_diary_tune.sql'

envf = ROOT / '.env'
if envf.is_file():
    for line in envf.read_text(encoding='utf-8').splitlines():
        line = line.strip()
        if line and not line.startswith('#') and '=' in line:
            k, v = line.split('=', 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"'))

pw = os.environ['DEPLOY_SSH_PASSWORD']
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('156.233.228.18', username='root', password=pw, timeout=30)


def sh(command, timeout=30):
    _, o, e = c.exec_command(command, timeout=timeout)
    return o.read().decode('utf-8', 'replace'), e.read().decode('utf-8', 'replace')


def put_sql(text):
    sftp = c.open_sftp()
    with sftp.open(REMOTE_SQL, 'wb') as f:
        f.write(text.encode('utf-8'))
    sftp.close()


def run_sql(text):
    put_sql(text)
    return sh('docker exec -i lianyu-mysql sh -c '
              '\'mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" -N -B lianyu\' < ' + REMOTE_SQL)


# 1. 读原 settings
out, err = run_sql('SELECT settings FROM `character` WHERE id=934;')
settings_raw = out.strip()
err_real = chr(10).join(l for l in err.strip().splitlines() if 'Using a password' not in l)
if not settings_raw or err_real:
    print('FAIL read:', repr(settings_raw[:200]), err[:300])
    sys.exit(1)
BACKUP.write_text(settings_raw, encoding='utf-8')
print('ORIGIN chatBehavior:', json.dumps(json.loads(settings_raw).get('chatBehavior'), ensure_ascii=False))

# 2. 注入临时覆盖并写回（JSON 用单引号 Q-转义防注入引号冲突）
data = json.loads(settings_raw)
cb = data.get('chatBehavior') or {}
cb.update({'proactiveEnabled': True, 'minIdleMinutes': 3, 'triggerProbability': 1.0})
data['chatBehavior'] = cb
new_settings = json.dumps(data, ensure_ascii=False)
update_sql = 'UPDATE `character` SET settings=%s WHERE id=934;\n' % json.dumps(new_settings)
out, err = run_sql(update_sql)
if err.strip():
    print('UPDATE stderr:', err[:300])

# 3. 清会话 884 的主动发言冷却
out, _ = sh('cd /opt/lianyu && set -a && . ./.env && set +a && '
            'docker exec lianyu-redis sh -c \'redis-cli -a "$REDIS_PASSWORD" --scan --pattern "chat:proactive:*884*"\' 2>/dev/null')
keys = [k for k in out.strip().splitlines() if k]
print('cooldown keys:', keys)
for key in keys:
    sh('cd /opt/lianyu && set -a && . ./.env && set +a && '
       'docker exec lianyu-redis sh -c \'redis-cli -a "$REDIS_PASSWORD" DEL "' + key + '"\' 2>/dev/null')
print('cooldown cleared')

# 4. 验证
out, _ = run_sql('SELECT JSON_EXTRACT(settings, "$.chatBehavior") FROM `character` WHERE id=934;')
print('NOW chatBehavior:', out.strip())
c.close()
print('TUNE_DONE')
