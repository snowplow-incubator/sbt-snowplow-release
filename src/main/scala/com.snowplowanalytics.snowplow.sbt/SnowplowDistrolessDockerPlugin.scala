/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
import sbt.Keys.artifactPath
import com.typesafe.sbt.packager.docker.{Cmd, DockerPermissionStrategy, DockerPlugin, ExecCmd}
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin.autoImport.packageJavaLauncherJar
import com.typesafe.sbt.packager.Keys.{maintainer, stage, stagingDirectory}
import DockerPlugin.autoImport._

import scala.io.Source

object SnowplowDistrolessDockerPlugin extends AutoPlugin {

  object autoImport {
    val stageDistrolessInstallScript = taskKey[File]("Stage the install script for building distroless docker image")
  }

  import autoImport._

  override def requires: Plugins = DockerPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    dockerPermissionStrategy := DockerPermissionStrategy.CopyChown,
    dockerBaseImage := "gcr.io/distroless/base:nonroot",
    dockerRepository := Some("snowplow"),
    dockerEntrypoint := Seq(
      "/usr/lib/jvm/java-11-openjdk/bin/java",
      "-jar",
      s"/opt/snowplow/lib/${(packageJavaLauncherJar / artifactPath).value.getName}"
    ),
    dockerCommands := {
      Seq(
        Cmd("FROM", "debian:bullseye-slim", "AS", "bullseye"), // Provides standard linux executables for the install script
        Cmd("COPY", "install.sh", "/usr/bin/"),
        Cmd(
          "FROM",
          "gcr.io/distroless/java11-debian11:nonroot",
          "AS",
          "java"
        ), // Provides a java installation compatible with the base image
        Cmd("FROM", dockerBaseImage.value), // The true base image
        Cmd("USER", "0"),
        // Mount the filesystems of the build images and run the install script:
        ExecCmd(
          "RUN --mount=type=bind,from=bullseye,source=/,target=/bullseye --mount=type=bind,from=java,source=/,target=/java",
          "/bullseye/bin/sh",
          "/bullseye/usr/bin/install.sh"
        )
      ) ++ dockerCommands.value.tail
    }
  ) ++ inConfig(Docker)(
    Seq(
      daemonUserUid := None,
      daemonGroup := "nonroot",
      daemonUser := "nonroot",
      maintainer := "Snowplow Analytics Ltd. <support@snowplow.io>",
      defaultLinuxInstallLocation := "/opt/snowplow",
      stage := (stage dependsOn stageDistrolessInstallScript).value,
      stageDistrolessInstallScript := {
        val f = stagingDirectory.value / "install.sh"

        val stream = getClass.getClassLoader.getResourceAsStream("distroless/install.sh")
        val content = Source.fromInputStream(stream).getLines // Warning: Source.fromResource does not work for a sbt plugin
        IO.writeLines(f, content.toSeq)
        f
      }
    )
  )
}
