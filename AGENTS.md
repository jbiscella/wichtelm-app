# AGENTS.md

This file is read by AI agents that work on this repository (in particular OpenAI Codex code review). It captures the review guidelines and project conventions that the author expects every reviewer (human or AI) to apply.

The file is split into two sections:

- **Section A — Cross-repository conventions** (reusable, copy verbatim into every repo)
- **Section B — Repository-specific context** (replace per repo)

---

## SECTION A — Cross-repository conventions

The content of Section A is the author's standard working agreement and applies to every repository the author owns.

### Working protocol

The repository author works with the following protocol that every AI agent must respect:

- **Direct and concise communication.** State conclusions first, then arguments. No filler, no preamble, no restating the question.
- **Decision tables in markdown.** When comparing options, use a table with options as columns and criteria as rows. Cells should be short — yes/no, or a few words. Never long paragraphs inside table cells.
- **Behavioral specifications in Gherkin.** Given/When/Then (English) for all behavioral specs. Tabular decision matrices for closed-set comparisons.
- **One open question at a time.** When consulting the author for clarification, ask exactly one binary or short-list question per turn. Never bundle multiple questions.
- **Acknowledge errors in one line.** If an error is discovered (yours or in spec), state it in one line, show the correction, indicate cascade effects on subsequent blocks, and ask whether to redo or patch.
- **One step at a time for operational sequences.** For shell commands, implementation steps, or actions where intermediate output is needed, output a single step and wait for the author's response before continuing.

### Factual verification (non-negotiable)

For every factual claim about libraries, versions, prices, free tiers, runtime support, geographic coverage, available APIs — **verify via web search before writing**. Never respond from memory on factual product-level claims. If verification is not possible, declare explicitly: "I cannot verify, proceed with caution." Never write "many libraries X exist" without listing and verifying them.

### Architectural defaults are banned

Never assume popular architectural defaults (REST API, microservices, PostgreSQL, OAuth, Docker, Kubernetes, CI/CD, etc.) without them being explicitly required by the spec. If an assumption is necessary to proceed, stop and ask a binary question. The phrase "typically backend services have a REST API" is banned.

### Stack-specific cautions

When working in a specific technology stack (Micronaut, NestJS, Vue3 Composition API, etc.), remember that annotations, APIs, and patterns have homonyms in other frameworks (Spring, Express, React) but different meanings. Before citing an annotation, a library, or a pattern, verify it belongs to the current stack. If in doubt, indicate the full package/import path explicitly.

### Feature creep is banned

Do not add features, sections, sub-blocks, error codes, metrics, or configurations that were not explicitly requested. If something seems missing, list it as "potential additions to decide" at the end of the response and wait for input. Never implement proactively.

### Critique on demand

When the author asks "explain", "go deeper", "what do you think", do not limit the response to confirming the option already chosen. Actively present counter-arguments, valid alternatives, and cases where the author's choice may be wrong. If the author's request contains a faulty premise, correct it before responding.

### Severity legend

| Level | Meaning |
|---|---|
| P0 | Must fix before merge. Spec drift, correctness, safety violations |
| P1 | Should fix before merge unless an explicit decision is recorded to defer |
| P2 | Should fix soon; can defer with comment |
| P3 | Nit. Optional improvement, no blocker |

Codex code review default behavior is to flag only P0 and P1. Reviewers may explicitly escalate P2/P3 items when material.

### Cross-repository review guidelines

These guidelines apply regardless of the repository.

**Critical (P0)**

- **Spec drift**: any change that contradicts an authoritative specification file in the repository. If the change is correct and the spec is outdated, flag both — spec review and code review are distinct.
- **Hardcoded secrets**: tokens, API keys, passwords in code, comments, or logs. URLs containing tokens (e.g. query-string auth) must never be logged at INFO level or above.
- **Factual claims about external systems without web verification**: any code or comment that asserts a product, library, version, price, or limit that is not currently verifiable.

**High (P1)**

