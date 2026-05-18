---
name: incremental-implementation-workflow
description: Implement increments specified in a CLAUDE.md file driven by BDD/TDD discipline. Use this skill whenever the user asks you to implement, build, or work on a feature defined as an increment with an "As ..., I want ..." clause and Given/When/Then scenarios. Use it even when the user says only "implement this", "start the next increment", or "pick this up" if the project contains a CLAUDE.md with increments. This skill defines the workflow only; stack-specific idioms (build tools, frameworks, cloud providers) live in companion skills.
---

# Incremental Implementation Workflow

A workflow for taking a behaviorally-specified increment from a CLAUDE.md and implementing it under outside-in discipline. The user defines what needs to exist using BDD; you implement it using a prereq → red → green → refactor cycle, with explicit governance over failure handling and ambiguity.

## When this skill applies

The user has a CLAUDE.md with one or more increments. Each increment has:
- An "As ..., I want ..., so that ..." clause (the value statement)
- One or more Given/When/Then scenarios (the behavior under test)

Sometimes the increment also has operational invariants — non-behavioral rules like "every config value the app reads must be sourced from infrastructure" or "the SDK region must match the resource region". These are not Gherkin scenarios; they are rules. Treat them as a separate verification layer (see §5).

## Spec layers

A CLAUDE.md is not flat. It typically contains six distinct kinds of content, each with its own role and authority. Recognize each layer; do not collapse them.

| Layer | What it contains | Authority |
|---|---|---|
| ADR | Stack choices, architectural decisions (language, framework, database, infra primitives) | **Non-negotiable**. See §11. |
| Structural specs | Data model tables, error catalog, config schemas, item types, attribute constraints | Authoritative shape; deviations are bugs unless approved |
| Behavioral specs | Gherkin GWT scenarios per increment | The primary test target |
| Reference algorithms | Pseudo-code complementing Gherkin where the algorithm is not obvious from the scenarios | Guidance, not literal — idiomatic implementations are expected |
| Code hygiene rules | Cross-cutting constraints (e.g. "BigDecimal everywhere", "Clock always injected", "domain layer must not import infrastructure SDKs", "use framework X annotations not framework Y") | Apply to every file you write, not only to the current increment |
| Operational invariants | IaC, observability, IAM, deployment constraints, runtime invariants | Verify after green (§5); often not testable as Gherkin |

When reading a CLAUDE.md, identify which layer each section belongs to. The implementation strategy differs:

- ADR and structural specs are read-once at the start of the session and held as constraints throughout.
- Behavioral specs drive the red→green cycle for the current increment.
- Reference algorithms are consulted when the scenarios alone underspecify the implementation.
- Code hygiene rules apply to every commit, not only behavior-related ones.
- Operational invariants are verified after behavior is green.

If the spec contains inline `Note:` or "Out-of-scope" annotations attached to scenarios, treat those as authoritative disambiguation; do not override them.

## Prerequisites

This skill assumes a Linux-like shell with the following tools available:
- `bash` (4+)
- `git`
- `grep`, `sed`, `awk`, `find` (POSIX)
- `jq` (for JSON)
- `curl` (for probing external APIs)

If any of these is missing on a system you control, install it before proceeding. Stack-specific tools (e.g. `mvn`, `aws`, `terraform`) are required by companion skills, not by this one.

## 1. Read and parse the input

Before doing anything else, read the CLAUDE.md from the repo root. Identify, in this order:

1. **ADR** (architecture decision record). Record stack and infra choices as binding constraints. Do not deviate from them while implementing; if you find yourself wanting to deviate, see §11.
2. **Code hygiene rules**. Cross-cutting constraints that apply globally (decimal handling, time handling, layer purity, allowed/banned annotations, banned patterns). Hold these as constraints for every file you write, not only for the current increment.
3. **Operational invariants**. Either explicit (a dedicated section) or implicit in IaC / IAM / observability sections.
4. **The target increment**. By ID, name, or context.
5. **The increment's behavioral spec**: `As ..., I want ...` clause + Gherkin scenarios + any inline `Note:` / "Out-of-scope" annotations.
6. **Reference algorithms** attached to the increment, if any.
7. **Existing scenarios from earlier increments** that this one might affect (regression risk).
8. **Documented trade-offs** in or near the increment. The spec sometimes explicitly accepts a trade-off (e.g. "not atomic; documented trade-off", "single provider failure is logged and dropped"). These are decisions, not bugs. See §5.

