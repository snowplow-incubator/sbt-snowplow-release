lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(scalaVersion := "2.13.18")

TaskKey[Unit]("checkStaged") := {
  val mappings = stageSparkScripts.value
  val byPath = mappings.map(_._2).toSet
  Seq(
    "spark/bin/spark-submit",
    "spark/bin/spark-class",
    "spark/bin/load-spark-env.sh",
    "spark/entrypoint.sh"
  ).foreach { p =>
    if (!byPath.contains(p)) sys.error(s"missing staged mapping: $p")
  }
  mappings.foreach { case (f, p) =>
    if (!f.canExecute) sys.error(s"staged file not executable: $p")
  }
  val entry = mappings.collectFirst { case (f, "spark/bin/spark-submit") => f }.get
  if (!entry.canExecute) sys.error("spark-submit not executable")
}