- **Default assumption injection**: any change that introduces a "popular default" not justified by the spec (REST API, ORM, message broker, container orchestration, etc.).
- **Unbounded feature creep**: changes that add features, error codes, metrics, configurations, or sections not requested by the original task or spec.
- **Documentation drift**: doc content (JavaDoc, docstring, README, inline) that contradicts spec or behavior, even when code is correct.

**Medium (P2)**

- Style consistency issues that are not yet enforced by tooling.
- Minor naming inconsistencies that may confuse new contributors.

### Things never to suggest

- Do NOT suggest adding CI/CD pipeline files unless explicitly asked.
- Do NOT suggest implementing planned future features (anything labeled "reserved for future enhancement" or in a planned-extensions section).
- Do NOT suggest releasing or publishing artifacts unless the repository is explicitly at a release milestone.
- Do NOT suggest replacing the test infrastructure with a different framework (e.g. don't suggest pure JUnit when Cucumber is in use, or vice versa). The choice is deliberate per project.
- Do NOT suggest splitting or merging modules. Module structure is deliberated upfront.
- Do NOT suggest "modernizing" patterns (e.g. converting records to Lombok classes, or vice versa). Patterns are chosen per project.

### Things to actively check (language-agnostic)

These are easy-to-miss issues in any language or stack:

- **Numeric equality with precision** (BigDecimal in Java, Decimal in Python): default `equals` may use scale-sensitive comparison; business equality usually requires `compareTo()` or normalized scale.
- **Time handling**: prefer UTC instant types (`Instant` in Java, `datetime` with `tzinfo=UTC` in Python) over local timezone types unless explicitly justified.
- **Optional/null handling in records or value classes**: records cannot enforce non-null on Optional fields; the canonical constructor must validate.
- **Defensive copies of collections in records**: use immutable-copy idioms (`List.copyOf` in Java, `frozenset`/`tuple` in Python), not mutable-copy idioms.
- **Thread safety contracts**: any type documented as thread-safe must not introduce static mutable state.
- **Lookahead / time-leak hazards**: in any time-series or event-driven code, ensure that computations at time T do not depend on data with time > T.

---

## SECTION B — Repository-specific context

This repository is `wichtelm-app`, an end-user backtesting application built on top of the `ha-track` libraries (separate repository, published to Maven Central under `net.jacopobiscella` namespace).

### Project overview

`wichtelm-app` is a Java 25 single-module application. The user expresses a trading strategy as a `.strat` text file in a custom Gherkin-conformant DSL, invokes the app via the `wichtelm` CLI with a TOML config file specifying what to run and against what data, and receives a self-contained HTML report with aggregate metrics and per-condition visual breakdowns.

The naming theme is continental Germanic folklore (Wichtelmann = small nocturnal helper sprite); the `-Mann → -app` pun mirrors the `Hütchen → H-tchen` deformation in sibling projects.

### Authoritative specifications

The repository is **spec-driven**. The root `CLAUDE.md` is the authoritative source for behavior. Any module-level CLAUDE.md (if added in future) takes precedence within its scope. These files take precedence over inline code comments, README content, or general best practices.

### Dependencies on `ha-track`

This repository consumes published artifacts from `ha-track`:

| Artifact (Maven Central) | Used for |
|---|---|
| `net.jacopobiscella.commons` | shared types (OHLCBar, Timeframe, Series, etc.) |
| `net.jacopobiscella.indicators` | shared indicator calculators (SMA, EMA, RSI, MACD, ATR, stddev) |
| `net.jacopobiscella.frau-holle` (v1.2+) | backtester engine. Uses `ClosePositionAtPrice` (v1.1) for intrabar stops and `AddToPosition` (v1.2) for pyramiding |
| `net.jacopobiscella.frau-holle-csv` | local CSV data driver |
| `net.jacopobiscella.frau-holle-eodhd` | EODHD data driver |
| `net.jacopobiscella.nachtkrapp` | pattern detection (HA primitives + MA/RSI/MACD primitives) |
| `net.jacopobiscella.heerwisch-jfreechart` | chart rendering for the HTML report |

### Repo-specific critical findings (P0)

- **Lookahead-safety violations in the DSL runtime**: this is the differentiating invariant of Wichtelm-app vs Pine Script and similar tools. Multi-TF references in user strategies MUST be evaluated against the most recently CLOSED higher-TF bar at the primary bar's time T. Any path that allows a user's `.strat` to access a higher-TF bar with time > T (or with `closeTime > T`) is a defect. See §3.3 of root CLAUDE.md.
- **Decimal arithmetic correctness**: all numeric values in the DSL evaluator and in the runtime use `BigDecimal` with `MathContext.DECIMAL64`. Introduction of `double`/`float` in business logic is a defect.
- **DSL parser must reject malformed input deterministically**: parse-time errors must produce clear messages with file, line, column, description, and suggestions. Silent skipping of unknown indicators/functions is a defect. See §3.5 of root CLAUDE.md.
- **Strategy Scenario must terminate with one of the 4 first-class conditions** (`long_entry`, `long_exit`, `short_entry`, `short_exit`). A `.strat` with a Scenario terminating differently must be rejected at parse time. See §3.2 of root CLAUDE.md.
- **Secrets handling**: API tokens (EODHD, etc.) MUST come from environment variables, NEVER from config files committed to git. URLs containing tokens must NEVER be logged at INFO level or above. See §3.4 of root CLAUDE.md.

### Repo-specific high findings (P1)

- **DSL grammar conformance**: the DSL is Gherkin-conformant — only standard Gherkin keywords are used (Feature, Background, Scenario, Given, When, Then, And, But). The `And with stop_loss at <expr>` and `And with take_profit at <expr>` clauses use the standard `And` keyword followed by domain-specific text. NO custom keywords beyond standard Gherkin.
- **Hand-written parser, not Cucumber library**: the DSL is parsed by a hand-written line-oriented parser, JDK-only, no dependency on `io.cucumber:gherkin`. The internal test infrastructure uses Cucumber for Java + JUnit Platform, but the user-facing `.strat` parser is independent.
- **Single-symbol per strategy**: a `.strat` file targets one instrument at a time. Multi-symbol portfolio strategies are out of scope.
- **Percentage-based capital**: position sizing is `X% of capital per entry`. Initial capital is normalized internally; absolute currency amounts are not used.
- **Stop-loss priority over close-evaluated exits**: when both an intrabar stop_loss/take_profit and a close-evaluated exit Scenario trigger in the same bar, the stop_loss/take_profit wins (the broker fills the stop before the close exists). See §3.7 of root CLAUDE.md.
- **frau-holle Signal mapping**: the runtime emits frau-holle Signal variants based on DSL evaluation. `long_entry` → `Buy`, `short_entry` → `Sell`, `long_exit`/`short_exit` (close-evaluated) → `ClosePosition`, intrabar stop/take_profit → `ClosePositionAtPrice`, pyramiding entry → `AddToPosition(direction)`.
- **TOML config parsing**: config files use TOML format. Parser library choice is deferred to implementation time (toml4j, tomlj, 4koma are candidates — none must introduce GPL-virality).
- **CLI tool name**: the binary is `wichtelm`. The artifact's main class produces this executable.

### Things that look wrong but are deliberate (do NOT flag)

- The DSL DOES NOT support user-defined functions or macros in v1. The vocabulary is closed to built-in functions/indicators from `nachtkrapp` and `indicators`. This is deliberate — user-defined functions are reserved as a future additive extension.
- Parameter types in `.strat` are limited to Integer and BigDecimal in v1. Boolean and String are deliberately NOT supported; they are reserved as future additive extensions.
- `entry_price`, `entry_time`, `position_size` are the only trade-context variables accessible in exit Scenarios. The set is deliberately minimal in v1; additions are reserved.
- The `And with stop_loss at <expr>` clause does NOT allow indicators (`atr`, `sma`, etc.) or window aggregates in v1. Only constants, parameters, and trade-context variables. ATR-based stops can be expressed as normal close-evaluated exit Scenarios. This is deliberate to simplify intrabar evaluation in v1.
- Stop_loss/take_profit fill price may sit outside the next bar's OHLC range — this is the gap-fill scenario explicitly allowed by `frau-holle` v1.1 `ClosePositionAtPrice` (which accepts any positive price). The runtime computes a realistic fill price (typically the next bar's open in gap scenarios), not necessarily the stop level itself.
- The runtime carries lookahead-safety enforcement entirely on its own; `frau-holle` does NOT police lookahead. This is by design — the contract in `frau-holle/CLAUDE.md` §2.2 states "Lookahead-safety: Implementations MUST NOT consult bars at times > context.currentBar().time(). This is a contractual promise of the strategy; the backtester does not police it." Wichtelm-app's SignalGenerator implementation fulfills this contract for arbitrary user strategies.
- The catalog of built-in functions/indicators exposed to the DSL is closed in v1 — see §3.9 of root CLAUDE.md. Adding new built-ins requires a v1.x release (additive, japicmp-validated).
- `Diagnostic`/`visualization`-only Scenarios are NOT allowed in `.strat` files. This is deliberate, matching the industry convention separating "strategy" scripts (Pine Script `strategy()`) from "indicator" scripts (Pine Script `indicator()`).
- Report HTML files are never overwritten — they include a timestamp in the filename (`{config_basename}_{timestamp}.html`). Accumulation of report files in the output directory is expected and intentional (preserves backtest history).

