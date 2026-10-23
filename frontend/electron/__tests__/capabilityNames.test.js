import { describe, it, expect } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { CAPABILITY_NAMES, capabilityManifest } from '../capability/capabilityNames.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const PY_SPECS = path.resolve(__dirname, '../../../backend-agent/app/capability/specs.py')

describe('capability names', () => {
  it('matches the Python source of truth (no silent drift)', () => {
    const source = fs.readFileSync(PY_SPECS, 'utf8')
    const pyNames = [...source.matchAll(/_spec\(\s*\n?\s*"(desk_[a-z_]+)"/g)].map((m) => m[1])
    expect(pyNames.length).toBeGreaterThan(0)
    expect([...pyNames].sort()).toEqual([...CAPABILITY_NAMES].sort())
  })

  it('builds a manifest the bridge accepts (names only, legal charset)', () => {
    const manifest = capabilityManifest()
    expect(manifest).toHaveLength(CAPABILITY_NAMES.length)
    for (const item of manifest) {
      expect(item.name).toMatch(/^[A-Za-z0-9_-]{1,64}$/)
      expect(item.dangerous).toBe(false)
      expect(item.inputSchema.type).toBe('object')
    }
  })
})
