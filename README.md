# SBT Snowplow Release

SBT plugins to help build and release Snowplow pipeline applications. A place to store shared common configuration settings.

## Installation

Add this to your `project/plugins.sbt` file:

```
addSbtPlugin("com.snowplowanalytics" % "sbt-snowplow-release" % "x.y.z")
```

## Plugins

### Snowplow Docker Plugin

Configure a sbt project to publish a docker image, using Snowplow's standard settings, and using `eclipse-temurin:21-jre-noble` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDockerPlugin)
```

### Snowplow Distroless Docker Plugin

Configure a sbt project to publish the "distroless" flavour of a Snowplow docker image. It uses Snowplow's standard settings, and using `gcr.io/distroless/java21-debian12` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDistrolessDockerPlugin)
```

### IgluSchemaPlugin

This plugin adds Iglu schema files to your project's managed resources.  This is helpful if you use [iglu-scala-client](https://github.com/snowplow/iglu-scala-client) and you want the schemas to be fetched at compile time instead of run time.

| Setting              | Default                  | Description |
|----------------------|--------------------------|-------------|
| `igluUris`           | (empty)                  | The list of Iglu URIs required by the project |
| `igluRepository`     | `http://iglucentral.com` | The Iglu repository URL from which to fetch schemas |
| `igluEmbeddedPrefix` | `iglu-client-embedded`   | The default is compatible with Iglu Scala Client's default location for an embedded repository |

This example will fetch a schema from Iglu Central and add it to the test resources directory, under the path `iglu-client-embedded/schemas/org.ietf/http_header/jsonschema/1-0-0`.

```scala
Test / igluUris := Seq("iglu:org.ietf/http_header/jsonschema/1-0-0")
```
