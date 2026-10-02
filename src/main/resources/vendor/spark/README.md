# Vendored Apache Spark scripts

These are third-party Apache Spark files, not Snowplow code, and keep their
original ASF license headers. They are vendored because, unlike Spark's jars,
they are not published to Maven.

Provenance differs by file, and it matters:

- **`bin/` scripts** (`spark-submit`, `spark-class`, `load-spark-env.sh`):
  copied verbatim from the official Spark tarball `spark-4.2.0-bin-hadoop3.tgz`
  (`bin/`). These are generic launchers, identical across the tarball and the
  published image. This is the minimal driver-launch closure: the entrypoint's
  driver branch execs `spark-submit` → `spark-class` → sources
  `load-spark-env.sh`. `find-spark-home` is intentionally omitted — the launchers
  only source it under `if [ -z "${SPARK_HOME}" ]`, and the image always sets
  `SPARK_HOME=/opt/spark`, so that branch is never taken. (Executors don't use
  the `bin/` scripts at all; they exec `java` directly.)

- **`entrypoint.sh`**: copied verbatim from the **`apache/spark-docker`** repo
  (`4.2.0/scala2.13-java17-ubuntu/entrypoint.sh`) — i.e. the entrypoint the
  *published* `apache/spark:4.2.0` image ships, **not** the tarball's
  `kubernetes/dockerfiles/spark/entrypoint.sh`. This is deliberate: the tarball /
  Spark-source entrypoint writes a `java_opts.txt` file into the current working
  directory, which fails under `readOnlyRootFilesystem: true` (the CWD is a
  read-only image layer) and crash-fails the pod. The published-image entrypoint
  assembles the executor Java opts in memory (`for v in "${!SPARK_JAVA_OPT_@}"`)
  and writes nothing to the CWD, so it runs under a read-only root filesystem —
  which is what our production Spark-on-k8s pods use, and what the current RDB
  transformer image already relies on. The `spark/smoke` sbt-test's `executorProbe`
  task guards against regressing to a CWD-writing entrypoint, and also drives
  the entrypoint's executor branch end to end (it sets the `SPARK_EXECUTOR_*` /
  `SPARK_DRIVER_URL` env vars this entrypoint reads, so a re-vendor that renames
  any of them will fail it).

`decom.sh` is intentionally **not** vendored: it is only used for executor
decommissioning (disabled by default, and unused by our batch workloads), and
nothing in the entrypoint or the `bin/` scripts references it.

Re-vendor when the plugin's `sparkVersion` changes: `bin/` from the matching
tarball, and `entrypoint.sh` from `apache/spark-docker` at the matching tag
(never from the tarball's `kubernetes/dockerfiles/`).

When bumping `sparkVersion`, note that `sparkLog4jConfigFile` points log4j2 at
Spark's `org/apache/spark/log4j2-defaults.properties` classpath resource. If a
Spark release renames it, any build with `sparkLog4jConfigFile` set fails with
a message saying so, and the pointer in `SnowplowSparkPlugin` must be updated.
