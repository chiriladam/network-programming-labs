#!/usr/bin/env bash
# Linux/macOS version of run_task1.ps1:  ./scripts/run_task1.sh [repeats] [--extras]
set -euo pipefail
cd "$(dirname "$0")/.."
REPEATS="${1:-3}"
EXTRAS="${2:-}"
JAVA_OPTS="${JAVA_OPTS:--Xmx2g}"

mkdir -p out && javac -d out src/*.java
lab() { java $JAVA_OPTS -cp out Lab1 "$@"; }

LOGICAL=$(java -cp out Lab1 info | awk '/Logical processors/ {print $3}')
[[ -f numbers.txt && -f numbers.txt.counts ]] || lab gen
[[ -f parts/part8.txt ]] || lab split
[[ -f results.csv ]] && mv results.csv "results_old_$(date +%Y%m%d_%H%M%S).csv"

lab 1a --threads 1 --csv warmup.csv > /dev/null    # warm the file cache

for ((r = 1; r <= REPEATS; r++)); do
  echo "===== Round $r of $REPEATS ====="
  for t in 1 2 4 8 16 32 64; do lab 1a --threads "$t" --run "$r"; done
  if (( REPEATS >= 3 )); then v1=1; else v1=$(( (3 + REPEATS - 1) / REPEATS )); fi
  for ((k = 1; k <= v1; k++)); do lab 1b --version 1 --threads "$LOGICAL" --run "$r.$k"; done
  lab 1b --version 2 --threads "$LOGICAL" --run "$r"
  lab 1b --version 3 --threads "$LOGICAL" --run "$r"
  for w in 1 2 4 8 16 32 64; do lab 1c --workers "$w" --run "$r"; done
done

if [[ "$EXTRAS" == "--extras" ]]; then
  lab 1c --workers 4 --batch 1
  lab 1c --workers 1 --capacity 0
  lab 1c --workers 4 --capacity 0
fi
echo "Done. Results: results.csv"
