import fs from 'node:fs';

const run = '.logistics/runs/20261105-184743-a813e2-master';
const handoffs = ['correctness', 'reliability', 'maintainability', 'security'];

for (const name of handoffs) {
  const path = `${run}/handoff/${name}/${name}.json`;
  const json = JSON.parse(fs.readFileSync(path, 'utf8'));
  const before = JSON.stringify(json);

  if (json.sections) {
    for (const [key, value] of Object.entries(json.sections)) {
      if (value && typeof value === 'object') json.sections[key] = value.count;
    }
  }

  if (name === 'correctness') {
    const drop = {
      u03: 'frontend/src/pages/ChatPage.vue',
      u04: 'scripts/voice_candidates.py',
      u07: 'frontend/src/components/AppHeader.vue',
    };
    for (const [unit, file] of Object.entries(drop)) {
      const entry = json.units?.[unit];
      if (!entry || !Array.isArray(entry.files)) continue;
      const kept = entry.files.filter((item) => item !== file);
      if (kept.length !== entry.files.length) {
        entry.files = kept;
        entry.filesRead = kept.length;
        console.log(`removed ${unit} ${file} -> ${kept.length}`);
      }
    }
  }

  if (name === 'security' && Array.isArray(json.deferred)) {
    json.deferred = json.deferred.map((item) => {
      if (typeof item === 'string' || item.summary) return item;
      return {
        expert: item.item && item.item.includes('FileStorageService') ? 'correctness' : '',
        file: '',
        lines: '',
        summary: [item.item, item.reason].filter(Boolean).join(' '),
      };
    });
  }

  if (JSON.stringify(json) !== before) {
    fs.copyFileSync(path, `${path}.bak`);
    fs.writeFileSync(path, JSON.stringify(json, null, 2) + '\n');
  }
  console.log(name, JSON.stringify(json.sections));
}
