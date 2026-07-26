import paramiko, os
c = paramiko.SSHClient()
c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
c.connect('156.233.228.18', 22, 'root', os.environ['DEPLOY_SSH_PASSWORD'])
sftp = c.open_sftp()
sftp.put(r'C:\Users\hp\Desktop\LianYu-PC\backend\lianyu-app\src\main\resources\application.yml',
         '/opt/lianyu/backend/lianyu-app/src/main/resources/application.yml')
sftp.close()
i, o, e = c.exec_command('grep -c "server:" /opt/lianyu/backend/lianyu-app/src/main/resources/application.yml')
print("server count:", o.read().decode().strip())
c.close()
