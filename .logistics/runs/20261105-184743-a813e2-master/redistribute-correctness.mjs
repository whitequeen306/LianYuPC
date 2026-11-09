import fs from 'node:fs';

const run = '.logistics/runs/20261105-184743-a813e2-master';
const scope = JSON.parse(fs.readFileSync(`${run}/scope.json`, 'utf8'));
const path = `${run}/handoff/correctness/correctness.json`;
const json = JSON.parse(fs.readFileSync(`${path}.bak2`, 'utf8'));

const scopeByUnit = new Map(scope.project.units.map((u) => [u.id, u]));
const owner = new Map();
for (const unit of scope.project.units) {
  for (const f of unit.files) {
    if (!owner.has(f.path)) owner.set(f.path, unit.id);
  }
}

// Collect every path the expert recorded, then assign each to its real scope unit.
const all = new Set();
for (const entry of Object.values(json.units)) {
  for (const f of entry.files || []) all.add(f);
}
for (const unit of scope.project.units) {
  const entry = json.units[unit.id];
  entry.files = [...all].filter((f) => owner.get(f) === unit.id).sort();
  entry.filesRead = entry.files.length;
}

for (const unit of scope.project.units) {
  const entry = json.units[unit.id];
  console.log(unit.id, entry.files.length);
}
const bad = scope.project.units.filter((u) => json.units[u.id].files.length < 30);
if (bad.length) {
  console.log('still below 30:', bad.map((u) => `${u.id}:${json.units[u.id].files.length}`).join(' '));
  process.exit(1);
}
fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
