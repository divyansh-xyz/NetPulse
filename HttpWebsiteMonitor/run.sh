#!/bin/bash
# ─────────────────────────────────────────────
# NetPulse — Build & Run Script
# ─────────────────────────────────────────────
# Usage:
#   ./run.sh          # Compile and run on default port 8080
#   ./run.sh 9090     # Compile and run on port 9090
#   ./run.sh build    # Compile only (no run)
# ─────────────────────────────────────────────

set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$PROJECT_DIR/src"
OUT_DIR="$PROJECT_DIR/out"
PORT="${1:-8080}"

echo ""
echo "  ╔══════════════════════════════════════╗"
echo "  ║       NetPulse — Build System        ║"
echo "  ╚══════════════════════════════════════╝"
echo ""

# ── Step 1: Compile ──
echo "[Build] Compiling Java sources..."
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

# Find all .java files and compile
find "$SRC_DIR" -name "*.java" | xargs javac -d "$OUT_DIR"

echo "[Build] Compilation successful → $OUT_DIR"

# ── Step 2: Run (unless 'build' argument was passed) ──
if [ "$1" = "build" ]; then
    echo "[Build] Build-only mode. Exiting."
    exit 0
fi

echo "[Run]   Starting NetPulse on port $PORT..."
echo ""

cd "$PROJECT_DIR"
java -cp "$OUT_DIR" Main "$PORT"

