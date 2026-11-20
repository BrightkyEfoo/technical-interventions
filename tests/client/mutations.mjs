import { mkdtemp, mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const cases = [{
  file: 'offline.ts',
  test: 'offline.test.mjs',
  from: 'return draft.attempted || draft.baseVersion === currentVersion;',
  to: 'return draft.baseVersion === currentVersion;',
  name: 'bloquer à tort la relance après perte de réponse',
}, {
  file: 'offline.ts',
  test: 'offline.test.mjs',
  from: 'return { ...draft, baseVersion: version, key, attempted: false };',
  to: 'return { ...draft, report: "", baseVersion: version, key, attempted: false };',
  name: 'effacer le texte lors de la résolution du conflit',
}];

const scratch = await mkdtemp(join(tmpdir(), 'serviceops-mutations-'));
try {
  await mkdir(join(scratch, 'apps/shared'), { recursive: true });
  await mkdir(join(scratch, 'tests/client'), { recursive: true });
  await symlink(resolve('node_modules'), join(scratch, 'node_modules'));
  for (const spec of cases) {
    const original = await readFile('apps/shared/' + spec.file, 'utf8');
    if (!original.includes(spec.from)) throw new Error('Mutation introuvable pour ' + spec.name);
    await writeFile(join(scratch, 'apps/shared', spec.file), original.replace(spec.from, spec.to));
    await writeFile(join(scratch, 'tests/client', spec.test), await readFile('tests/client/' + spec.test));
    const result = spawnSync(process.execPath, ['--experimental-strip-types', '--test', join(scratch, 'tests/client', spec.test)], { encoding: 'utf8' });
    if (result.status !== 1 || !result.stdout.includes('AssertionError')) {
      throw new Error('Le test ne détecte pas le défaut : ' + spec.name);
    }
    console.log('Défaut détecté · ' + spec.name);
  }
} finally {
  await rm(scratch, { recursive: true, force: true });
}
