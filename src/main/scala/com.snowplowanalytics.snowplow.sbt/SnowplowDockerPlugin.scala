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
import com.typesafe.sbt.packager.docker.DockerPlugin
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport._
import com.typesafe.sbt.packager.Keys.maintainer
import DockerPlugin.autoImport._

object SnowplowDockerPlugin extends AutoPlugin {

  override def requires: Plugins = DockerPlugin

  override def projectSettings: Seq[Setting[_]] = Seq(
    Docker / maintainer := "Snowplow Analytics Ltd. <support@snowplow.io>",
    dockerBaseImage := "eclipse-temurin:21-jre-noble",
    Docker / daemonUser := "snowplow",
    dockerRepository := Some("snowplow"),
    Docker / defaultLinuxInstallLocation := "/home/snowplow",
    dockerUpdateLatest := true
  )
}
