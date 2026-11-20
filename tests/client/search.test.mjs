import assert from 'node:assert/strict';
import test from 'node:test';
import { switchMap } from 'rxjs';
import { TestScheduler } from 'rxjs/testing';
import { searchTerms } from '../../apps/shared/search.ts';

test('une requête de recherche lente ne remplace jamais la recherche actuelle', () => {
  const scheduler = new TestScheduler((actual, expected) => assert.deepEqual(actual, expected));
  scheduler.run(({ cold, expectObservable, expectSubscriptions }) => {
    const terms = cold('a----b--------|');
    const old = cold('--------a|');
    const latest = cold('--b|');
    const result = terms.pipe(searchTerms(2, scheduler), switchMap(term => term === 'a' ? old : latest));
    expectObservable(result).toBe('---------b----|');
    expectSubscriptions(old.subscriptions).toBe('--^----!');
  });
});
