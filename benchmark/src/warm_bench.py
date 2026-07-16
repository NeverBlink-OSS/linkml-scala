import argparse
import glob
import statistics
import time
import sys
from multiprocessing import Pool

from linkml.generators.jsonschemagen import JsonSchemaGenerator
from linkml.generators.shaclgen import ShaclGenerator
from linkml_runtime import SchemaView

# Benchmark script for measuring the post-parsing performance of linkml generators.
# Loads the schemas provided by a glob pattern including imports outside the measured code.
# Collects time to execute and runs simple aggregates

GENERATORS = {
    "LinkmlPython.jsonSchema": lambda schema: JsonSchemaGenerator(schema).serialize(),
    "LinkmlPython.shacl": lambda schema: ShaclGenerator(schema).serialize(),
}

def bench_generator(fn, schema, warmup: int, iterations: int) -> list[float]:
    for _ in range(warmup):
        GENERATORS[fn](schema)

    samples = []
    for _ in range(iterations):
        start = time.perf_counter()
        GENERATORS[fn](schema)
        samples.append(time.perf_counter() - start)
    return samples


def bench_forks(fn, schema, warmup: int, iterations: int, forks: int) -> list[float]:
    with Pool(1, maxtasksperchild=1) as pool:
        results = [
            pool.apply(bench_generator, args = (fn, schema, warmup, iterations))
            for i in range(forks)
        ]
    return [el for run in results for el in run]

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark linkml generators, excluding schema loading/parsing.",
    )
    parser.add_argument(
        "--models-glob",
        default="benchmark/resources/linkml-datasets/*/main.yaml",
        help="Glob pattern for schema files to benchmark (default: %(default)s)",
    )
    parser.add_argument("--warmup", type=int, default=5, help="Warmup iterations per generator")
    parser.add_argument("--iterations", type=int, default=10, help="Measured iterations per generator")
    parser.add_argument("--forks", type=int, default=5, help="Number of forks to run in parallel")
    args = parser.parse_args()

    models = sorted(glob.glob(args.models_glob))
    if not models:
        raise SystemExit(f"No schemas matched glob: {args.models_glob}")

    print(f"{'Benchmark':<30}{'(model)':<30}{'Mode':<10}{'Cnt':<10}{'Score':>12}{'Error':>12}{'Units':>10}")
    for model in models:
        name = model.split("/")[-2]
        print(f"Processing {name}", file=sys.stderr)

        # Schema loading/parsing happens once here (hopefully) and is not part of the measured section below.
        sv = SchemaView(model, merge_imports=True)

        for gen_name, fn in GENERATORS.items():
            samples = bench_forks(gen_name, sv.schema, args.warmup, args.iterations, args.forks)
            # Throughput per sample (ops/s); stdev assumes the per-sample throughput is normally
            # distributed around the mean.
            ops = [1.0 / s for s in samples]
            stdev = statistics.stdev(ops) if len(ops) > 1 else 0.0
            print(
                f"{gen_name:<30}{name:<30}{'thrpt':<10}{args.iterations * args.forks:<10}"
                f"{statistics.mean(ops):>12.4f}"
                f"{stdev:>12.4f}"
                f"{'ops/s':>10}",
            )


if __name__ == "__main__":
    main()
