import assert from 'node:assert/strict';
import { test } from 'node:test';
import { validateMessage, validateTitle, releaseLevel } from './commit-policy.mjs';
import { selectBump } from './release-policy.mjs';

test('validates ordinary, scoped and breaking commit subjects', async () => {
  for (const text of ['feat: add capture', 'fix(lens): restore access', 'feat!: change consent',
    'chore(release): 1.0.0', 'ci: validate PR titles']) {
    assert.equal(await validateMessage(text), true, text);
  }
});

test('rejects invalid, empty, merge and fixup subjects', async () => {
  for (const text of ['', 'Update capture', 'feature: add capture', 'fix: ',
    'Merge pull request #2', 'fixup! fix: repair capture', 'FIX: repair', 'fix(bad scope): repair',
    'fix(): repair', 'fix:  leading space']) {
    assert.equal(await validateMessage(text), false, text);
  }
});

test('PR titles must be a single conventional header', async () => {
  assert.equal(await validateTitle('feat(capture)!: change consent'), true);
  assert.equal(await validateTitle('feat: add capture\n\nbody'), false);
  assert.equal(await validateTitle('fix: repair\rmalformed'), false);
  assert.equal(await validateTitle('fix: ' + 'a'.repeat(96)), false);
});

test('uses breaking notes or exclamation marks, not prose mentions', async () => {
  assert.equal(await releaseLevel('feat!: change capture'), 'major');
  assert.equal(await releaseLevel('fix: change capture\n\nBREAKING CHANGE: consent must be renewed'), 'major');
  assert.equal(await releaseLevel('fix: repair\n\nBREAKING-CHANGE: consent changed'), 'major');
  assert.equal(await releaseLevel('docs: explain\n\nMention BREAKING CHANGE in documentation.'), null);
});

test('selects the highest release level and skips maintenance-only history', async () => {
  assert.equal(await selectBump([]), null);
  assert.equal(await selectBump([{ message: 'ci: improve checks' }, { message: 'docs: explain setup' }]), null);
  assert.equal(await selectBump([{ message: 'fix: repair capture' }, { message: 'perf: reduce allocation' }]), 'patch');
  assert.equal(await selectBump([{ message: 'revert: remove regression' }]), 'patch');
  assert.equal(await selectBump([{ message: 'fix: repair' }, { message: 'feat: add capture mode' }]), 'minor');
  assert.equal(await selectBump([{ message: 'feat: add mode' }, { message: 'fix!: change contract' }]), 'major');
});

test('Major Feature label overrides any conventional type and requires a merged main PR', async () => {
  const pr = { merged_at: '2026-10-18T00:00:00Z', base: { ref: 'main' }, labels: [{ name: 'Major Feature' }] };
  assert.equal(await selectBump([{ message: 'refactor: reorganize capture', pulls: [pr] }]), 'major');
  for (const other of [{ ...pr, merged_at: null }, { ...pr, base: { ref: 'other' } },
    { ...pr, labels: [{ name: 'major feature' }] }]) {
    assert.equal(await selectBump([{ message: 'refactor: reorganize capture', pulls: [other] }]), null);
  }
});

test('invalid history fails instead of silently selecting no release', async () => {
  await assert.rejects(selectBump([{ message: 'invalid title' }]));
});
