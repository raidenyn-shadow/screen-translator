export default {
  extends: ['@commitlint/config-conventional'],
  defaultIgnores: false,
  rules: {
    'header-max-length': [2, 'always', 100],
    'subject-case': [0],
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
    'scope-case': [2, 'always', 'lower-case'],
    'scope-enum': [0],
    'breaking-change-exclamation-mark': [0],
    'scope-format': [2, 'always'],
    'header-format': [2, 'always'],
  },
  plugins: [{
    rules: {
      'header-format': ({ header }) => [
        /^(feat|fix|perf|refactor|docs|test|build|ci|chore|style|revert)(\([a-z0-9][a-z0-9._/-]*\))?!?: [^ \r\n][^\r\n]*$/.test(header ?? ''),
        'header must use type(scope)!: description with optional nonempty scope and breaking marker',
      ],
      'scope-format': ({ scope }) => [
        !scope || /^[a-z0-9][a-z0-9._/-]*$/.test(scope),
        'scope must contain only lowercase letters, digits, dot, underscore, slash or hyphen',
      ],
    },
  }],
};
