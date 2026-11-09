import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
const { renderMarkdown } = await import(pathToFileURL('C:/Users/hp/.dsh/profiles/desktop/node_modules/dsh-logistics/lib/report.js').href)

const runDir = 'C:/Users/hp/Desktop/LianYu-PC/.logistics/runs/20261105-184743-a813e2-master'
const reportPath = `${runDir}/report.json`
const latestPath = 'C:/Users/hp/Desktop/LianYu-PC/docs/logistics/latest.md'
const report = JSON.parse(readFileSync(reportPath, 'utf8'))
const drop = new Set(['REL-E07C8', 'MNT-F7190'])

report.findings = report.findings.filter((finding) => !drop.has(finding.id))
report.fixOrder = report.fixOrder.filter((finding) => !drop.has(finding.id))

for (const module of report.modules) {
  module.findings = module.findings.filter((finding) => !drop.has(finding.id))
  const counts = { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
  for (const finding of module.findings) counts[finding.severity] += 1
  module.counts = counts
  module.experts = [...new Set(module.findings.map((finding) => finding.expert))]
}
report.modules = report.modules.filter((module) => module.findings.length > 0)

const findings = report.findings
const bySeverity = { critical: 0, high: 0, medium: 0, low: 0, info: 0 }
const byExpert = { correctness: 0, security: 0, reliability: 0, maintainability: 0 }
const byModule = {}
for (const finding of findings) {
  bySeverity[finding.severity] += 1
  byExpert[finding.expert] = (byExpert[finding.expert] ?? 0) + 1
  const module = report.modules.find((item) => item.findings.some((ref) => ref.id === finding.id))
  if (module) byModule[module.module] = (byModule[module.module] ?? 0) + 1
}
report.counts = {
  total: findings.length,
  direct: findings.filter((finding) => finding.kind === 'direct').length,
  plan: findings.filter((finding) => finding.kind === 'plan').length,
  lowConfidence: findings.filter((finding) => finding.confidence === 'low').length,
  bySeverity,
  byExpert,
  byModule,
}
report.summary = `本次发现 ${findings.length} 项问题（${bySeverity.critical + bySeverity.high} 项高优先级），集中在 backend-agent、backend 模块；性能维度未运行。`

writeFileSync(reportPath, `${JSON.stringify(report, null, 2)}\n`, 'utf8')
writeFileSync(latestPath, renderMarkdown(report), 'utf8')
console.log(`findings=${findings.length} direct=${report.counts.direct} plan=${report.counts.plan}`)
console.log(JSON.stringify(report.counts.bySeverity))
