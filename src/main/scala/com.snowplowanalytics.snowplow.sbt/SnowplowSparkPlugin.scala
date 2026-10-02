/*
 * Copyright (c) 2022-2026 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.sbt

import sbt._
import sbt.Keys._
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport._
import com.typesafe.sbt.packager.universal.UniversalPlugin.autoImport.Universal
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.defaultLinuxInstallLocation
import sbtassembly.AssemblyPlugin.autoImport._

object SnowplowSparkPlugin extends AutoPlugin {

  object autoImport {
    val sparkVersion = settingKey[String]("Apache Spark version to bundle")
    val stageSparkScripts = taskKey[Seq[(File, String)]]("Stage vendored Spark scripts and return docker mappings")
    val sparkConfig = settingKey[Map[String, String]](
      "Intrinsic Spark configuration rendered into the image's spark-defaults.conf"
    )
    val stageSparkDefaults = taskKey[(File, String)](
      "Generate spark-defaults.conf from sparkConfig and return its docker mapping"
    )
    val stageSparkEntrypoint = taskKey[(File, String)](
      "Generate the self-deploying Snowplow entrypoint and return its docker mapping"
    )
    val sparkLog4jConfigFile = settingKey[Option[File]](
      "log4j2 properties file layered on top of Spark's default log4j2 config in the image"
    )
    val stageSparkLog4jConfig = taskKey[Seq[(File, String)]](
      "Package sparkLog4jConfigFile into a log4j2 config jar and return its docker mapping (empty when unset)"
    )

    // Dedicated Ivy configuration used to resolve the Spark
    // distribution (spark-sql + spark-kubernetes and their patched transitive
    // closure) independently of the app's own libraryDependencies. Kept separate
    // from Compile/Runtime so a consuming app's non-Spark deps can never leak
    // into /opt/spark/jars/. (The other half of the separation - keeping Spark
    // out of the app's fat jar - is the app's own doing: it declares its Spark
    // compile deps as Provided.)
    val SparkDistribution = config("spark-distribution").hide
  }

  import autoImport._

  override def requires: Plugins = SnowplowDockerPlugin && DockerPlugin

  // Default versions the plugin bundles into the Spark distribution. `spark` is the Spark version itself;
  // the rest are third-party libraries we forward-patch to mitigate CVEs.
  //
  // ALL of these are defaults a consuming app can override - only the mechanism
  // differs: `spark` is exposed as the `sparkVersion` setting (override with
  // `sparkVersion := "..."`, which also keeps the app's own Provided compile dep
  // in lockstep), while the CVE pins are overridden by adding the artifact to the
  // SparkDistribution config (Coursier highest-wins pulls it up) or a
  // dependencyOverrides force.
  private object V {
    val spark = "4.2.0"
    val netty = "4.2.18.Final"
    val jackson = "2.21.7"
    val log4j = "2.25.5"
    val lz4Java = "1.11.4"
    val zstdJni = "1.5.7-20"
    val zookeeper = "3.9.6"
    val vertx = "4.5.30"
    val ivy = "2.6.0"
    val parquetJackson = "1.18.1" // shades jackson 2.22.2
  }

  // Vendored resource path -> mapping path within the install location.
  //
  // Left: the file under src/main/resources/vendor/spark -- these are Apache
  // Spark's own scripts, vendored verbatim (see that directory's README).
  // Right: where it lands under the install location (spark/... => /opt/spark/...),
  // i.e. the Spark distribution layout the vendored scripts expect.
  //
  // This is the minimal driver-launch closure: the entrypoint's driver branch
  // execs bin/spark-submit, which execs bin/spark-class, which sources
  // bin/load-spark-env.sh. (Executors don't use these -- they exec `java`
  // directly.)
  private val vendoredScripts = Seq(
    "vendor/spark/bin/spark-submit" -> "spark/bin/spark-submit",
    "vendor/spark/bin/spark-class" -> "spark/bin/spark-class",
    "vendor/spark/bin/load-spark-env.sh" -> "spark/bin/load-spark-env.sh",
    "vendor/spark/entrypoint.sh" -> "spark/entrypoint.sh"
  )

  // The Snowplow-owned entrypoint, generated at build time from the template
  // resource because it bakes in the app's main class and jar filename. It sits
  // IN FRONT of Spark's vendored /opt/spark/entrypoint.sh: executor/history-server
  // dispatch straight through (the k8s executor-pod launch path must be
  // untouched); everything else is treated as a driver launch. Caller args are
  // split on a "--" sentinel so spark-submit options land before the jar and
  // application args after it, matching the terraform driver-pod template's args
  // layout. The template lives next to the vendored Spark scripts it wraps; its
  // @MAIN_CLASS@/@JAR_NAME@ tokens (chosen so they can't collide with the
  // script's own bash `${...}` expansions) are substituted here.
  private val entrypointTemplateResource = "vendor/spark/snowplow-entrypoint.sh.template"

  private def entrypointScript(mainClass: String, jarName: String): String =
    readResource(entrypointTemplateResource).replace("@MAIN_CLASS@", mainClass).replace("@JAR_NAME@", jarName)

  // Open a resource on the plugin's own classpath, failing loudly if it's absent
  // (which would only happen if the plugin jar were built wrong).
  private def resourceStream(resource: String): java.io.InputStream =
    Option(getClass.getClassLoader.getResourceAsStream(resource))
      .getOrElse(sys.error(s"vendored resource not found on plugin classpath: $resource"))

  private def readResource(resource: String): String = {
    val in = resourceStream(resource)
    try scala.io.Source.fromInputStream(in, "UTF-8").mkString
    finally in.close()
  }

  // log4j2 config layering (sparkLog4jConfigFile). log4j2 reads its own
  // settings from a log4j2.component.properties on the classpath; ours points
  // log4j2's composite configuration at Spark's own defaults first, then the
  // app's overrides, so the app only states what it changes. Both files ship in
  // one small jar under /opt/spark/jars/ - the only image classpath location
  // shared by the driver (spark-class) and k8s executors (entrypoint.sh's
  // SPARK_CLASSPATH). /opt/spark/conf is hidden on executors by Spark's conf
  // configmap mount, and the app fat jar isn't on the classpath when log4j2
  // initialises.
  //
  // The defaults resource has been at this path from Spark 3.3 (its first
  // log4j2 release) to at least 4.2.0, though the jar holding it has moved.
  private val sparkLog4jDefaultsResource = "org/apache/spark/log4j2-defaults.properties"
  private val log4jComponentEntry = "log4j2.component.properties"
  private val log4jOverridesEntry = "snowplow-log4j2-overrides.properties"
  private val log4jConfigJarName = "snowplow-log4j2-config.jar"
  private val log4jComponentProperties =
    "# Generated by SnowplowSparkPlugin: layer the app's sparkLog4jConfigFile over Spark's default log4j2 config.\n" +
      s"log4j2.configurationFile = classpath:$sparkLog4jDefaultsResource,classpath:$log4jOverridesEntry\n"
  // Fixed entry timestamp (2010-01-01T00:00:00Z, the same one sbt uses for
  // reproducible jars) so an unchanged overrides file yields an identical jar
  // and docker layer.
  private val fixedJarTimestamp = 1262304000000L

  // The Spark distribution jars, from the dedicated SparkDistribution config
  // (NOT Runtime/dependencyClasspath, which carries the app's own deps).
  private def sparkDistributionJars(report: UpdateReport): Seq[File] =
    report
      .select(configurationFilter(SparkDistribution.name))
      .filter(f => f.isFile && f.getName.endsWith(".jar"))
      .distinct

  // The jars that contain the given entry. Only reads each zip's central
  // directory, not its contents.
  private def jarsContaining(jars: Seq[File], entry: String): Seq[File] =
    jars.filter { j =>
      val zip = new java.util.zip.ZipFile(j)
      try zip.getEntry(entry) != null
      finally zip.close()
    }

  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    sparkVersion := V.spark,
    sparkConfig := Map.empty,
    sparkLog4jConfigFile := None
  )

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    ivyConfigurations += SparkDistribution,
    // RUNTIME CLASSPATH PRECEDENCE (sharp edge): at runtime the Spark
    // distribution's jars (/opt/spark/jars/*) precede the app fat jar on the
    // driver/executor classpath by default. So if the app pins a library
    // version that Spark ALSO ships transitively, Spark's version wins at
    // runtime regardless of what the app declared here - the app's pin only
    // affects compilation and what gets bundled, not resolution order. Apps
    // that genuinely need their own version to win must opt into
    // spark.driver.userClassPathFirst / spark.executor.userClassPathFirst, or
    // shade the dependency in their assembly.
    //
    // These two modules seed the SparkDistribution resolution (see the config
    // definition above). This is ONLY about packaging the distribution --
    // compiling the app is the app's own responsibility: it declares whichever
    // Spark modules it builds against (spark-sql, and possibly
    // spark-hive/mllib/streaming) as `Provided`, on `sparkVersion.value` so the
    // compile version stays in lockstep with the version shipped here.
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % sparkVersion.value,
      "org.apache.spark" %% "spark-kubernetes" % sparkVersion.value
    ).map(_ % SparkDistribution),
    // Overrides to address CVEs in the versions Spark ships transitively. Scoped
    // to SparkDistribution so they touch /opt/spark/jars/ only, never the app's
    // Compile classpath; Coursier's highest-wins only ever pulls these up from
    // Spark's baseline, never below it. The list grows as CVEs surface and shrinks
    // as a sparkVersion bump makes entries redundant. Versions live in `V` above.
    libraryDependencies ++= Seq(
      "io.netty" % "netty-buffer" % V.netty,
      // netty-codec is a 4.2 aggregator that also depends on marshalling and
      // protobuf, which Spark excludes from its netty-all; mirror that here.
      ("io.netty" % "netty-codec" % V.netty)
        .exclude("io.netty", "netty-codec-marshalling")
        .exclude("io.netty", "netty-codec-protobuf"),
      "io.netty" % "netty-codec-base" % V.netty,
      "io.netty" % "netty-codec-compression" % V.netty,
      "io.netty" % "netty-codec-dns" % V.netty,
      "io.netty" % "netty-codec-http" % V.netty,
      "io.netty" % "netty-codec-http2" % V.netty,
      "io.netty" % "netty-codec-socks" % V.netty,
      "io.netty" % "netty-common" % V.netty,
      "io.netty" % "netty-handler" % V.netty,
      "io.netty" % "netty-handler-proxy" % V.netty,
      "io.netty" % "netty-resolver" % V.netty,
      "io.netty" % "netty-resolver-dns" % V.netty,
      "io.netty" % "netty-transport" % V.netty,
      "io.netty" % "netty-transport-classes-epoll" % V.netty,
      "io.netty" % "netty-transport-classes-kqueue" % V.netty,
      "io.netty" % "netty-transport-native-unix-common" % V.netty,
      "io.netty" % "netty-transport-native-epoll" % V.netty,
      "io.netty" % "netty-transport-native-kqueue" % V.netty,
      "com.fasterxml.jackson.core" % "jackson-databind" % V.jackson,
      "com.fasterxml.jackson.core" % "jackson-core" % V.jackson,
      "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % V.jackson,
      "com.fasterxml.jackson.dataformat" % "jackson-dataformat-yaml" % V.jackson,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % V.jackson,
      "org.apache.logging.log4j" % "log4j-core" % V.log4j,
      "org.apache.logging.log4j" % "log4j-api" % V.log4j,
      "org.apache.logging.log4j" % "log4j-1.2-api" % V.log4j,
      "org.apache.logging.log4j" % "log4j-slf4j2-impl" % V.log4j,
      "org.apache.logging.log4j" % "log4j-layout-template-json" % V.log4j,
      "at.yawk.lz4" % "lz4-java" % V.lz4Java,
      "com.github.luben" % "zstd-jni" % V.zstdJni,
      "org.apache.zookeeper" % "zookeeper" % V.zookeeper,
      "org.apache.zookeeper" % "zookeeper-jute" % V.zookeeper,
      "io.vertx" % "vertx-core" % V.vertx,
      "io.vertx" % "vertx-web-client" % V.vertx,
      "io.vertx" % "vertx-web-common" % V.vertx,
      "io.vertx" % "vertx-auth-common" % V.vertx,
      "io.vertx" % "vertx-uri-template" % V.vertx,
      "org.apache.ivy" % "ivy" % V.ivy,
      "org.apache.parquet" % "parquet-jackson" % V.parquetJackson
    ).map(_ % SparkDistribution),
    // The distribution provides the Scala library itself, so it must not be
    // duplicated inside the app's fat jar.
    assembly / assemblyPackageScala / assembleArtifact := false,
    stageSparkScripts := {
      val out = target.value / "spark-dist"
      vendoredScripts.map { case (resource, mapping) =>
        val dest = out / mapping
        IO.createDirectory(dest.getParentFile)
        val in = resourceStream(resource)
        try IO.transfer(in, dest)
        finally in.close()
        dest.setExecutable(true, false)
        dest -> mapping
      }
    },
    stageSparkDefaults := {
      val out = target.value / "spark-dist" / "spark-defaults.conf"
      IO.createDirectory(out.getParentFile)
      // Spark's spark-defaults.conf format is `key value` (space-separated),
      // one per line. Sorted by key for a deterministic, diff-friendly file.
      // Empty map => empty file (kept for a predictable layout; $SPARK_HOME/conf
      // is already on the classpath per the vendored entrypoint).
      val lines = sparkConfig.value.toSeq.sortBy(_._1).map { case (k, v) => s"$k $v" }
      IO.write(out, lines.mkString("\n"))
      out -> "spark/conf/spark-defaults.conf"
    },
    stageSparkEntrypoint := {
      val cls = (Compile / mainClass).value.getOrElse(
        sys.error(
          "SnowplowSparkPlugin: could not determine the application main class. " +
            "Set it explicitly, e.g. `Compile / mainClass := Some(\"com.example.Main\")`."
        )
      )
      val jarName = (assembly / assemblyJarName).value
      val out = target.value / "spark-dist" / "snowplow-entrypoint.sh"
      IO.createDirectory(out.getParentFile)
      IO.write(out, entrypointScript(cls, jarName))
      out.setExecutable(true, false)
      out -> "snowplow/entrypoint.sh"
    },
    stageSparkLog4jConfig := {
      val distJars = sparkDistributionJars(update.value)
      val out = target.value / "spark-dist" / "log4j2"
      sparkLog4jConfigFile.value match {
        // Unset => emit nothing: no jar, so Spark keeps its own fallback
        // behaviour exactly as before this feature existed.
        case None => Seq.empty
        case Some(overrides) =>
          if (!overrides.isFile)
            sys.error(s"SnowplowSparkPlugin: sparkLog4jConfigFile does not exist: $overrides")
          if (!overrides.getName.endsWith(".properties"))
            sys.error(
              s"SnowplowSparkPlugin: sparkLog4jConfigFile must be a .properties file (log4j2 properties format), got: $overrides"
            )
          // If the defaults resource is missing, log4j2 silently runs with ONLY
          // the overrides: no appender, root at its built-in default, and all
          // INFO logs vanish. So this must fail the build, not the pod.
          if (jarsContaining(distJars, sparkLog4jDefaultsResource).isEmpty)
            sys.error(
              s"SnowplowSparkPlugin: no jar in the Spark distribution contains $sparkLog4jDefaultsResource. " +
                s"Spark ${sparkVersion.value} has probably renamed or moved it, and the plugin's log4j2 pointer must be " +
                "updated before sparkLog4jConfigFile can be used. (Otherwise log4j2 would silently run with only the " +
                "overrides and drop all INFO logs.)"
            )
          // Two component files on /opt/spark/jars/* => which one log4j2 reads
          // depends on classpath order, so our pointer could silently lose.
          val clashes = jarsContaining(distJars, log4jComponentEntry)
          if (clashes.nonEmpty)
            sys.error(
              s"SnowplowSparkPlugin: the Spark distribution already ships $log4jComponentEntry in " +
                s"${clashes.map(_.getName).mkString(", ")}, so it's undefined which one log4j2 would read and " +
                "sparkLog4jConfigFile cannot be applied reliably."
            )
          val component = out / log4jComponentEntry
          IO.write(component, log4jComponentProperties)
          val jar = out / log4jConfigJarName
          // The app's file is copied byte for byte: log4j2 reads exactly what
          // the app committed, under log4j2's own properties rules.
          IO.jar(
            Seq(component -> log4jComponentEntry, overrides -> log4jOverridesEntry),
            jar,
            new java.util.jar.Manifest,
            Some(fixedJarTimestamp)
          )
          Seq(jar -> s"spark/jars/$log4jConfigJarName")
      }
    },
    Docker / defaultLinuxInstallLocation := "/opt",
    Universal / mappings := {
      val distJars = sparkDistributionJars(update.value)
      val jarMappings = distJars.map(j => j -> s"spark/jars/${j.getName}")
      // The app, packaged as a fat jar: its own classes plus its non-Spark
      // deps, with Spark (Provided) and the Scala library excluded.
      val appJar = assembly.value
      val scripts = stageSparkScripts.value
      jarMappings ++ Seq(
        appJar -> s"snowplow/${appJar.getName}",
        stageSparkDefaults.value,
        stageSparkEntrypoint.value
      ) ++ scripts ++ stageSparkLog4jConfig.value
    },
    dockerEntrypoint := Seq("/opt/snowplow/entrypoint.sh"),
    // Each case below inserts a command exactly ONCE, keyed off a command that
    // the single-stage CopyChown Dockerfile emits exactly once (FROM, WORKDIR,
    // USER).
    dockerCommands := dockerCommands.value.flatMap {
      // The vendored entrypoint.sh is Apache Spark's own k8s image entrypoint,
      // which unconditionally execs `/usr/bin/tini` as PID 1 for both the
      // "driver" and "executor" dispatch branches. Our base image
      // (eclipse-temurin, not Spark's own image) doesn't carry tini, so without
      // this the entrypoint fails immediately with "No such file or directory".
      // Installed while still root, i.e. before the USER nobody switch further
      // down the command list.
      case cmd @ com.typesafe.sbt.packager.docker.Cmd("FROM", _*) =>
        Seq(
          cmd,
          com.typesafe.sbt.packager.docker.Cmd(
            "RUN",
            "apt-get update && apt-get install -y --no-install-recommends tini && rm -rf /var/lib/apt/lists/*"
          )
        )
      case cmd @ com.typesafe.sbt.packager.docker.Cmd("WORKDIR", _*) =>
        Seq(
          com.typesafe.sbt.packager.docker.Cmd("ENV", "SPARK_HOME", "/opt/spark"),
          com.typesafe.sbt.packager.docker.Cmd("ENV", "PATH", "/opt/spark/bin:$PATH"),
          cmd
        )
      // Mirror Apache Spark's own k8s image: use /opt/spark/work-dir as a
      // nobody-writable CWD. The auto-generated `WORKDIR /opt` is root-owned
      // (COPY --chown only chowns /opt's *contents*), so as nobody any relative
      // write there would fail; giving Spark a writable CWD keeps that safe (the
      // vendored entrypoint also puts $PWD on the executor classpath per
      // SPARK-43540). This runs as root, right before the USER switch and after
      // the COPY that created /opt/spark, and (last-one-wins) overrides the
      // earlier auto-generated `WORKDIR /opt`.
      case cmd @ com.typesafe.sbt.packager.docker.Cmd("USER", _*) =>
        Seq(
          com.typesafe.sbt.packager.docker.Cmd(
            "RUN",
            "mkdir -p /opt/spark/work-dir && chown nobody:nogroup /opt/spark/work-dir"
          ),
          com.typesafe.sbt.packager.docker.Cmd("WORKDIR", "/opt/spark/work-dir"),
          cmd
        )
      case other => Seq(other)
    }
  )
}
