#!/usr/bin/env bash
# Run every EODHD demo config against LIVE EODHD data and write one stable,
# committable report per config. This is the online counterpart to
# run_demo.sh (which exercises the offline CSV fixtures).
#
# Requires outbound network access to eodhd.com and the free demo token:
#
#   export EODHD_API_TOKEN=demo
#   ./demo/run_eodhd_demos.sh
#
# The free "demo" token serves AAPL.US, TSLA.US, VTI.US, AMZN.US, BTC-USD.CC
# and EURUSD.FOREX. Intraday from EODHD is RAW (unadjusted for splits and
# dividends); the committed demo windows deliberately avoid corporate-action
# discontinuities (see demo/README.md "Raw-data limitation").
#
# Each eodhd-*.toml is run with --output-dir demo/reports, then its freshest
# timestamped report is promoted to demo/reports/<config-basename>-report.html
# so it can be committed as a stable reference (matching the CSV demos).
#
# Env:
#   EODHD_API_TOKEN     (required) the token every eodhd-*.toml reads
#   WICHTELM_SKIP_BUILD=1   reuse an existing target/wichtelm.jar (skip mvn)
#
# Run from anywhere; the script cd's to the repository root itself.
set -uo pipefail

cd "$(dirname "$0")/.."

JAR=target/wichtelm.jar
REPORT_DIR=demo/reports
TOKEN_VAR=EODHD_API_TOKEN   # the env var name every eodhd-*.toml points at

# ── Preconditions ──────────────────────────────────────────────────────────
if [[ -z "${!TOKEN_VAR:-}" ]]; then
  echo "ERROR: \$$TOKEN_VAR is not set — the EODHD configs read the token from it." >&2
  echo "Export the free EODHD demo token and re-run:" >&2
  echo "    export $TOKEN_VAR=demo" >&2
  echo "    ./demo/run_eodhd_demos.sh" >&2
  exit 1
fi

if [[ "${WICHTELM_SKIP_BUILD:-0}" == "1" && -f "$JAR" ]]; then
  echo "==> WICHTELM_SKIP_BUILD=1 — reusing $JAR"
else
  echo "==> building $JAR from source"
  mvn -q clean package -DskipTests
fi

shopt -s nullglob
configs=(demo/eodhd-*.toml)
if [[ ${#configs[@]} -eq 0 ]]; then
  echo "ERROR: no demo/eodhd-*.toml configs found" >&2
  exit 1
fi

# ── Run each config ─────────────────────────────────────────────────────────
ok=()
failed=()
for cfg in "${configs[@]}"; do
  base="$(basename "$cfg" .toml)"
  echo "==> $base  ($cfg)"
  # --output-dir pins output to demo/reports regardless of each config's
  # [output] block; the EODHD configs deliberately omit one.
  if java -jar "$JAR" run "$cfg" --output-dir "$REPORT_DIR"; then
    latest="$(ls -t "$REPORT_DIR/${base}_"*.html 2>/dev/null | head -1)"
    if [[ -n "$latest" ]]; then
      mv -f "$latest" "$REPORT_DIR/${base}-report.html"
      echo "    -> $REPORT_DIR/${base}-report.html"
      ok+=("$base")
    else
      echo "    !! ran but produced no report file" >&2
      failed+=("$base (no report)")
    fi
  else
    echo "    !! backtest failed (network? token? unsupported symbol/window?)" >&2
    failed+=("$base")
  fi
done

# Sweep any leftover timestamped reports (e.g. from an interrupted run). The
# stable <base>-report.html names contain no '_' so they are never matched.
rm -f "$REPORT_DIR"/eodhd-*_*.html

# ── Summary ──────────────────────────────────────────────────────────────────
echo
echo "==================== EODHD demo summary ===================="
echo "  generated ${#ok[@]} / ${#configs[@]} report(s) under $REPORT_DIR/"
for b in "${ok[@]}";     do echo "    OK    $b-report.html"; done
for b in "${failed[@]}"; do echo "    FAIL  $b"; done
echo "============================================================"
echo "These reports embed licensed EODHD data — view them locally; do NOT commit"
echo "them (reports/.gitignore excludes eodhd-*-report.html). The committed demo"
echo "references are the synthetic-data CSV reports. See demo/README.md."

# Non-zero exit if any config failed, so CI / callers can detect partial runs.
[[ ${#failed[@]} -eq 0 ]]
