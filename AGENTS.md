# ResForge contributor instructions

## Start here

- Read `docs/AI-CONTEXT.md` before making substantive changes. It is the curated
  architecture, build, feature, convention, and open-work primer.
- Use `docs/DESIGN-notes.md` for detailed reverse-engineering history and
  `kb/notes/` for atomic format facts and design decisions.
- Treat notes as guidance, not proof: verify relevant claims against the current
  checkout before changing code.

## Keep knowledge synchronized

Keep documentation in the same commit as the code it describes:

- Update `docs/AI-CONTEXT.md` when architecture, features, builds, conventions,
  or next steps materially change.
- Append durable format discoveries and experiment results under `kb/notes/`,
  using one descriptive heading per independently searchable fact.
- Update `docs/DESIGN-notes.md` when the detailed design or reverse-engineering
  history changes.
- Update `README.md` and `CHANGELOG.md` for user-visible behavior.
- Apply dependency and build changes consistently to Gradle, Maven, and Ant
  documentation and configuration.

Do not mechanically edit every document for every commit. Update only the
documents whose claims changed, but treat stale documentation as a defect.

## Core engineering constraints

- Preserve byte-identical handling of unchanged resource layers.
- Keep typed editing lossless-or-raw: expose it only when decode and re-encode
  reproduce the original bytes.
- Keep changes narrowly scoped and add focused regression tests for defects.
- Never commit copyrighted game resource samples; local `samples/` are
  gitignored validation inputs.
- For build or dependency changes, validate Gradle, Maven, and Ant parity.

## Git policy

- Never commit or push unless the user explicitly requests it.
- Never amend commits or otherwise rewrite history unless explicitly requested.
- Keep unrelated user changes out of commits; stage only files belonging to the
  requested task.
- Before committing, inspect the staged changes and verify that no unintended
  files are included.
- Use an imperative commit subject and explain the reason for the change in the
  commit body.

## Commit messages

- Every AI-assisted commit must identify the active model in its own paragraph,
  immediately before the `Co-authored-by` trailer. Use the model's display name
  in this exact format, for example: `Powered by GPT 5.6 Sol`.
- Always include:
  `Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>`.
