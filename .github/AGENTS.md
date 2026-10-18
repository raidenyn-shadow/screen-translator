# CI and release rules

- Root `AGENTS.md` applies. Pin external actions to immutable commit SHAs. PR workflows use read-only permissions and never signing secrets.
- Keep **Semantic policy** required on `main`; run it on PR title edits, commits and label changes as well as main pushes. Pass event data through JSON/environment, never interpolate PR text into shell commands.
- `commitlint.config.mjs` is the naming authority; use the same validation in Husky, CI and automated release commits. Add behavioral tests when changing commit classification or version selection.
- Release selection uses first-parent commits since the current `vMAJOR.MINOR.PATCH` tag, plus associated merged PRs targeting `main`. The exact **Major Feature** label is evaluated at release time; set it before release and retain it for auditability.
- Maintenance-only history is a successful no-op. Fail on invalid history, missing baseline tags or GitHub API errors. Do not silently fall back to patch.
- Publish only after both variants' tests/lint, signed APK verification and both required statuses pass on the exact candidate commit. Keep version/tag push atomic, preserve recovery artifacts, and clean the temporary keystore and candidate branch.
