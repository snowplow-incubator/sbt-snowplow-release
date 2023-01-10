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

// SBT
import sbt._
import Keys._

// dynver plugin
import sbtdynver.DynVerPlugin.autoImport._

object BuildSettings {

  lazy val buildSettings = Seq[Setting[_]](
    name := "sbt-snowplow-release",
    organization := "com.snowplowanalytics",
    licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html")),
  )

  // Bintray publishing settings
  lazy val publishSettings = Seq[Setting[_]](
    publishArtifact := true,
    Test / publishArtifact := false,
    pomIncludeRepository := { _ => false },
    homepage := Some(url("http://snowplow.io")),
    ThisBuild / dynverVTagPrefix := false, // Otherwise git tags required to have v-prefix
    developers := List(
      Developer(
        "Snowplow Analytics Ltd",
        "Snowplow Analytics Ltd",
        "support@snowplow.io",
        url("https://snowplow.io")
      )
    )
  )

  lazy val rootSettings = buildSettings ++ publishSettings

}
