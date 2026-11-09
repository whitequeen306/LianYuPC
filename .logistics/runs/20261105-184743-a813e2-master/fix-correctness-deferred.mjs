import fs from 'node:fs';

const path = '.logistics/runs/20261105-184743-a813e2-master/handoff/correctness/correctness.json';
const json = JSON.parse(fs.readFileSync(path, 'utf8'));
const target = ['maintainability', 'reliability', 'reliability', 'reliability'];
json.deferred = (json.deferred || []).map((item, i) => {
  if (typeof item === 'string' || item.summary) return item;
  return {
    expert: target[i] || '',
    file: '',
    lines: '',
    summary: [item.topic, item.detail].filter(Boolean).join('：'),
  };
});
fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
console.log(json.deferred.map((d) => `${d.expert}: ${d.summary.slice(0, 40)}`).join('\n'));
