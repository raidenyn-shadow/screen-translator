import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const root = fileURLToPath(new URL('../../', import.meta.url));

test('Husky commit-msg runs the actual commitlint configuration', t => {
  const temp = mkdtempSync(join(tmpdir(), 'commit-hook-'));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const message = join(temp, 'message');
  for (const [text, expected] of [['fix(lens): restore access', 0], ['Update code', 1]]) {
    writeFileSync(message, text + '\n');
    const result = spawnSync('sh', ['.husky/_/commit-msg', message], { cwd: root, encoding: 'utf8' });
    assert.equal(result.status, expected, result.stdout + result.stderr);
  }
});

test('CI validates a PR title and each commit in its range', t => {
  const temp = mkdtempSync(join(tmpdir(), 'semantic-pr-'));
  t.after(() => rmSync(temp, { recursive: true, force: true }));
  const env = { ...process.env, GIT_CONFIG_GLOBAL: '/dev/null',
    GIT_AUTHOR_NAME: 'Test', GIT_AUTHOR_EMAIL: 'test@example.invalid',
    GIT_COMMITTER_NAME: 'Test', GIT_COMMITTER_EMAIL: 'test@example.invalid' };
  function git(...args) {
    return execFileSync('git', ['-C', temp, ...args], { env, encoding: 'utf8' }).trim();
  }
  git('init', '-q');
  writeFileSync(join(temp, 'content'), 'base');
  git('add', 'content');
  git('commit', '-qm', 'chore: baseline');
  const base = git('rev-parse', 'HEAD');
  writeFileSync(join(temp, 'content'), 'fix');
  git('commit', '-qam', 'fix: repair capture');
  const head = git('rev-parse', 'HEAD');
  const eventPath = join(temp, 'event.json');
  function run(title, sha = head) {
    writeFileSync(eventPath, JSON.stringify({ pull_request: { title, base: { sha: base }, head: { sha } } }));
    return spawnSync(process.execPath, ['.github/scripts/check-policy.mjs'], {
      cwd: root, env: { ...env, GITHUB_EVENT_PATH: eventPath, GIT_DIR: join(temp, '.git'), GIT_WORK_TREE: temp },
      encoding: 'utf8',
    });
  }
  assert.equal(run('fix: repair capture').status, 0);
  assert.notEqual(run('Update capture').status, 0);
  writeFileSync(join(temp, 'content'), 'bad');
  git('commit', '-qam', 'Invalid commit');
  assert.notEqual(run('fix: repair capture', git('rev-parse', 'HEAD')).status, 0);
});
