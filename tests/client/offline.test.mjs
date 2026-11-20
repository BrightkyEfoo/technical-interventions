import assert from 'node:assert/strict';
import test from 'node:test';
import { createDraft, rebase, sendable } from '../../apps/shared/offline.ts';

test('conserve le texte, la base et la clé lors d’une interruption après envoi', () => {
  const draft = { ...createDraft('request', 4, 'stable-key'), report: 'Compte rendu conservé' };
  assert.equal(sendable(draft, 5), false);
  const reopened = JSON.parse(JSON.stringify({ ...draft, attempted: true }));
  assert.equal(sendable(reopened, 5), true);
  assert.equal(reopened.key, 'stable-key');
  const corrected = rebase(reopened, 5, 'new-key');
  assert.equal(corrected.report, draft.report);
  assert.equal(corrected.baseVersion, 5);
  assert.equal(corrected.attempted, false);
});
