import { createHash } from 'node:crypto';
import { readFile, readdir, writeFile } from 'node:fs/promises';

const output = new URL('../dist/mobile/browser/', import.meta.url);
const files = (await readdir(output)).filter(name => /\.(js|css|html)$/.test(name) && name !== 'sw.js').sort();
const hash = createHash('sha256');
for (const file of files) hash.update(await readFile(new URL(file, output)));
await writeFile(new URL('offline-assets.json', output), JSON.stringify({
  version: hash.digest('hex').slice(0, 16),
  assets: files.map(name => '/' + name),
}));
