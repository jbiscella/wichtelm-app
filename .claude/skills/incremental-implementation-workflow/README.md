# incremental-implementation-workflow

A workflow skill for Claude Code that enforces BDD/TDD discipline when implementing increments specified in a `CLAUDE.md`. Codifies prereq → red → green → refactor execution, test failure classification, ADR primacy, drift detection (including retroactive drift on increments marked `done`), and the rules for stopping and reporting ambiguity instead of proceeding on assumptions.

## When to install

**Always.** On any project where Claude Code writes or modifies code under a spec.

This is the foundation skill. The other skills in this family compose on top of it — they assume the workflow it defines is in effect.

## What it actually catches

Two concrete examples of bugs this skill would have caught at implementation time:

- A scenario in a Gherkin feature file asserts a failure count but does not actually require the production code to have implemented the failure-handling behavior. The skill's "red discipline" rule (§4) forces verifying the scenario fails for the right reason — without the implementation, the scenario must turn red. A scenario that passes regardless of the implementation is dead coverage and gets flagged.
- A spec lists an increment scenario for transient retry, but the implementation never adds a retry. The skill's "definition of done" (§10) requires a test per scenario; the missing test would have made the increment fail DoD instead of being marked done.

## When this skill might add less value

- Pure exploratory spikes with no spec to enforce. The skill assumes a `CLAUDE.md` with increments exists.
- Codebases without any test infrastructure. The skill expects a test runner to exist; setting one up is outside its scope.

## Installation

In a Claude Code project:

```bash
unzip incremental-implementation-workflow.skill -d .claude/skills/
```

For global use across all projects, replace `.claude/skills/` with `~/.claude/skills/`.

## Composability

This skill defines the workflow only. Stack-specific idioms (annotations, build tools, cloud diagnostics) belong in companion skills that load alongside this one. The workflow rules apply regardless of stack.
