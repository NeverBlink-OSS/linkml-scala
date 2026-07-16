package eu.neverblink.linkml.benchmark

import org.openjdk.jmh.annotations.*

import java.util.concurrent.TimeUnit

transparent inline def jvmArgs: Array[String] = Array(
  "-server",
  "-Xnoclassgc",
  "-Xms4g", // Preallocate all heap regions & make them fixed-size where possible
  "-Xmx4g",
  "-Xss2m",
  "-XX:NewSize=3g",
  "-XX:MaxNewSize=3g",
  "-XX:InitialCodeCacheSize=512m", // Preallocate all code regions
  "-XX:ReservedCodeCacheSize=512m",
  "-XX:NonNMethodCodeHeapSize=32m",
  "-XX:NonProfiledCodeHeapSize=240m",
  "-XX:ProfiledCodeHeapSize=240m",
  "-XX:TLABSize=4m", // Use large fixed-size TLAB
  "-XX:-ResizeTLAB",
  "-XX:+UseParallelGC", // The fastest GC
  "-XX:+UseCompactObjectHeaders", // Requires JDK 24+
  "-XX:-UseAdaptiveSizePolicy", // Do not resize heap regions
  "-XX:MaxInlineLevel=20", // Helps to speed-up Scala code
  "-XX:InlineSmallCode=2500", // Use defaults from Open JDK 17+
  "-XX:+AlwaysPreTouch", // Pretouch memory pages
  // "-XX:+UseTransparentHugePages", Linux only???
  "-XX:-UseDynamicNumberOfGCThreads", // Stabilize GC & JIT overhead
  "-XX:-UseDynamicNumberOfCompilerThreads",
  "-XX:+UseNUMA", // Relevant for multi-socket servers
  "-XX:-UseAdaptiveNUMAChunkSizing",
  "-XX:+PerfDisableSharedMem", // See https://github.com/Simonis/mmap-pause#readme
  "-XX:-UsePerfData",
  "-XX:+UnlockExperimentalVMOptions",
  "-XX:+TrustFinalNonStaticFields", // It is safe for almost all Scala code
)
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.Throughput))
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(
  iterations = 5,
  time = 1,
  timeUnit = TimeUnit.SECONDS,
) // 5s usually enough for warmup and exiting from "turbo" mode reaching PL2 (Power Limit 2)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(
  value = 1,
  jvmArgs = jvmArgs,
)
abstract class CommonParams
