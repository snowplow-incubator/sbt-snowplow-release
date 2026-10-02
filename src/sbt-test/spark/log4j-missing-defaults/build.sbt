// Genuinely remove Spark's default log4j2 resource from the distribution by
// excluding the one jar that carries it in Spark 4.1.x
// (spark-common-utils-java). This fixture only stages; it never runs Spark,
// which this exclusion would break - hence a fixture of its own.
lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    scalaVersion := "2.13.18",
    sparkLog4jConfigFile := Some(baseDirectory.value / "src" / "spark" / "log4j2-overrides.properties"),
    excludeDependencies += ExclusionRule("org.apache.spark", "spark-common-utils-java_2.13")
  )

// Assert stageSparkLog4jConfig FAILS, with an error message containing the
// given substring. Checking the message (not just that something failed)
// stops an unrelated failure from making the test pass.
InputKey[Unit]("checkStageFails") := {
  val expected = Def.spaceDelimited("<expected message substring>").parsed.mkString(" ")
  stageSparkLog4jConfig.result.value match {
    case Value(v) =>
      sys.error(s"expected stageSparkLog4jConfig to fail with '$expected', but it succeeded with $v")
    case Inc(inc) =>
      val messages = Incomplete.allExceptions(inc).map(_.getMessage).mkString("\n")
      if (!messages.contains(expected))
        sys.error(s"stageSparkLog4jConfig failed, but not with '$expected'. Actual:\n$messages")
  }
}
