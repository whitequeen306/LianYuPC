import { test } from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { passwordHash, readQrcodeImage, webuiBase } from '../src/napcatWebui.js'

test('passwordHash 与 NapCat 内部 generatePasswordHash 同口径', () => {
  const expected = crypto.createHash('sha256').update('lianyupc.napcat').digest('hex')
  assert.equal(passwordHash('lianyupc'), expected)
  assert.equal(passwordHash('a'), crypto.createHash('sha256').update('a.napcat').digest('hex'))
})

test('webuiBase 指向本机分配端口', () => {
  assert.equal(webuiBase({ webuiPort: 31005 }), 'http://127.0.0.1:31005')
})

test('readQrcodeImage 缺文件/空文件返回 null，有内容返回 Buffer', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gw-qr-'))
  assert.equal(readQrcodeImage(dir), null)

  fs.mkdirSync(path.join(dir, 'cache'), { recursive: true })
  assert.equal(readQrcodeImage(dir), null)

  fs.writeFileSync(path.join(dir, 'cache', 'qrcode.png'), '')
  assert.equal(readQrcodeImage(dir), null)

  const png = Buffer.from([0x89, 0x50, 0x4e, 0x47])
  fs.writeFileSync(path.join(dir, 'cache', 'qrcode.png'), png)
  assert.deepEqual(readQrcodeImage(dir), png)
})
