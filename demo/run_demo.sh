#!/usr/bin/env bash
# End-to-end demo / smoke test for wichtelm-app.
#
# Builds the executable JAR from the current source tree, regenerates the
# synthetic CSV data, validates the demo strategy, and runs the backtest.
# A fresh timestamped HTML report is written to demo/reports/.
#
# The build runs every time so the smoke test always exercises current
# source. Set WICHTELM_SKIP_BUILD=1 to reuse an existing JAR for quick
# data-only iteration.
#
# Run from the repository root:
#   ./demo/run_demo.sh
set -euo pipefail

cd "$(dirname "$0")/.."

JAR=target/wichtelm.jar

if [[ "${WICHTELM_SKIP_BUILD:-0}" == "1" && -f "$JAR" ]]; then
  echo "==> WICHTELM_SKIP_BUILD=1 — reusing existing $JAR"
else
  echo "==> building $JAR from source"
  mvn -q clean package -DskipTests
fi

echo "==> regenerating synthetic OHLC data (TSTX at 1h/4h/1d/1w)"
java demo/GenerateData.java

echo "==> validating a demo strategy"
java -jar "$JAR" validate demo/strategies/swing-multi-tf.strat

echo "==> running every CSV demo"
for cfg in demo/tstx-*.toml; do
  echo "  - $cfg"
  java -jar "$JAR" run "$cfg"
done

echo "==> done — see the HTML reports under demo/reports/"
