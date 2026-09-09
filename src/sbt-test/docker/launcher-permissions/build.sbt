lazy val root = (project in file("."))
  .enablePlugins(SnowplowDockerPlugin, JavaAppPackaging)
  .settings(
    scalaVersion := "2.13.18",
    name := "launcher-permissions",
    version := "0.1.0",
    Docker / packageName := "launcher-permissions",
    Compile / mainClass := Some("example.Main")
  )

lazy val image = settingKey[String]("Tag of the image built from the staged context")

image := s"snowplow/${(Docker / packageName).value}:${version.value}"

// Reproduce what a GitHub Actions artifact round-trip does to the staged docker
// context: every file comes back mode 644, so the launcher script is no longer
// executable on the host side. Deliberately NOT wired to `Docker / stage` as a
// dependency - re-running the staging task would restore the executable bit.
TaskKey[Unit]("stripFileModes") := {
  val stageDir = (Docker / com.typesafe.sbt.packager.Keys.stagingDirectory).value
  val files = (stageDir ** "*").get.filter(_.isFile)
  if (files.isEmpty) sys.error(s"no staged files found under $stageDir - did Docker/stage run?")
  files.foreach { f =>
    f.setExecutable(false, false)
    if (f.canExecute) sys.error(s"could not clear the executable bit on $f")
  }
}

// Build the mode-stripped context by shelling out to `docker build`, exactly as
// our CI does. Going through `Docker / publishLocal` instead would re-stage the
// context first and undo `stripFileModes`.
TaskKey[Unit]("buildStagedContext") := {
  import scala.sys.process._
  val log = streams.value.log
  val cmd = Seq("docker", "build", "-t", image.value, (Docker / com.typesafe.sbt.packager.Keys.stagingDirectory).value.getAbsolutePath)
  log.info(cmd.mkString(" "))
  val code = cmd.!
  if (code != 0) sys.error(s"docker build failed with exit code $code")
}

// The regression: with a permission strategy that inherits the host file mode,
// the entrypoint is not executable and the container dies with exit 126
// ("permission denied") before the JVM ever starts.
TaskKey[Unit]("runStagedImage") := {
  import scala.sys.process._
  val log = streams.value.log
  val cmd = Seq("docker", "run", "--rm", image.value)
  log.info(cmd.mkString(" "))
  val out = new StringBuilder
  val code = cmd ! ProcessLogger(line => out.append(line).append('\n'))
  if (code != 0)
    sys.error(s"the staged image failed to start (exit code $code) - the launcher script is likely not executable:\n$out")
  if (!out.toString.contains("launcher-permissions-ok"))
    sys.error(s"the app did not run as expected:\n$out")
}