Do not start work if the target increment is missing, has no value clause, or has no scenarios. Stop and produce a clarification brief (see §6).

## 2. Decide on sub-increments (optional)

If the increment is too large to handle in one coherent pass, or if splitting it would produce qualitatively better work for how you operate, you may propose sub-increments. Reasons that justify a split include:
- Total context to hold is dense even if not strictly oversized.
- Prereqs and behavior are heterogeneous enough that intermediate red/green checkpoints would be cleaner.
- Multiple scenarios in the same increment cover conceptually distinct sub-domains.

When you propose a split, every sub-increment must itself have an `As ..., I want ...` clause and at least one Given/When/Then scenario. State the motivation explicitly. Wait for user approval before proceeding. Never split unilaterally.

## 3. Declare the plan

Once the increment (or sub-increment) is fixed, declare a plan in chat before touching any code. The plan has two lists:

**Prerequisites** — work needed to make the scenarios runnable as failing tests. Examples: a REST client, a Kafka consumer, an event matcher, a fixture, a test double. Anything the scenarios depend on that doesn't exist yet.

**Behavior implementation** — the work that makes the scenarios pass once the prereqs are in place.

State them as two short lists. Do not write code yet. The plan is the user's checkpoint to spot missing prereqs or scope creep before you've committed cycles.

### Optional gate

If the prereqs list is large or touches cross-cutting concerns (new external client, new dependency, changes to shared infrastructure or config), stop and ask the user to confirm the plan before proceeding. For small, internal-only prereq lists, proceed without asking.

## 4. Execute prereq → red → green → refactor

Work in this order:

1. **Prereqs.** Build the scaffolding the scenarios need. Each prereq should be its own logical unit of change, with its own commit. Verify in isolation that each prereq behaves as expected — without yet running the Gherkin scenarios against it.
2. **Red.** Wire the Gherkin scenarios to the system under test so they execute and fail for the right reason (missing behavior, not missing prereq). If a scenario fails because a prereq is broken, fix the prereq first.
3. **Green.** Implement the behavior. Make scenarios pass one at a time when possible. Resist the urge to batch implementation across multiple scenarios — green one scenario, commit, move to the next.
4. **Refactor.** Clean up only after all scenarios for the increment are green. Refactoring before green confuses signal (was the failure caused by the new code, or by the refactor?).

### When to add unit tests (AAA: Arrange/Act/Assert)

The Gherkin scenarios are the primary spec. Add unit tests in Arrange/Act/Assert form only where they add value the scenarios cannot capture. Examples where AAA pays off:
- Algorithmic logic with many edge cases not worth enumerating in Gherkin.
- Pure functions with non-trivial branching.
- Boundary conditions (off-by-one, empty collections, large inputs).

Examples where AAA is redundant:
- Behavior already covered by a Gherkin scenario.
- Simple delegation or wiring code.
- Code whose only purpose is to satisfy a single scenario.

When you decide to add (or skip) an AAA test, state the decision and the reason in the commit message. Do not add AAA tests reflexively; do not skip them when they would add real coverage.

## 5. Classify test failures

When a test fails unexpectedly during the cycle, classify it before acting. The classification determines what you do next:

- **Expected TDD failure.** A test you just wrote fails because the behavior isn't implemented yet. This is the red→green cycle working as intended. Continue.
- **Pre-existing test failure.** A test that was green before your changes is now red. The fix would touch code outside the current increment. **Stop and report.** Do not extend scope.
- **Unclear internal failure.** A test in the current increment fails for a reason that's not obvious. If the cause is isolable and internal to this increment, fix it and continue. If isolation requires touching code outside this increment, stop and report.
- **Ambiguous spec.** A test failure suggests the Gherkin scenario is contradictory, underspecified, or inconsistent with another scenario. **Always stop and report.** Never paper over a spec problem by adjusting code or test until the spec is clarified.
- **Documented trade-off encountered.** The failure mode is one the spec explicitly accepts (e.g. "not atomic; documented trade-off", "single provider failure is logged and dropped"). **Do not fix.** The trade-off is a decision. Record that the trade-off was hit (log, commit message). Only revisit it if the user asks.

