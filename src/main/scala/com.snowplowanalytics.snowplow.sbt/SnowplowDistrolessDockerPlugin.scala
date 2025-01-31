/*
 * Copyright (c) 2022-2023 Snowplow Analytics Ltd. All rights reserved.
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
import com.typesafe.sbt.packager.docker.{DockerPermissionStrategy, DockerPlugin}
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin
import com.typesafe.sbt.packager.archetypes.jar.LauncherJarPlugin.autoImport.packageJavaLauncherJar
import com.typesafe.sbt.packager.Keys.maintainer
import DockerPlugin.autoImport._

object SnowplowDistrolessDockerPlugin extends AutoPlugin {

  object autoImport {
    val stageDistrolessInstallScript = taskKey[File]("Stage the install script for building distroless docker image")
  }

  override def requires: Plugins = DockerPlugin && LauncherJarPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplow.io>",
    dockerBaseImage := "gcr.io/distroless/java21-debian12:nonroot",
    Docker / daemonUser := "nonroot",
    Docker / daemonGroup := "nonroot",
    Docker / daemonUserUid := None,
    dockerRepository := Some("snowplow"),
    dockerUpdateLatest := false,
    Docker / defaultLinuxInstallLocation := "/home/snowplow",
    dockerEntrypoint := Seq("java", "-jar", s"/home/snowplow/lib/${(packageJavaLauncherJar / artifactPath).value.getName}"),
    dockerAlias := dockerAlias.value.copy(tag = dockerAlias.value.tag.map(t => s"$t-distroless")),
    dockerPermissionStrategy := DockerPermissionStrategy.CopyChown
  )
}
