# Behavioral eval harness for the `wichtelm-strategy-author` skill

The JUnit `SkillRegressionTest` is **Layer 1**: it checks the skill's *files* stay
consistent with the app (examples parse, the catalog matches `BuiltinCatalog.java`, the
quoted parser messages still exist, no inline comments in gherkin snippets). It runs in
the normal CI.

This is **Layer 2**: it checks the skill's *behavior* — when Claude is given the skill and
asked for a strategy, does it actually produce a `.strat` that **parses**? Because model
output is non-deterministic, you can't assert exact text; instead each generated strategy
is **graded by the real `wichtelm validate` parser** (exit 0 = pass). The parser is the
objective judge, which turns a fuzzy "did it do well?" into a hard pass/fail.

This is **not** a unit test and is deliberately kept out of CI:
- it calls the paid Claude API (costs money; results vary run to run);
- it needs a built `wichtelm` (jar or launcher) to grade against the real parser.

## Run it

```sh
export ANTHROPIC_API_KEY=sk-ant-...
mvn -q -DskipTests package            # builds target/wichtelm.jar (needs JDK 25)
pip install -r evals/requirements.txt
python evals/run_evals.py             # auto-detects target/wichtelm.jar
```

Useful flags:

```sh
python evals/run_evals.py --llm-judge                 # also score quality 1-5 with an LLM judge
python evals/run_evals.py --model <model-id>          # grade against the model your users run
python evals/run_evals.py --limit 3 --out report.json # quick subset + JSON report
python evals/run_evals.py --validator 'wichtelm validate {file}'   # custom validator command
```

The generation **model defaults to `claude-sonnet-4-6`** (cheap enough to run the whole
set); override with `--model` or the `ANTHROPIC_MODEL` env var to grade the skill against
whatever model your users actually run.

## How it works

1. **Loads the skill as a cached system prompt.** `SKILL.md` + everything under
   `reference/` and `examples/` is concatenated into one system block with
   `cache_control: {"type": "ephemeral"}`, so the large skill content is written to the
   cache once and read (~0.1× cost) on every subsequent prompt in the run. The harness
   prints the cache-read token count so you can confirm caching is working.

   > This approximates the claude.ai skill by injecting the skill's content as context.
   > It exercises the same instructions/catalog/examples; it does not use the hosted
   > Skills runtime.

2. **Generates** a `.strat` per prompt in `prompts.jsonl`, extracting the fenced
   ```gherkin block from the reply.

3. **Grades deterministically** by writing the strategy to a temp file and running
   `wichtelm validate` on it. Lightweight substring checks (`must_include` /
   `must_exclude`) catch content gaps — e.g. the `ha-only-honored` case asserts the skill
   respects an explicit "Heikin-Ashi only" request and doesn't bolt on an RSI. **A case
   passes only if it both parses and satisfies its substring assertions** — the content
   checks gate the result, they don't just print a note.

4. **(Optional) LLM judge** (`--llm-judge`) scores each result 1–5 for how well it
   satisfies the request and uses the DSL idiomatically. (Advisory — the judge score does
   not affect pass/fail.)

The pass-rate counts cases that parsed *and* met their substring assertions; the process
exits non-zero if any case failed either gate, so you can gate on it manually.

## The prompt set (`prompts.jsonl`)

Ten prompts spanning the catalog and the skill's behaviors: HA+RSI long/short, HA+200-EMA
+ ATR stop, HA+MACD, MA crossover, multi-timeframe, pivot breakout, an **HA-only** request
(verifies the skill honors it rather than adding indicators), a **fix-the-broken-strategy**
task (a `stop_loss` on an exit scenario — P12), a **non-default RSI period** case (should
use a Background series), and a `stddev` volatility filter. Add your own — one JSON object
per line.
