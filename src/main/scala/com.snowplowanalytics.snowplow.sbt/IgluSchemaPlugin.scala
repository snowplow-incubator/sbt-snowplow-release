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
import sbt.Keys._
import sbt.io.IO
import scala.sys.process._
import scala.util.matching.Regex
import scala.language.postfixOps

object IgluSchemaPlugin extends AutoPlugin {

  object autoImport {
    val igluUris = settingKey[Seq[String]]("Iglu schemas required by the current project")
    val igluRepository = settingKey[String]("Iglu repository URL from which to fetch schemas")
    val igluSchemas = taskKey[Seq[File]]("Iglu schema files required by the current projet")
    val igluEmbeddedPrefix = settingKey[String]("Iglu embedded repository prefix")
  }

  private val igluRegex: Regex = ("^iglu:" + // Protocol
    "([a-zA-Z0-9-_.]+)/" + // Vendor
    "([a-zA-Z0-9-_]+)/" + // Name
    "([a-zA-Z0-9-_]+)/" + // Format
    "([0-9-]+)$").r // SchemaVer (lax)

  private def fetchSchemas(
    igluUris: Seq[String],
    igluRepository: String,
    outputBase: File
  ): Seq[File] =
    igluUris.map {
      case igluRegex(vendor, name, format, version) =>
        val dir: File = outputBase / "schemas" / vendor / name / format
        val to: File = dir / version
        if (!to.exists) {
          IO.createDirectory(dir)
          url(s"$igluRepository/schemas/$vendor/$name/$format/$version") #> to !
        }
        to
      case other =>
        throw new IllegalArgumentException(s"Invalid Iglu URI $other")
    }

  override def trigger: PluginTrigger = allRequirements

  import autoImport._

  override lazy val globalSettings: Seq[Setting[_]] = Seq(
    igluRepository := "http://iglucentral.com",
    igluUris := Seq(),
    igluEmbeddedPrefix := "iglu-client-embedded"
  )

  lazy val baseProjectSettings: Seq[Setting[_]] = Seq(
    igluSchemas := fetchSchemas(igluUris.value, igluRepository.value, resourceManaged.value / igluEmbeddedPrefix.value),
    resourceGenerators += igluSchemas
  )

  override lazy val projectSettings: Seq[Setting[_]] =
    inConfig(Compile)(baseProjectSettings) ++ inConfig(Test)(baseProjectSettings)

}
