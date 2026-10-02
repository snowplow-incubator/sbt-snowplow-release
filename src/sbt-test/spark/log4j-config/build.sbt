import java.util.zip.ZipFile
import scala.collection.JavaConverters._

lazy val root = (project in file("."))
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    scalaVersion := "2.13.18",
    name := "snowplow-spark-log4j-config",
    version := "0.1.0",
    Compile / mainClass := Some("example.Main"),
    sparkLog4jConfigFile := Some(baseDirectory.value / "src" / "spark" / "log4j2-overrides.properties")
  )

val configJarMapping = "spark/jars/snowplow-log4j2-config.jar"

def readJar(jar: File): Map[String, Array[Byte]] = {
  val zip = new ZipFile(jar)
  try zip.entries.asScala
    .filterNot(_.isDirectory)
    .map { e =>
      val in = zip.getInputStream(e)
      try e.getName -> in.readAllBytes()
      finally in.close()
    }
    .toMap
  finally zip.close()
}

// Assert the config jar is staged at exactly one mapping under spark/jars/,
// is present in Universal / mappings exactly once (the plugin defines
// Universal / mappings with :=, so it must be added inside that definition),
// and contains exactly the component file (with the verbatim pointer) and the
// app's overrides file, byte for byte.
TaskKey[Unit]("checkLog4jJar") := {
  val staged = stageSparkLog4jConfig.value
  if (staged.map(_._2) != Seq(configJarMapping))
    sys.error(s"expected exactly one staged mapping $configJarMapping, got: ${staged.map(_._2)}")

  val mapped = (Universal / mappings).value.map(_._2).count(_ == configJarMapping)
  if (mapped != 1) sys.error(s"$configJarMapping should appear in Universal / mappings exactly once, found $mapped")

  val entries = readJar(staged.head._1) - "META-INF/MANIFEST.MF"
  val expectedNames = Set("log4j2.component.properties", "snowplow-log4j2-overrides.properties")
  if (entries.keySet != expectedNames)
    sys.error(s"config jar entries mismatch.\n  expected: $expectedNames\n  actual:   ${entries.keySet}")

  val pointer = new String(entries("log4j2.component.properties"), "UTF-8").linesIterator
    .map(_.trim)
    .filter(l => l.nonEmpty && !l.startsWith("#"))
    .toList
  val expectedPointer = List(
    "log4j2.configurationFile = classpath:org/apache/spark/log4j2-defaults.properties,classpath:snowplow-log4j2-overrides.properties"
  )
  if (pointer != expectedPointer)
    sys.error(s"component file mismatch.\n  expected: $expectedPointer\n  actual:   $pointer")

  val source = IO.readBytes(sparkLog4jConfigFile.value.get)
  if (!java.util.Arrays.equals(entries("snowplow-log4j2-overrides.properties"), source))
    sys.error(
      "overrides file in the jar is not byte-identical to the source.\n" +
        s"  source: ${new String(source, "UTF-8")}\n" +
        s"  jar:    ${new String(entries("snowplow-log4j2-overrides.properties"), "UTF-8")}"
    )
}

// Assert that with the setting off, nothing is staged and nothing is mapped,
// including no stale jar left over from an earlier run with the setting on.
TaskKey[Unit]("checkNoLog4jJar") := {
  val staged = stageSparkLog4jConfig.value
  if (staged.nonEmpty) sys.error(s"expected nothing staged with sparkLog4jConfigFile := None, got: $staged")
  val leaked = (Universal / mappings).value.map(_._2).filter(_.contains("log4j2-config"))
  if (leaked.nonEmpty) sys.error(s"expected no log4j2 config jar in Universal / mappings, got: $leaked")
}

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
