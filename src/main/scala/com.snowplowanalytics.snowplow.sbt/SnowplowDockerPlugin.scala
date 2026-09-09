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
import com.typesafe.sbt.packager.Keys.maintainer
import com.typesafe.sbt.packager.docker.{Cmd, CmdLike, DockerChmodType, DockerPermissionStrategy, DockerPlugin, ExecCmd}
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import DockerPlugin.autoImport._

object SnowplowDockerPlugin extends AutoPlugin {

  override def requires: Plugins = DockerPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplow.io>",
    dockerBaseImage := "eclipse-temurin:21-jre-noble",
    Docker / daemonUser := "nobody",
    Docker / daemonGroup := "nogroup",
    Docker / daemonUserUid := None,
    dockerRepository := Some("snowplow"),
    Docker / defaultLinuxInstallLocation := "/home/snowplow",
    dockerUpdateLatest := true,
    dockerPermissionStrategy := DockerPermissionStrategy.CopyChown,
    dockerCommands := insertAdditionalPermissions(dockerCommands.value, dockerAdditionalPermissions.value)
  )

  /**
   * Restore the executable bit on the launcher script (and on any other file sbt-native-packager
   * considers executable) from inside the docker build.
   *
   * `DockerPermissionStrategy.CopyChown` emits a bare `COPY --chown` and no `chmod` at all, so the
   * file modes in the image are whatever they happened to be in the build context. Our apps stage
   * the docker context in one CI job and run `docker build` in another, shipping the context
   * between them as a GitHub Actions artifact - and artifact upload/download does not preserve
   * permissions, so every file arrives mode 644. The image then has a non-executable entrypoint and
   * the container dies with exit code 126 ("permission denied") before the JVM starts.
   *
   * `dockerAdditionalPermissions` already lists the files that need the executable bit (everything
   * under `bin` plus any `.sh` file), but sbt-native-packager only honours it under the
   * `MultiStage` and `Run` strategies. We cannot simply switch to one of those: both also `chmod -R
   * u=rX,g=rX` the whole install location, which breaks apps that need to write there (e.g. the
   * Spark distribution's `/opt/spark/work`), and `MultiStage` emits a second FROM/WORKDIR/USER that
   * [[SnowplowSparkPlugin]]'s `dockerCommands` transform keys off. So apply just the `chmod` part
   * here.
   *
   * The commands are inserted before the first `USER` instruction, i.e. while the build is still
   * root.
   */
  private def insertAdditionalPermissions(
    commands: Seq[CmdLike],
    additionalPermissions: Seq[(DockerChmodType, String)]
  ): Seq[CmdLike] =
    if (additionalPermissions.isEmpty) commands
    else {
      val chmods = additionalPermissions
        .groupBy(_._1)
        .toSeq
        .sortBy(_._1.argument)
        .map { case (chmodType, entries) =>
          ExecCmd("RUN", Seq("chmod", chmodType.argument) ++ entries.map(_._2).sorted: _*)
        }
      // `USER` is where the build stops being root, so the chmods have to go in ahead of it.
      val split = commands.span {
        case Cmd("USER", _*) => false
        case _ => true
      }
      split._1 ++ chmods ++ split._2
    }
}
