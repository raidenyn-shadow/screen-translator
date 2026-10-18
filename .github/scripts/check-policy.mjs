import { readFileSync } from 'node:fs';
import { validateMessage, validateTitle } from './commit-policy.mjs';
import { git } from './release-policy.mjs';

async function check(message, title = false) {
  if (!await (title ? validateTitle(message) : validateMessage(message))) {
    throw new Error(`Invalid ${title ? 'PR title' : 'commit message'}: ${message.split('\n')[0]}`);
  }
}

if (process.argv.includes('--head')) {
  await check(git('show', '-s', '--format=%B', 'HEAD'));
} else {
  const event = JSON.parse(readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8'));
  let range;
  if (event.pull_request) {
    await check(event.pull_request.title, true);
    range = `${event.pull_request.base.sha}..${event.pull_request.head.sha}`;
  } else if (event.merge_group) {
    range = `${event.merge_group.base_sha}..${event.merge_group.head_sha}`;
  } else if (event.before && !/^0+$/.test(event.before)) {
    range = `${event.before}..${event.after}`;
  }
  const shas = range ? git('rev-list', '--reverse', ...(event.merge_group ? ['--no-merges'] : []), range)
    .split('\n').filter(Boolean) : ['HEAD'];
  for (const sha of shas) await check(git('show', '-s', '--format=%B', sha));
}
console.log('Semantic policy passed');