### Things never to suggest (repo-specific)

- Do NOT suggest replacing the Gherkin-conformant DSL with a different paradigm (SQL-like, fluent Java DSL, etc.). The paradigm choice was deliberated.
- Do NOT suggest replacing the hand-written parser with `io.cucumber:gherkin`. The hand-written choice was deliberated.
- Do NOT suggest adding `Tags`, `Rule`, `Scenario Outline`, `Examples`, `DocStrings`, or `DataTable` Gherkin features to the DSL in v1. These are deliberately out of v1 scope.
- Do NOT suggest replacing TOML with YAML/JSON/.properties. TOML was deliberated.
- Do NOT suggest implementing user-defined functions/macros in v1.
- Do NOT suggest adding a visual editor / web UI. This is reserved as a future increment.
- Do NOT suggest adding output formats beyond HTML (PDF, JSON, CSV trade export). These are reserved as future enhancements.
- Do NOT suggest dynamic position sizing (ATR-proportional, volatility-proportional). Reserved as future enhancement.
- Do NOT suggest slippage or commission models. Reserved as future enhancement.
- Do NOT suggest walk-forward / parameter sweep tooling as part of v1.
- Do NOT suggest making `wichtelm-app` a library or framework. It is an end-user application — its public API is the CLI surface plus the `.strat` DSL grammar, not Java types.

### Things to actively check (Wichtelm-app specific)

- Lookahead-safety paths in the multi-TF series lookup: any access to a higher-TF series at primary bar time T must resolve to the most recently closed higher-TF bar with closeTime ≤ T.
- Parse-time validation completeness: every malformed `.strat` input should produce a typed parse error, never an NPE or runtime crash.
- The 4 first-class condition enforcement: any Scenario without a terminating `Then long_entry` / `Then long_exit` / `Then short_entry` / `Then short_exit` should be rejected at parse time, not silently ignored.
- TOML config validation: config files referencing parameters not declared in the `.strat` should produce a clear warning or error (verify the chosen behavior in code matches the spec).
- Report filename uniqueness: two backtests run in the same second should not collide on filename. Either timestamp precision is sufficient or a collision-handling rule is documented.
- EODHD URL logging: every code path touching the EODHD client URL must NOT log it at INFO+. Even error paths should redact the api_token query parameter.
