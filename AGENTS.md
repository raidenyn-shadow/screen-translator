# Repository rules

- Run `npm ci` after cloning; Node.js 22.12+ is required for repository tooling. Husky's `commit-msg` hook validates every local commit with commitlint. Do not bypass hooks.
- Commit subjects and PR titles use `type(scope)!: description`; scope and `!` are optional. Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`, `chore`, `style`, `revert`. Subject length: at most 100 characters. Scopes use lowercase letters/digits and `._/-`; descriptions are nonempty. PR titles are single-line.
- Use `!` or a `BREAKING CHANGE:` / `BREAKING-CHANGE:` footer for breaking changes. Keep the marker in the PR title/body so the squash commit preserves it.
- PRs must pass **Semantic policy** and **Tests and lint**. Use squash merge only; the squash subject must equal the validated PR title. Rebase topic branches rather than introducing nonconventional merge commits.
- Releases are manually dispatched on `main`. Since the current version tag: exact PR label **Major Feature** or a breaking change → major; `feat` → minor; `fix`/`perf`/`revert` → patch; other types alone → no release. Highest increment wins, including for `0.x`; major/minor reset lower components. Android `versionCode` increases once per published version.
- Do not manually bump versions. Keep signing keys/secrets out of Git. Preserve the current release certificate.
- Verify tooling with `npm test` and `python3 -m unittest discover -s .github/scripts -p 'test_*.py'`. Android changes also require both unit-test suites and both lints (`JAVA_HOME=$JAVA_ROOT/zulu21 ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease`).
