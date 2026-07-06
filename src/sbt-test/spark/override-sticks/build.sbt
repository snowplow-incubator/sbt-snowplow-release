lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    scalaVersion := "2.13.18"
  )

// Assert the netty forward-patch actually landed on the resolved Spark
// distribution: every io.netty jar except netty-tcnative-* (its own 2.0.x
// scheme) and netty-all (an empty aggregator jar, deliberately left unpinned
// at Spark's baseline - see SnowplowSparkPlugin's netty comment) must be the
// pinned 4.2.15.Final.
//
// Spark is now Provided (compile-only, not on the Runtime classpath), so the
// distribution's netty jars only exist in the dedicated SparkDistribution
// config - that's where this must look, not Runtime/dependencyClasspath.
TaskKey[Unit]("checkNetty") := {
  val jarNames = update.value
    .select(configurationFilter("spark-distribution"))
    .map(_.getName)
    .filter(n => n.startsWith("netty-") && !n.startsWith("netty-tcnative") && !n.startsWith("netty-all"))
  if (jarNames.isEmpty) sys.error("No netty jars on the spark-distribution classpath")
  val wrong = jarNames.filterNot(_.contains("4.2.15.Final"))
  if (wrong.nonEmpty)
    sys.error(s"Netty override did not stick; found: ${wrong.mkString(", ")}")
  streams.value.log.info(s"netty override OK: ${jarNames.mkString(", ")}")
}
