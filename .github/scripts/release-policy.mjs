import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { releaseLevel } from './commit-policy.mjs';

export async function selectBump(commits) {
  let selected = null;
  const rank = { patch: 1, minor: 2, major: 3 };
  for (const commit of commits) {
    const level = await releaseLevel(commit.message);
    const majorFeature = (commit.pulls ?? []).some(pr => pr.merged_at &&
      pr.base?.ref === 'main' && pr.labels.some(label => label.name === 'Major Feature'));
    const candidate = majorFeature ? 'major' : level;
    if ((rank[candidate] ?? 0) > (rank[selected] ?? 0)) selected = candidate;
  }
  return selected;
}

export function git(...args) {
  return execFileSync('git', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).trim();
}

export function associatedPulls(sha, repository = process.env.GITHUB_REPOSITORY) {
  if (!repository) throw new Error('GITHUB_REPOSITORY is required');
  const pages = JSON.parse(execFileSync('gh', ['api', '--paginate', '--slurp',
    `repos/${repository}/commits/${sha}/pulls`], { encoding: 'utf8' }));
  return pages.flat();
}

async function main() {
  const source = readFileSync('app/build.gradle.kts', 'utf8');
  const version = source.match(/^\s*versionName\s*=\s*"((?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*))"\s*$/m)?.[1];
  if (!version) throw new Error('Missing stable SemVer versionName');
  const baseline = `v${version}`;
  git('rev-parse', '--verify', `refs/tags/${baseline}^{commit}`);
  git('merge-base', '--is-ancestor', baseline, 'HEAD');
  const shas = git('rev-list', '--reverse', '--first-parent', `${baseline}..HEAD`).split('\n').filter(Boolean);
  const commits = shas.map(sha => ({ message: git('show', '-s', '--format=%B', sha), pulls: associatedPulls(sha) }));
  console.log(await selectBump(commits) ?? 'none');
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  await main();
}