State the classification in the commit message or in the chat update when the failure occurs. Do not bundle multiple classifications silently.

### Operational invariants

If the increment is associated with operational invariants (e.g. "every Lambda env var declared in code must have a Terraform resource that injects it"), verify these explicitly after green. They are not testable as Gherkin scenarios; verify them with bash checks, grep patterns, or scripts. If a companion skill defines the verification mechanism for a specific stack, use it.

## 6. Stop and produce a clarification brief

When you stop for an ambiguity (missing spec, contradictory scenarios, prereq with unclear scope, etc.), produce a clarification brief in the chat. The brief is AI-agnostic — the user may paste it into another model for a second opinion. Use exactly these 8 sections, in this order:

1. **Context.** 2-3 lines on what you were doing (increment ID, current phase: prereq/red/green/refactor).
2. **Source artifacts.** Exact references: CLAUDE.md filename, feature/scenario name, code snippets quoted verbatim.
3. **Ambiguity statement.** One sentence. "The ambiguity is X." No preamble.
4. **Why it blocks progress.** Why the work cannot continue without resolving this — not "I'd prefer to know" but "without this I cannot decide between options N below".
5. **Options considered.** Numbered list of plausible interpretations. For each: what it implies for implementation, which existing scenarios it supports or breaks, relative cost.
6. **What you recommend and why.** Your preferred option with a one-sentence rationale, or an explicit statement that you have no preference.
7. **Question to the reviewer.** Binary or multiple-choice, with explicit answer criterion (e.g. "choose 1/2/3" or "confirm or correct").
8. **Out of scope for this question.** What you are NOT asking, so the reviewer doesn't expand the question.

Constraints on the brief:
- No markdown beyond headers, bullets, and code fences.
- No references to chat history. The brief is self-contained.
- Acronyms expanded on first use.
- Target length: readable in 2 minutes, ~400 words max for a typical brief.
- Never ask open-ended questions like "what do you think?". Always provide an answer criterion.

## 7. Commit message convention

Commits are your only persistent log between sessions. When a new session reads `git log`, it must be able to reconstruct your decisions. Commit messages should be informative without leaking sensitive detail (assume the repo may be public).

**Format:**

```
[<increment-id>][<phase>] <short summary>

<longer body, optional but encouraged for non-trivial commits>
```

Where `<phase>` is one of: `prereq`, `red`, `green`, `refactor`, `infra`, `docs`, `fix`.

The body should record:
- Decisions you made that another reader could not infer from the diff alone.
- AAA test additions or omissions, with one-sentence reason.
- Test failure classifications when relevant ("classified as pre-existing failure, see issue #N").
- Sub-increment boundaries when working through a split.

Examples:

```
[INC-42][prereq] add HTTP client wrapper for upstream service

Wraps the raw client to inject retry headers per Gherkin scenario
"Given the upstream returns 429". No business logic here.
```

```
[INC-42][green] match events on (correlationId, type) tuple

Naive single-key matching would not handle the duplicate-correlationId
case in scenario "Two events with same correlation ID". Skipped AAA
test because the Gherkin scenario fully covers the branching.
```

Do not leak: internal endpoints, secret names, customer identifiers, business rule details that aren't already public, or vulnerability descriptions.

## 8. Communication discipline

### One action per turn in operational flow

When troubleshooting a live system or executing setup steps, give exactly one command per response and wait for the user's output before the next. Bundling "first do X, then Y, then Z" feels efficient but each step's output may invalidate the next. This rule applies to operational/troubleshooting flow; for plan declaration, brief production, or static analysis, multi-section output is fine.

### Verified vs speculated facts

