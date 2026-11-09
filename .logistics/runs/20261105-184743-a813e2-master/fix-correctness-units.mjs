import fs from 'node:fs';

const run = '.logistics/runs/20261105-184743-a813e2-master';
const scope = JSON.parse(fs.readFileSync(`${run}/scope.json`, 'utf8'));
const path = `${run}/handoff/correctness/correctness.json`;
const json = JSON.parse(fs.readFileSync(path, 'utf8'));

for (const unit of scope.project.units) {
  const entry = json.units?.[unit.id];
  if (!entry || !Array.isArray(entry.files)) continue;
  const allowed = new Set(unit.files.map((f) => f.path));
  const kept = entry.files.filter((f) => allowed.has(f));
  const dropped = entry.files.filter((f) => !allowed.has(f));
  if (dropped.length) console.log(unit.id, 'dropped', dropped.length, dropped.join(', '));
  entry.files = kept;
  entry.filesRead = kept.length;
}
fs.copyFileSync(path, `${path}.bak2`);
fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
console.log('units', Object.entries(json.units).map(([k, v]) => `${k}:${v.files.length}`).join(' '));
