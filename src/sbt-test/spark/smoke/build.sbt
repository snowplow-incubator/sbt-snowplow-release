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
    libraryDependencies += "org.typelevel" %% "cats-core" % "2.13.0",
    // Opts the smoke image into the log4j2 config layering, so smokeRun and
    // executorProbe can assert it end to end in both JVM kinds.
    sparkLog4jConfigFile := Some(baseDirectory.value / "src" / "spark" / "log4j2-overrides.properties")
  )

// Runs a docker command to completion under a hard deadline, echoing its
// combined stdout/stderr to the sbt log and returning it alongside the exit
// code. No reliance on a `timeout` binary (absent on macOS): past the deadline
// the named container is killed and the task fails loudly.
def runContainer(cmd: Seq[String], containerName: String, deadlineMs: Long, log: sbt.util.Logger): (Int, String) = {
  import scala.sys.process._
  // Clear any container left behind by an earlier run that was killed before
  // `--rm` fired; otherwise `--name` clashes and the run fails for the wrong
  // reason. Output and exit code ignored: usually there's nothing to remove.
  Seq("docker", "rm", "-f", containerName).!(ProcessLogger(_ => ()))
  log.info(cmd.mkString(" "))
  // StringBuffer, not StringBuilder: stdout and stderr lines arrive on separate
  // threads. Each line is appended in ONE call so the two streams can't
  // interleave mid-line (which would break line-anchored assertions).
  val out = new StringBuffer
  val proc = cmd.run(ProcessLogger { line =>
    log.info(line)
    out.append(line + "\n")
    ()
  })
  val started = System.currentTimeMillis()
  while (proc.isAlive() && System.currentTimeMillis() - started < deadlineMs)
    Thread.sleep(500L)
  if (proc.isAlive()) {
    Seq("docker", "kill", containerName).!
    proc.destroy()
    sys.error(s"container $containerName timed out after ${deadlineMs / 1000}s - killed it")
  }
  (proc.exitValue(), out.toString)
}

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
  if (!paths.contains("spark/jars/snowplow-log4j2-config.jar"))
    sys.error("spark/jars/snowplow-log4j2-config.jar (log4j2 config layering) not mapped")
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

  // Hard deadline via runContainer: a correct run finishes in well under 2
  // minutes; past the deadline something is wrong (e.g. an executor relaunch
  // loop), so the container is killed and the task fails.
  val (code, output) = runContainer(cmd, containerName, 300000L, log)
  if (code != 0) sys.error(s"spark smoke job failed with exit code $code")

  // log4j2 layering, driver JVM. (local-cluster executors write to files under
  // the worker dir, not to this output; executorProbe covers executors.)
  def check(cond: Boolean, msg: String): Unit =
    if (!cond) sys.error(s"smokeRun (driver): $msg\n--- driver output ---\n$output")
  check(
    !output.contains("Using Spark's default log4j profile"),
    "Spark fell back to its default log4j profile - the log4j2 config jar was not picked up"
  )
  check(!output.contains("INFO SecurityManager:"), "SecurityManager INFO lines present - the level override did not apply")
  // Scoped to SparkContext: local-cluster's in-process Master and Worker log
  // the same message under their own loggers, and must keep it.
  check(
    !output.contains("SparkContext: Running Spark version"),
    "SparkContext 'Running Spark version' present - the SparkContext RegexFilter did not apply"
  )
  // Spark's own pattern (%d{yy/MM/dd HH:mm:ss} %p %c{1}: %m) on a surviving
  // SparkContext INFO line proves the defaults were layered underneath, not lost.
  val sparkPatternInfo = """(?m)^\d{2}/\d{2}/\d{2} \d{2}:\d{2}:\d{2} INFO SparkContext: """.r
  check(
    sparkPatternInfo.findFirstIn(output).isDefined,
    "no SparkContext INFO line in Spark's pattern - Spark's default config was lost, not layered"
  )
}

// Executor-path probe. Production k8s executor pods run this same image with
// args `executor` (the executor pod template sets no command/args), under
// readOnlyRootFilesystem as UID 65534. That path is: our wrapper's executor
// passthrough -> the vendored /opt/spark/entrypoint.sh executor branch -> tini
// -> java -cp $SPARK_CLASSPATH ...KubernetesExecutorBackend. The smoke job's
// local-cluster executors bypass all of that (they're forked by an in-process
// standalone Worker), so this is the only check of it.
//
// We set the env vars Spark's k8s backend would set on an executor pod and
// point the executor at a dead driver URL. It then gets as far as starting the
// backend, initialising logging, and attempting the driver RPC connection,
// which fails with "Connection refused" in ~2s. Exit code is non-zero by
// design; we assert on the output instead.
//
// Also the read-only-root-filesystem regression guard: we vendor Apache
// Spark's *published-image* entrypoint (in-memory SPARK_JAVA_OPT_ expansion)
// because the tarball variant wrote `java_opts.txt` into the CWD and
// crash-failed under that posture.
//
// The env var names are the ones the vendored entrypoint.sh reads; if a Spark
// re-vendor renames one, this probe fails loudly, which is what we want.
TaskKey[Unit]("executorProbe") := {
  val log = streams.value.log
  val image = s"snowplow/${(Docker / packageName).value}:${version.value}"
  val containerName = "snowplow-spark-executor-probe"
  val env = Seq(
    "SPARK_EXECUTOR_MEMORY" -> "512m",
    "SPARK_EXECUTOR_CORES" -> "1",
    "SPARK_EXECUTOR_ID" -> "1",
    "SPARK_APPLICATION_ID" -> "spark-probe",
    "SPARK_EXECUTOR_POD_IP" -> "127.0.0.1",
    "SPARK_RESOURCE_PROFILE_ID" -> "0",
    "SPARK_EXECUTOR_POD_NAME" -> "probe-exec-1",
    "SPARK_DRIVER_URL" -> "spark://CoarseGrainedScheduler@127.0.0.1:1"
  ).flatMap { case (k, v) => Seq("-e", s"$k=$v") }
  val cmd = Seq("docker", "run", "--rm", "--name", containerName, "--read-only", "--user", "65534") ++
    env ++ Seq(image, "executor")
  val (_, output) = runContainer(cmd, containerName, 120000L, log)
  def check(cond: Boolean, msg: String): Unit =
    if (!cond) sys.error(s"executorProbe: $msg\n--- executor output ---\n$output")

  check(
    !output.toLowerCase.contains("read-only file system"),
    "entrypoint wrote to its read-only CWD - the CWD-writing entrypoint has regressed"
  )
  check(
    output.contains("KubernetesExecutorBackend: Started daemon"),
    "KubernetesExecutorBackend never started - the executor passthrough, entrypoint, tini, classpath or env var names have regressed"
  )
  check(
    output.contains("Connection refused"),
    "executor did not reach the driver-connection stage - it failed earlier than expected"
  )

  // log4j2 layering, on the real k8s executor path. "Started daemon" (checked
  // above) also proves Spark's defaults survived: if the defaults resource were
  // lost, log4j2 would drop every INFO line, that one included.
  check(
    !output.contains("Using Spark's default log4j profile"),
    "Spark fell back to its default log4j profile - the log4j2 config jar was not picked up"
  )
  check(!output.contains("INFO SecurityManager:"), "SecurityManager INFO lines present - the level override did not apply")
  check(!output.contains("for HUP"), "SignalUtils HUP line present - the RegexFilter did not apply")
  check(
    output.contains("Registering signal handler for TERM"),
    "SignalUtils TERM line missing - the HUP RegexFilter is over-matching"
  )
}