Distinguish these states explicitly in your output:
- **Verified.** Confirmed by tool use this session (web search result, file read, command output). Cite the source.
- **Stated assumption.** Standard behavior in your training data. May be stale. Flag as such.
- **Speculation.** Hypothesis to explain a symptom. Always label: "this is a hypothesis; the way to test it is X".

When uncertain, prefer "I don't know, let's verify" over a confident guess. For claims about products, library versions, free tiers, APIs, runtime support, or anything that changes over time, verify before stating.

### User pushback as signal

When the user contradicts a claim you just made, do not double down. The user is closer to the system than you are. Default response: "let me re-check". Re-verify the contested claim explicitly. If you were right, present the evidence concisely. If wrong, acknowledge in one line and move on. Do not over-apologize.

### Third-party API discipline

Before integrating with an external API or library:
- Probe with controlled experiments. If the user can reach the API from their terminal, reproduce the call with `curl` and vary User-Agent, headers, and authentication. If a browser-like UA works and the default does not, the issue is UA classification, not IP rate-limiting. Do not invent IP-blocking or rate-limit explanations without evidence.
- Check the library's recency (last commit, last release). Abandonware libraries — common in finance and market data — are a known failure mode. Prefer maintained alternatives.
- Check what's actually in the free tier. Don't assume "free" covers all endpoints uniformly.

### Tool integration data audit

When the system exposes a tool catalog (function calling, MCP, agent tools), audit each tool's underlying data source before relying on the catalog. For each tool, verify:
- What data source backs it?
- Does the implementation fetch real data, or does it return empty/placeholder by default?
- What's the failure mode when data is unavailable?

A tool that silently returns empty results is worse than no tool: the consumer will reason about empty output as if it were a fact.

## 9. Branching (recommendation, not enforcement)

The recommended pattern: work on a feature branch per increment, commit at each phase boundary (prereq, red, green, refactor), and merge to main only when all scenarios for the increment are green. Long-lived branches are an antipattern; if an increment takes more than a few days, that's a signal to split.

This is a recommendation. If the user works differently (trunk-based, feature flags, ad-hoc branching), follow their lead. Do not block on branching preferences.

## 10. Definition of done

An increment is done when:
- All Gherkin scenarios for the increment pass.
- All AAA tests added for the increment pass.
- All pre-existing tests still pass (no regressions).
- Operational invariants associated with the increment are verified.
- Commits follow the convention in §7.

There is no manual sign-off step beyond green build. If the user wants stakeholder validation, that's a separate gate outside this workflow.

## 11. ADR primacy

The ADR (architecture decision record) section of CLAUDE.md is not a default to be revisited. It is a constraint set chosen deliberately by the user. Treat it as binding throughout the session.

Examples of ADR-level constraints: language version, framework choice, build tool, runtime (Lambda vs container vs server), database choice and modeling style (e.g. single-table DynamoDB), AWS SDK version, persistence library, charting library, email library, IaC tool, observability stack.

If during implementation you find an ADR constraint inconvenient or you have a better alternative in mind:
- Do not silently substitute. Even a "better" choice that contradicts the ADR is a violation.
- Do not introduce a parallel mechanism alongside the ADR choice.
- Stop and ask. State the constraint, your proposed alternative, the reason, and the cost of switching. Wait for explicit user approval before changing anything.

If the ADR is silent on a sub-decision (e.g. ADR picks "Maven" but does not specify the plugin for shading), make the choice idiomatically and document it in the commit message — that is sub-ADR territory, not ADR violation.

## 12. Drift detection

During implementation, you will encounter the existing code base. The relationship between code and CLAUDE.md is not always clean. Three cases:

- **Code matches spec.** Proceed normally.
- **Code is silent on something the spec describes.** Implement it.
- **Code contradicts the spec.** Drift. Stop and report — do not silently normalize either direction.

When you detect drift:
- Identify which side is canonical: spec or code? The default is spec. But the user may have made a deliberate change in code that wasn't yet folded back into CLAUDE.md.
- Produce a clarification brief (§6) with: the spec passage, the code location, the nature of the divergence, options (align code to spec / update spec to match code / hybrid), and your recommendation.
- Do not start changing code or spec until the user confirms which side wins.

