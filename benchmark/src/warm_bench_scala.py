import argparse
import glob
import statistics
import time
import sys
from multiprocessing import Pool

import linkml_scala

# The linkml_scala counterpart of warm_bench.py: same generators, same output format, so the two
# can be compared line for line.
#
# Loads the schemas outside the measured code, as the Python version does. Unlike that one, the
# loading happens inside the worker: a loaded schema is a handle into a GraalVM isolate rather than
# a Python object, so it cannot be pickled across to a forked process.

GENERATORS = {
    "LinkmlScala.jsonSchema": lambda schema: schema.json_schema(),
    "LinkmlScala.shacl": lambda schema: schema.shacl(),
}


def bench_generator(fn, model: str, warmup: int, iterations: int) -> list[float]:
    with linkml_scala.load_file(model) as schema:
        for _ in range(warmup):
            GENERATORS[fn](schema)

        samples = []
        for _ in range(iterations):
            start = time.perf_counter()
            GENERATORS[fn](schema)
            samples.append(time.perf_counter() - start)
        return samples


def bench_forks(fn, model: str, warmup: int, iterations: int, forks: int) -> list[float]:
    with Pool(1, maxtasksperchild=1) as pool:
        results = [
            pool.apply(bench_generator, args=(fn, model, warmup, iterations))
            for i in range(forks)
        ]
    return [el for run in results for el in run]


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Benchmark linkml-scala generators, excluding schema loading/parsing.",
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
        # A dataset laid out as <name>/main.yaml is named for its directory; a plain file is named
        # for the file itself.
        parts = model.split("/")
        name = parts[-2] if parts[-1] in ("main.yaml", "main.yml") else parts[-1].rsplit(".", 1)[0]
        print(f"Processing {name}", file=sys.stderr)

        for gen_name, fn in GENERATORS.items():
            samples = bench_forks(gen_name, model, args.warmup, args.iterations, args.forks)
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
