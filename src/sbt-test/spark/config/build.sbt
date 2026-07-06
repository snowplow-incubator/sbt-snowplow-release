lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    scalaVersion := "2.13.18",
    name := "snowplow-spark-config",
    version := "0.1.0",
    Docker / packageName := "snowplow-spark-config",
    Compile / mainClass := Some("example.Main"),
    // Intrinsic, app-owned conf: two entries so we can assert ordering and
    // that a value containing an '=' round-trips intact.
    sparkConfig := Map(
      "spark.hadoop.fs.s3a.aws.credentials.provider" -> "example.MyCredentialsProvider",
      "spark.app.marker" -> "abc=def"
    )
  )

// Assert sparkConfig renders into the staged spark-defaults.conf as `key value`
// lines (space-separated, Spark's format), sorted by key, one per entry.
TaskKey[Unit]("checkSparkConf") := {
  val (file, mapping) = stageSparkDefaults.value
  if (mapping != "spark/conf/spark-defaults.conf")
    sys.error(s"unexpected spark-defaults mapping: $mapping")
  val lines = IO.readLines(file).filter(_.trim.nonEmpty)
  val expected = List(
    "spark.app.marker abc=def",
    "spark.hadoop.fs.s3a.aws.credentials.provider example.MyCredentialsProvider"
  )
  if (lines != expected)
    sys.error(s"spark-defaults.conf mismatch.\n  expected: $expected\n  actual:   $lines")
}

// Assert the natural assembly jar name is preserved (not renamed to app.jar)
// and that the generated Snowplow entrypoint bakes in the main class and that
// exact jar name.
TaskKey[Unit]("checkEntrypoint") := {
  val paths = (Universal / mappings).value.map(_._2).toSet
  if (paths.contains("snowplow/app.jar"))
    sys.error("app jar must keep its natural assembly name, not app.jar")
  val jarName = (assembly / assemblyJarName).value
  if (!paths.contains(s"snowplow/$jarName"))
    sys.error(s"app jar not mapped at snowplow/$jarName; mappings under snowplow/: " +
      paths.filter(_.startsWith("snowplow/")).mkString(", "))

  val (script, mapping) = stageSparkEntrypoint.value
  if (mapping != "snowplow/entrypoint.sh")
    sys.error(s"unexpected entrypoint mapping: $mapping")
  if (!script.canExecute) sys.error("generated entrypoint is not executable")
  val body = IO.read(script)
  val q = "\""
  if (!body.contains(s"--class ${q}example.Main${q}"))
    sys.error(s"entrypoint does not reference the main class:\n$body")
  if (!body.contains(s"${q}local:///opt/snowplow/$jarName${q}"))
    sys.error(s"entrypoint does not reference the jar path local:///opt/snowplow/$jarName:\n$body")
  if (!body.contains("executor | history-server"))
    sys.error(s"entrypoint missing the executor/history-server passthrough:\n$body")
}
