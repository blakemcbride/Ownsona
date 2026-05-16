@AI/KnowledgeBase.md

@AI/ApplicationDetails.md

---

## Maintenance directive

**This file (`CLAUDE.md`) and the files it `@`-includes are
load-bearing context for every future Claude session. Keep them
current.**

- `AI/KnowledgeBase.md` is the Kiss framework reference. Do not edit
  it for project-specific notes.
- `AI/ApplicationDetails.md` is the project-specific operational
  guide for OwnSona. Update it in the same commit whenever a design
  invariant changes, a new convention emerges, or operational
  practice shifts.
- This file (`CLAUDE.md`) itself rarely needs editing; it just
  pulls the others in. If the include set changes, update it.

**Do not record history in any of these files.** Per-phase commit
logs, dates of past deploys, "we tried X and reverted" notes — all
of that belongs in `git log`, in commit messages, or in
`OwnSona-rollout-plan.md`'s Implementation Status table. Putting it
here would make the file grow unboundedly and would drift out of
sync with reality.

Stale guidance is worse than no guidance: future Claude sessions
will *act on* what's written here. If something in this file or its
includes contradicts the current code or the rollout plan, fix the
file before acting on it.
