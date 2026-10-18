import lint from '@commitlint/lint';
import load from '@commitlint/load';
import parse from '@commitlint/parse';

const config = await load();
const parserOpts = config.parserPreset?.parserOpts;

export async function validateMessage(message) {
  if (!message.trim()) return false;
  const result = await lint(message, config.rules, {
    parserOpts, plugins: config.plugins, defaultIgnores: false,
  });
  return result.valid;
}

export async function validateTitle(title) {
  return !/[\r\n]/.test(title) && await validateMessage(title);
}

export async function releaseLevel(message) {
  if (!await validateMessage(message)) throw new Error(`Invalid Conventional Commit: ${message.split('\n')[0]}`);
  const parsed = await parse(message, undefined, parserOpts);
  if (parsed.notes.some(note => /BREAKING[ -]CHANGE/.test(note.title)) ||
      /^[a-z]+(?:\([^\r\n)]+\))?!: /.test(parsed.header)) return 'major';
  if (parsed.type === 'feat') return 'minor';
  if (['fix', 'perf', 'revert'].includes(parsed.type)) return 'patch';
  return null;
}
