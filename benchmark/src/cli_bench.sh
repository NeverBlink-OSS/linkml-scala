#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="${MILL_WORKSPACE_ROOT:-$(pwd)}"
MODELS_DIR="$REPO_ROOT/../linkml-benchmark-schemas"
NATIVE_IMAGE="$REPO_ROOT/out/cli/jvm/nativeImage.dest/linkml-scala"
LINKML_PY="$REPO_ROOT/.venv/bin/linkml"

WARMUP=5
RUNS=50
GENERATORS=(json-schema shacl)
MODELS=(
#  ai-atlas-nexus # schema is fine but python SHACL fails
  brigde2ai_model_card
  cdm
  chem-dcat-ap
  crdch
  d3fend
  fluxnova-bpm
  include
  iso27001
  nmdc_microbiome
  sssom
  tc57cim
)

usage() {
  echo "Usage: $0 [--warmup N] [--runs N] [--export-dir DIR]"
}

EXPORT_DIR="cli-benchmark-results"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --warmup)
      WARMUP="$2"
      shift 2
      ;;
    --runs)
      RUNS="$2"
      shift 2
      ;;
    --export-dir)
      EXPORT_DIR="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

command -v hyperfine >/dev/null 2>&1 || {
  echo "hyperfine not found on PATH. Install it to run this benchmark." >&2
  exit 1
}

[[ -d "$MODELS_DIR" ]] || {
  echo "Did not find the linkml-benchmark-schemas directory in the expected location ($MODELS_DIR)." \
    "Run \`cd ..; git clone https://github.com/NeverBlink-OSS/linkml-benchmark-schemas.git\` to fix" >&2
  exit 1
}

[[ -x "$NATIVE_IMAGE" ]] || {
  echo "No native image executable found at $NATIVE_IMAGE." \
    "Run \`./mill cli.jvm.nativeImage\` to fix" >&2
  exit 1
}

[[ -x "$LINKML_PY" ]] || {
  echo "No python linkml executable found at $LINKML_PY." \
    "Run \`uv venv; source .venv/bin/activate; uv pip install -r requirements.txt\` to fix" >&2
  exit 1
}

if [[ -n "$EXPORT_DIR" ]]; then
  mkdir -p "$EXPORT_DIR"
fi

for model in "${MODELS[@]}"; do
  schema="$MODELS_DIR/$model/main.yaml"
  if [[ ! -f "$schema" ]]; then
    echo "Skipping $model: $schema not found" >&2
    continue
  fi

  for generator in "${GENERATORS[@]}"; do
    echo "Benchmarking $generator on $model" >&2

    export_args=()
    if [[ -n "$EXPORT_DIR" ]]; then
      export_args+=(--export-json "$EXPORT_DIR/${model}-${generator}.json")
      export_args+=(--export-markdown "$EXPORT_DIR/${model}-${generator}.md")
    fi

    hyperfine \
      --warmup "$WARMUP" \
      --runs "$RUNS" \
      "${export_args[@]}" \
      -n nativeImage "$NATIVE_IMAGE generate $generator $schema" \
      -n python "$LINKML_PY generate $generator $schema"
  done
done
