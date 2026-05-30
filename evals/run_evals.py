#!/usr/bin/env python3
"""Behavioral eval harness for the wichtelm-app strategy-authoring skill.

What it does
------------
Layer-1 tests (the JUnit ``SkillRegressionTest``) check that the skill's *files*
stay consistent with the app. This is Layer 2: it checks the skill's *behavior* —
does Claude, given the skill, actually produce a strategy that parses?

For each prompt in ``prompts.jsonl`` it:
  1. sends the prompt to the Claude API with the skill's SKILL.md + reference/ +
     examples/ loaded as a *prompt-cached* system prompt;
  2. extracts the ``.strat`` Claude produced;
  3. grades it deterministically by piping it through the real ``wichtelm validate``
     parser (exit 0 = pass) — the parser is the objective judge;
  4. optionally asks an LLM judge to score quality (``--llm-judge``).

It prints a pass-rate and writes an optional JSON report.

This is NOT a unit test: it calls a paid API, results vary run to run, and it needs
a built ``wichtelm`` (jar or launcher). Run it locally, not in the deterministic CI.

Usage
-----
    export ANTHROPIC_API_KEY=sk-ant-...
    mvn -q -DskipTests package           # build target/wichtelm.jar (needs JDK 25)
    python evals/run_evals.py            # uses target/wichtelm.jar automatically
    python evals/run_evals.py --model claude-opus-4-8 --llm-judge --out report.json

The model defaults to ``claude-sonnet-4-6`` (cheap enough to run the whole set);
override with ``--model`` or the ``ANTHROPIC_MODEL`` environment variable to grade
the skill against whatever model your users actually run.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SKILL_DIR = REPO_ROOT / "skills" / "wichtelm-strategy-author"
DEFAULT_MODEL = os.environ.get("ANTHROPIC_MODEL", "claude-sonnet-4-6")

# Order matters only for readability; all of it goes into one cached system block.
SKILL_FILES = [
    "SKILL.md",
    "reference/dsl-grammar.md",
    "reference/function-catalog.md",
    "reference/validation-rules.md",
    "reference/config-cli-report.md",
    "reference/guided-builder.md",
    "examples/README.md",
    "examples/canonical.strat",
    "examples/ha-rsi-reversal.strat",
    "examples/ha-ema200-atr.strat",
    "examples/ha-macd.strat",
    "examples/ha-ma-crossover.strat",
    "examples/ha-multi-tf.strat",
]

GENERATION_INSTRUCTION = (
    "\n\n===== EVAL HARNESS INSTRUCTION =====\n"
    "You are acting as the wichtelm-app strategy-authoring skill above. When the user "
    "asks you to write or fix a strategy, reply with ONLY the complete `.strat` file "
    "inside a single ```gherkin code block and nothing else — no prose before or after. "
    "Follow the grammar, the closed function catalog, and the P1-P22 rules exactly."
)

CODE_BLOCK_RE = re.compile(r"```(?:gherkin|strat|g-?herkin)?\s*\n(.*?)```", re.DOTALL | re.IGNORECASE)


@dataclass
class EvalCase:
    id: str
    prompt: str
    must_include: list[str] = field(default_factory=list)
    must_exclude: list[str] = field(default_factory=list)


@dataclass
class CaseResult:
    id: str
    parsed_ok: bool
    validator_output: str
    substrings_ok: bool
    missing: list[str]
    forbidden: list[str]
    judge_score: int | None = None
    judge_reason: str = ""
    strat: str = ""


def build_system_prompt() -> str:
    parts = ["The following is the complete content of the wichtelm-app "
             "strategy-authoring skill. Use it as your authoritative reference.\n"]
    for rel in SKILL_FILES:
        path = SKILL_DIR / rel
        if not path.is_file():
            sys.exit(f"ERROR: skill file missing: {path}")
        parts.append(f"\n===== FILE: {rel} =====\n{path.read_text()}")
    parts.append(GENERATION_INSTRUCTION)
    return "".join(parts)


def load_cases(prompts_path: Path, limit: int | None) -> list[EvalCase]:
    cases: list[EvalCase] = []
    for line in prompts_path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        obj = json.loads(line)
        cases.append(EvalCase(
            id=obj["id"],
            prompt=obj["prompt"],
            must_include=obj.get("must_include", []),
            must_exclude=obj.get("must_exclude", []),
        ))
    return cases[:limit] if limit else cases


def resolve_validator(explicit: str | None) -> list[str] | None:
    """Return a command template containing the literal token ``{file}``."""
    if explicit:
        return explicit.split()
    jar = REPO_ROOT / "target" / "wichtelm.jar"
    if jar.is_file():
        return ["java", "-jar", str(jar), "validate", "{file}"]
    if shutil.which("wichtelm"):
        return ["wichtelm", "validate", "{file}"]
    return None


def run_validator(template: list[str], strat: str) -> tuple[bool, str]:
    with tempfile.NamedTemporaryFile("w", suffix=".strat", delete=False) as fh:
        fh.write(strat)
        tmp = fh.name
    try:
        cmd = [tok.replace("{file}", tmp) for tok in template]
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
        out = (proc.stdout + proc.stderr).strip()
        return proc.returncode == 0, out
    except subprocess.TimeoutExpired:
        return False, "validator timed out"
    finally:
        os.unlink(tmp)


def extract_strat(text: str) -> str:
    m = CODE_BLOCK_RE.search(text)
    return (m.group(1) if m else text).strip()


def message_text(resp) -> str:
    return "".join(b.text for b in resp.content if getattr(b, "type", None) == "text")


def generate(client, model: str, system_blocks, prompt: str) -> tuple[str, object]:
    resp = client.messages.create(
        model=model,
        max_tokens=2000,
        system=system_blocks,
        messages=[{"role": "user", "content": prompt}],
    )
    return message_text(resp), resp.usage


JUDGE_SYSTEM = (
    "You are grading a trading-strategy DSL file a model produced in response to a "
    "request. Score how well the strategy satisfies the request and uses the DSL "
    "idiomatically. Respond with ONLY a JSON object: "
    '{"score": <integer 1-5>, "reason": "<one sentence>"}.'
)


def judge(client, model: str, prompt: str, strat: str) -> tuple[int | None, str]:
    resp = client.messages.create(
        model=model,
        max_tokens=300,
        system=JUDGE_SYSTEM,
        messages=[{"role": "user",
                   "content": f"REQUEST:\n{prompt}\n\nSTRATEGY:\n{strat}"}],
    )
    text = message_text(resp).strip()
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        return None, f"unparseable judge output: {text[:120]}"
    try:
        obj = json.loads(m.group(0))
        return int(obj.get("score")), str(obj.get("reason", ""))
    except (ValueError, TypeError):
        return None, f"unparseable judge output: {text[:120]}"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", default=DEFAULT_MODEL,
                    help=f"generation model (default: {DEFAULT_MODEL})")
    ap.add_argument("--judge-model", default=None,
                    help="LLM-judge model (default: same as --model)")
    ap.add_argument("--prompts", default=str(Path(__file__).parent / "prompts.jsonl"))
    ap.add_argument("--validator", default=None,
                    help="validator command with a {file} token; "
                         "auto-detected (target/wichtelm.jar or `wichtelm`) if omitted")
    ap.add_argument("--llm-judge", action="store_true", help="also run the LLM quality judge")
    ap.add_argument("--limit", type=int, default=None, help="run only the first N prompts")
    ap.add_argument("--out", default=None, help="write a JSON report to this path")
    args = ap.parse_args()

    try:
        from anthropic import Anthropic
    except ImportError:
        sys.exit("ERROR: pip install -r evals/requirements.txt (the anthropic SDK is required)")
    if not os.environ.get("ANTHROPIC_API_KEY"):
        sys.exit("ERROR: set ANTHROPIC_API_KEY")

    template = resolve_validator(args.validator)
    if not template:
        sys.exit("ERROR: no validator found. Build the app (`mvn -q -DskipTests package` "
                 "for target/wichtelm.jar, needs JDK 25), put `wichtelm` on PATH, or pass "
                 "--validator 'java -jar path/to/wichtelm.jar validate {file}'.")

    client = Anthropic()
    cases = load_cases(Path(args.prompts), args.limit)
    # One large cached system block — reused across every prompt (cache reads ~0.1x).
    system_blocks = [{"type": "text", "text": build_system_prompt(),
                      "cache_control": {"type": "ephemeral"}}]
    judge_model = args.judge_model or args.model

    results: list[CaseResult] = []
    cache_reads = 0
    print(f"Running {len(cases)} eval prompts with model={args.model}\n")
    for case in cases:
        strat_text, usage = generate(client, args.model, system_blocks, case.prompt)
        cache_reads += getattr(usage, "cache_read_input_tokens", 0) or 0
        strat = extract_strat(strat_text)
        parsed_ok, vout = run_validator(template, strat)
        missing = [s for s in case.must_include if s not in strat]
        forbidden = [s for s in case.must_exclude if s in strat]
        res = CaseResult(
            id=case.id, parsed_ok=parsed_ok, validator_output=vout,
            substrings_ok=(not missing and not forbidden),
            missing=missing, forbidden=forbidden, strat=strat,
        )
        if args.llm_judge:
            res.judge_score, res.judge_reason = judge(client, judge_model, case.prompt, strat)
        results.append(res)

        status = "PASS" if parsed_ok else "FAIL"
        extra = ""
        if not parsed_ok:
            extra = f"  -> {res.validator_output.splitlines()[0] if res.validator_output else 'no output'}"
        elif missing or forbidden:
            extra = f"  (substrings: missing={missing} forbidden={forbidden})"
        if res.judge_score is not None:
            extra += f"  [judge {res.judge_score}/5]"
        print(f"  {status}  {case.id}{extra}")

    passed = sum(1 for r in results if r.parsed_ok)
    print(f"\nParse pass-rate: {passed}/{len(results)} "
          f"({100 * passed / len(results):.0f}%)" if results else "no cases")
    if cache_reads:
        print(f"Cache reads: {cache_reads} tokens (system prompt was cached across prompts)")
    if args.llm_judge:
        scored = [r.judge_score for r in results if r.judge_score is not None]
        if scored:
            print(f"Avg judge score: {sum(scored) / len(scored):.2f}/5 over {len(scored)} graded")

    if args.out:
        Path(args.out).write_text(json.dumps(
            {"model": args.model, "pass_rate": passed / len(results) if results else 0,
             "results": [r.__dict__ for r in results]}, indent=2))
        print(f"Report written to {args.out}")

    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