When generating any kind of project status report (e.g. a context bundle for handoff to another tool), include a "known divergences" section that lists drift discovered but not yet resolved. Drift unrecorded is drift accumulating.

### Pre-flight audit on increments marked done

CLAUDE.md often records increment status (e.g. a roadmap with ✅ marks). A `done` mark is the user's statement of belief, not a guarantee that every Gherkin scenario in that increment has a corresponding test that actually fails when the implementation is removed. Specs evolve, tests get skipped, scenarios get added without a matching test — the result is **silent drift**: the Gherkin scenario exists, but nothing enforces it.

A full audit of every done increment at every session start would be prohibitively expensive. Instead, the audit runs only when one of these triggers fires:

- **Trigger 1: modifying code in a done increment.** You are about to change code that belongs to an increment marked done. Before changing it, audit it.
- **Trigger 2: unexpected test failure pointing at a done increment.** A test failure in the current increment seems to originate from behavior owned by a done increment (e.g. retry logic, circuit breaker, validation), and the symptom suggests the upstream behavior was never actually implemented.

When a trigger fires, perform the minimum audit:

1. List the Gherkin scenarios in the done increment from CLAUDE.md.
2. For each scenario, locate the test that implements it (by feature/scenario name, by tag, or by the file structure convention the project uses).
3. For at least the scenarios relevant to the current work, confirm the test fails when the implementation is removed (mental dry-run, or actual mutation if cheap). A test that passes regardless of the implementation does not enforce the scenario — it is dead coverage.
4. If a scenario has no corresponding test, or the test does not actually fail without the implementation: **this is retroactive drift**. Stop and report via clarification brief (§6).

The audit is scoped: do not expand it beyond the scenarios touched by the current work. The goal is to catch the trap before falling into it, not to re-validate the entire codebase.

If the audit finds retroactive drift, the user decides whether to:
- Add the missing tests now (and only then proceed with the current work).
- Acknowledge the gap and proceed at their own risk (their explicit choice).
- Reclassify the done increment as "drifted from spec" in CLAUDE.md.

Do not make this choice unilaterally. The point of the audit is to surface the gap, not to fix it without consent.

## Anti-patterns to avoid

- **Speculating as if diagnosing.** "The API probably rate-limits AWS IPs" without evidence is speculation, not diagnosis. Label it.
- **Bundling multi-step plans during operational troubleshooting.** Output is invalidated step by step.
- **Default architectural assumptions.** Don't assume REST, microservices, Postgres, OAuth, Docker, Kubernetes, or any specific CI/CD just because they're common. If an assumption is needed, ask.
- **Reflexive AAA tests.** Don't add unit tests for code already fully covered by Gherkin scenarios.
- **Reflexive AAA omission.** Don't skip unit tests for algorithmic logic just because a Gherkin scenario touches the surface.
- **Scope creep into pre-existing failures.** A pre-existing failure is a separate ticket, not a silent fix during the current increment.
- **Papering over ambiguous specs.** Adjusting code or tests to make an ambiguous spec "work" loses the most valuable signal you have. Stop and clarify instead.
- **Fixing a documented trade-off.** If the spec explicitly accepts a behavior, your job is not to "improve" it. The cost of revisiting was paid by whoever wrote the trade-off; don't re-pay it unilaterally.
- **Silently normalizing drift.** If code and spec contradict each other, both are evidence. Picking one without the user's input is unilateral spec change.
- **Trusting a "done" mark without verification when modifying or debugging.** A ✅ in CLAUDE.md is a statement of belief; if you are about to change or debug code in a done increment, audit it first (§12). Building on top of unverified done work multiplies the risk.
- **ADR substitution.** Replacing a stack choice with a "better" one without explicit approval. Even if you're right, the cost of inconsistency is real and not yours to pay.

## Companion skills

Stack-specific behavior (build idioms, framework annotations, cloud CLI commands) lives in separate skills that compose with this one. This skill defines the workflow; companion skills define the technical idioms. When a companion skill is loaded, follow both. If they conflict, the user decides; ask explicitly.
