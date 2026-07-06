lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    scalaVersion := "2.13.18",
    name := "snowplow-spark-smoke",
    version := "0.1.0",
    Docker / packageName := "snowplow-spark-smoke",
    Compile / mainClass := Some("example.Main"),
    // The app declares its OWN Spark compile dependency, Provided (so it compiles
    // against Spark but Spark is not bundled into the fat jar - the distribution
    // supplies it at runtime), using the plugin's sparkVersion so the compile
    // version matches what ships. The plugin does NOT inject this: compilation is
    // the app's responsibility, packaging the distribution is the plugin's.
    libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion.value % Provided,
    // The app's own, non-Spark dependency. cats-core is deliberately chosen
    // because Spark is a Java project and ships no cats-* jar in its distribution
    // closure, so if Main uses cats and the job runs, the only place cats could
    // have come from is the app fat jar.
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.13.0"
  )

// Assert the staged docker mappings describe the expected distribution layout,
// AND that the two halves (Spark distribution vs. app + its own deps) are
// actually kept separate.
TaskKey[Unit]("checkLayout") := {
  val allMappings = (Universal / mappings).value
  val paths = allMappings.map(_._2).toSet
  if (paths.contains("snowplow/app.jar")) sys.error("app jar must keep its natural assembly name, not app.jar")
  if (!paths.exists(p => p.startsWith("snowplow/") && p.endsWith(".jar")))
    sys.error("app jar not mapped under snowplow/")
  if (!paths.contains("snowplow/entrypoint.sh")) sys.error("snowplow/entrypoint.sh (self-deploying wrapper) not mapped")
  if (!paths.contains("spark/entrypoint.sh")) sys.error("vendored spark/entrypoint.sh not mapped")
  val sparkJars = paths.count(p => p.startsWith("spark/jars/") && p.endsWith(".jar"))
  if (sparkJars < 100) sys.error(s"expected the full Spark jar closure under spark/jars/, found $sparkJars")
  val appJarInSparkJars = paths.exists(p => p.startsWith("spark/jars/") && p.contains("snowplow-spark-smoke"))
  if (appJarInSparkJars) sys.error("app jar must NOT be under spark/jars/")
  // The app's own dependency (cats-core) must live only in the fat jar, never
  // in the Spark distribution. Spark ships no cats-* jar, so a bare module-name
  // check is both correct and clean here.
  val catsInSparkJars = paths.exists(p => p.startsWith("spark/jars/") && p.split('/').last.startsWith("cats-"))
  if (catsInSparkJars) sys.error("app's own dependency (cats-*) must NOT leak into spark/jars/")
  // The smoke fixture uses the default (empty) sparkConfig, so the rendered
  // spark-defaults.conf should be empty - exercises the empty-map path.
  val defaultsFile = allMappings
    .collectFirst { case (f, "spark/conf/spark-defaults.conf") => f }
    .getOrElse(sys.error("spark/conf/spark-defaults.conf not mapped"))
  if (IO.read(defaultsFile).trim.nonEmpty)
    sys.error("default (empty) sparkConfig should render an empty spark-defaults.conf")
}

