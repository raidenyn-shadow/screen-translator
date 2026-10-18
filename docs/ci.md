# GitHub CI and releases

## Commit and pull-request policy

Run `npm ci` with Node.js 22.12+ after cloning. It installs Husky's `commit-msg`
hook; every local commit is checked by `commitlint.config.mjs`. Use
`type(scope)!: description` (scope and `!` optional), at most 100 characters.
Types: `feat`, `fix`, `perf`, `refactor`, `docs`, `test`, `build`, `ci`,
`chore`, `style`, `revert`. Scope characters are lowercase letters, digits,
dot, underscore, slash and hyphen. PR titles use the same single-line format.

The required **Semantic policy** CI check validates the PR title and every new
commit, rerunning on title edits, commits and label changes. Rebase topic branches
instead of adding merge commits. GitHub permits squash merges only, defaults the
squash subject to the PR title and its body to the PR description; keep that
validated title when merging. The policy workflow also validates new `main`
commits. GitHub rejected the commit-message metadata restriction for this
repository, so server-side protection is the required PR check, squash-only
merging and linear history, rather than a commit-subject regex gate. A manually
edited squash subject is checked by CI after merge; retain the PR-title default.

Breaking changes use a subject `!` or `BREAKING CHANGE:` / `BREAKING-CHANGE:`
footer. Preserve the marker in the PR title/body for the resulting squash commit.
Test tooling with `npm test` and the Python versioning test command below.

## Pull requests

`Android CI` runs on PR creation, reopening, every new commit, merge-queue
updates, and pushes to `main`. The required Android check is **Tests and lint**. It runs:

```sh
python3 -m unittest discover -s .github/scripts -p 'test_*.py'
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease --no-daemon
```

Both Android variants are covered. There are currently no instrumented tests or
additional Kotlin lint tools configured. Reports are retained for 14 days,
including when a check fails.

The `main: required CI` GitHub ruleset requires passing **Tests and lint** and
**Semantic policy** checks from GitHub Actions and an up-to-date branch before merging. It blocks
force pushes and deletion, with no bypass actors. The release workflow records
a successful commit status on its exact tested release candidate before pushing
to `main`, satisfying the same requirement. Keep write permissions restricted
to trusted workflows. PR workflows use a read-only token and do not receive
signing secrets.

The ruleset definition is in [`.github/main-ruleset.json`](../.github/main-ruleset.json).
For a new repository, an administrator can create it with:

```sh
gh api --method POST repos/OWNER/REPO/rulesets --input .github/main-ruleset.json
```

For an existing ruleset, use `PUT repos/OWNER/REPO/rulesets/RULESET_ID` instead.

## Debug APKs

Add the exact label **build apk** to a PR. `PR debug APK` builds immediately and
on subsequent commits while the label remains. Download the APK from that
workflow run's **Artifacts** section. Artifacts are retained for 14 days.
Removing the label prevents future builds; it does not delete existing artifacts.
Fork PRs use GitHub's normal workflow approval policy.

## Release signing setup

In **Settings → Secrets and variables → Actions**, create these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | Base64 encoding of the existing app's signing keystore |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Alias of the existing signing key |
| `RELEASE_KEY_PASSWORD` | Signing key password |

Use the existing certificate so APKs can update installed versions. Upload the
base64 value directly as a secret; do not commit the keystore. All four secrets
must be nonempty. No additional personal access token is needed.

## Publish a release

After the workflow changes are merged into `main`:

1. Open **Actions → Release APK → Run workflow** and choose **main**.
2. The workflow examines first-parent commits since the current version tag and
   their associated merged PRs targeting `main`. The exact PR label **Major
   Feature** or a breaking change selects major; `feat` selects minor;
   `fix`/`perf`/`revert` select patch. Highest increment wins, including for `0.x`.
   Other types alone cause a successful no-op. Labels are evaluated at release
   time; retain the label for auditability. Major/minor reset lower components;
   `versionCode` increments once (for example, `21`/`0.8.2` becomes `22`/`1.0.0`
   for a major release). No manual version bumps.
3. It commits the version bump on a temporary release-candidate branch, runs
   both unit-test suites and both Android lint variants, builds the
   optimized release APK, aligns it, signs it, and verifies the signature.
4. It records successful **Tests and lint** and **Semantic policy** statuses from GitHub Actions,
   atomically pushes the version-bump commit to `main` and the matching
   annotated `vVERSION` tag, then publishes a GitHub Release with generated notes
   and `screen-translator-VERSION.apk` attached. It then deletes the temporary
   release-candidate branch.

Dispatching from another branch skips the release job. Release runs are
serialized with at most one running and one pending run; a newer dispatch can
replace an already-pending run. Each run with release-worthy changes publishes the
selected next version; maintenance-only runs publish nothing. If
`main` advances during a build, the non-fast-forward push fails rather than
overwriting changes; start a fresh run. Failed builds/signing do not push a
version bump or tag to `main`. The release workflow checks its bumped source itself;
GitHub does not start another CI run for commits pushed with `GITHUB_TOKEN`.

If publication fails after the atomic push, download the signed APK from the
run's recovery artifact. If the release does not exist, publish the existing tag
with `gh release create vVERSION screen-translator-VERSION.apk --verify-tag
--generate-notes`. If the release exists but its upload failed, attach the APK
with `gh release upload vVERSION screen-translator-VERSION.apk --clobber`.
Do not rerun the entire workflow to recover the same version: after the atomic
version/tag push it normally becomes a no-op, or publishes another version if
new release-worthy commits arrived. It does not repair the failed publication.
Check whether a release was already created before recovery.
