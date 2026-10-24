/**
 * logger 的「stdout 熔断」回归测试。
 *
 * 真实事故（2026-10-24）：从父进程管道已关闭的会话启动 App 时，
 * 每次 console.log 抛 EPIPE；全局 uncaughtException 又用 logger.error 打日志，
 * 于是无限递归把主进程卡死（IPC 全部超时，表现为「角色操作没反应」）。
 */
import { describe, it, expect, vi, beforeEach, afterAll } from 'vitest'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

const { logDir } = vi.hoisted(() => {
  const nodeFs = require('node:fs')
  const nodeOs = require('node:os')
  const nodePath = require('node:path')
  return { logDir: nodeFs.mkdtempSync(nodePath.join(nodeOs.tmpdir(), 'ly-logger-')) }
})

vi.mock('electron', () => ({ app: { getPath: () => logDir } }))

const { log } = await import('../logger.js')
const logFile = path.join(logDir, 'logs', 'app.log')

describe('logger stdout 熔断', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    try { fs.rmSync(path.join(logDir, 'logs'), { recursive: true, force: true }) } catch { /* ignore */ }
  })

  afterAll(() => {
    try { fs.rmSync(logDir, { recursive: true, force: true }) } catch { /* ignore */ }
  })

  it('正常写入文件', () => {
    vi.spyOn(console, 'log').mockImplementation(() => {})
    log('INFO', 'unit', 'hello')
    expect(fs.readFileSync(logFile, 'utf8')).toContain('hello')
  })

  it('stdout 抛 EPIPE 时不抛异常，且之后不再尝试写 stdout', () => {
    const spy = vi.spyOn(console, 'log').mockImplementation(() => {
      const err = new Error('EPIPE: broken pipe, write')
      err.code = 'EPIPE'
      throw err
    })

    expect(() => log('INFO', 'unit', 'first')).not.toThrow()
    // 文件里仍然有日志
    expect(fs.readFileSync(logFile, 'utf8')).toContain('first')

    expect(() => log('INFO', 'unit', 'second')).not.toThrow()
    // 熔断后只尝试过一次 stdout 镜像
    expect(spy).toHaveBeenCalledTimes(1)
    expect(fs.readFileSync(logFile, 'utf8')).toContain('second')
  })
})