// Runs the built image as the non-root nobody user (UID 65534), driving a
// shuffle job via local-cluster so netty block transfer and jackson
// serialization across real executor JVMs are exercised.
TaskKey[Unit]("smokeRun") := {
  import scala.sys.process._
  val log = streams.value.log
  val image = s"snowplow/${(Docker / packageName).value}:${version.value}"

  // Belt-and-braces: assert the plugin's dockerCommands ENV transform actually
  // landed. If native-packager ever stops emitting a WORKDIR command, the
  // transform in SnowplowSparkPlugin silently skips inserting SPARK_HOME/PATH,
  // and spark-submit would fail later with an obscure "command not found"
  // rather than a clear signal about the missing env.
  val envCheckCmd = Seq(
    "docker", "run", "--rm", "--user", "65534", "--entrypoint", "sh", image,
    "-c", "[ \"$SPARK_HOME\" = /opt/spark ] && echo \"$PATH\" | grep -q /opt/spark/bin"
  )
  log.info(envCheckCmd.mkString(" "))
  val envCheckCode = envCheckCmd.!
  if (envCheckCode != 0)
    sys.error(s"SPARK_HOME/PATH env check failed with exit code $envCheckCode - dockerCommands ENV transform may have regressed")

  val containerName = "snowplow-spark-smoke-run"
  val cmd = Seq(
    "docker", "run", "--rm", "--name", containerName, "--user", "65534",
    // local-cluster runs the driver AND both executors inside this one
    // container, all talking over the driver's RPC port. Force the driver to
    // bind and advertise loopback so the in-container executors can actually
    // reach it - without this the driver advertises the container hostname but
    // binds elsewhere, executors get "Connection refused", and each failed
    // registration resets the standalone retry counter into an infinite
    // relaunch loop. SPARK_DRIVER_BIND_ADDRESS feeds the entrypoint's
    // --conf spark.driver.bindAddress; SPARK_LOCAL_IP pins block-manager
    // endpoints; spark.driver.host fixes the advertised address.
    "-e", "SPARK_DRIVER_BIND_ADDRESS=127.0.0.1",
    "-e", "SPARK_LOCAL_IP=127.0.0.1",
    // NOTE: deliberately NO `-w` here. Running with the image's own WORKDIR
    // (/opt/spark/work-dir, created nobody-writable by the plugin) proves the
    // non-root posture end to end, exactly as k8s driver/executor pods do.
    image,
    // Self-deploying: no `driver`, no `--class`, no jar path. These are all
    // spark-submit options, and the wrapper entrypoint synthesises the rest.
    // This exercises the real production entrypoint path end to end.
    "--master", "local-cluster[2,1,1024]",
    "--conf", "spark.driver.host=127.0.0.1",
    // Defensive fail-fast: cap standalone executor relaunches so a future
    // misconfig surfaces as a job failure rather than an infinite loop.
    "--conf", "spark.deploy.maxExecutorRetries=2",
    // Sentinel + application arg: proves the wrapper entrypoint routes
    // pre-"--" tokens to spark-submit (the job runs) and post-"--" tokens to
    // the application (Main's require passes). Without the split working, the
    // job would either fail to submit or Main would abort.
    "--", "--smoke-marker"
  )
  log.info(cmd.mkString(" "))

  // Hard timeout in the task itself (no reliance on a `timeout` binary, which
  // is not present on macOS). A correct run finishes in well under 2 minutes;
  // if we blow past the deadline something is wrong (e.g. an executor relaunch
  // loop), so force-kill the container and the process and fail loudly.
  val deadlineMs = 300000L
  val proc       = cmd.run(true)
  val started    = System.currentTimeMillis()
  while (proc.isAlive() && System.currentTimeMillis() - started < deadlineMs)
    Thread.sleep(1000L)
  if (proc.isAlive()) {
    Seq("docker", "kill", containerName).!
    proc.destroy()
    sys.error(s"spark smoke job timed out after ${deadlineMs / 1000}s - killed container $containerName")
  }
  val code = proc.exitValue()
  if (code != 0) sys.error(s"spark smoke job failed with exit code $code")
}

// Read-only-root-filesystem regression guard. The production k8s driver pod
// runs with `readOnlyRootFilesystem: true` and only /tmp mounted writable, so
// the vendored entrypoint must NOT write anything into its CWD (a read-only
// image layer). We vendor Apache Spark's *published-image* entrypoint (in-memory
// SPARK_JAVA_OPT_ expansion) precisely because the source-tree/tarball variant
// wrote `java_opts.txt` into the CWD and crash-failed under this posture.
//
// This drives the `executor` branch under `docker run --read-only`, which hits
// the same entrypoint preamble as the driver but fails fast (no driver URL) once
// it reaches the JVM - all we assert is that the read-only CWD is never touched,
// i.e. the output never contains "Read-only file system". A regression to the
// CWD-writing entrypoint would reintroduce that error and fail this guard.
TaskKey[Unit]("readOnlyRun") := {
  import scala.sys.process._
  val log = streams.value.log
  val image = s"snowplow/${(Docker / packageName).value}:${version.value}"
  val cmd = Seq("docker", "run", "--rm", "--read-only", "--user", "65534", image, "executor")
  log.info(cmd.mkString(" "))
  val out = new StringBuilder
  val logger = ProcessLogger(line => out.append(line).append('\n'))
  cmd.run(logger).exitValue() // expected non-zero: executor bails without a driver, that's fine
  if (out.toString.toLowerCase.contains("read-only file system"))
    sys.error(s"entrypoint wrote to its read-only CWD - the CWD-writing entrypoint has regressed:\n$out")
}
