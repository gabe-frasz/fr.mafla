# Mafla

Minimal Android launcher (Kotlin, View system). Forked from Olauncher; no longer tracks upstream.

## Project rules

- Don't run Gradle builds locally: the development machine can't handle them. GitHub CI (`.github/workflows/build.yml`) builds and runs the tests on every push to `main` and on every pull request.
- User-facing strings live in English (`values/`) and Brazilian Portuguese (`values-pt-rBR/`). Everything else in the repo (code, comments, docs, commits) is in English.

## Agent skills

### Issue tracker

Issues live in GitHub Issues on gabe-frasz/fr.mafla, via `gh`. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default labels: needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
