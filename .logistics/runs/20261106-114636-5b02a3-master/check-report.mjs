import fs from 'node:fs';
const md = fs.readFileSync('report.md', 'utf8');
const v = JSON.parse(fs.readFileSync('verdict.json', 'utf8'));
function sec(name) {
  const a = md.indexOf('## ' + name);
  if (a < 0) return '';
  const b = md.indexOf('\n## ', a + 3);
  return md.slice(a, b < 0 ? undefined : b);
}
const direct = sec('可直接修');
const plan = sec('需方案');
const count = (s) => s.split('\n').filter((l) => /^- (COR|SEC|REL|MNT|PRF)-[A-F0-9]{5,8} /.test(l)).length;
console.log('direct lines', count(direct), 'plan', count(plan));
const miss = [];
for (const f of v.findings) {
  const re = new RegExp('^- \\[' + f.severity + '\\] \\[' + f.unit + '\\] \\[' + f.kind + '\\] ' + f.id + ' —', 'm');
  if (!re.test(md)) miss.push(f.id);
}
console.log('missing finding lines', miss.length, miss.slice(0, 5));
console.log('headings:', md.split('\n').filter((l) => l.startsWith('## ')).map((l) => l.slice(3)).join(' | '));
console.log('chars', md.length);
