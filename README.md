# SBT Snowplow

SBT plugins to help build Snowplow pipeline applications. A place to store shared common configuration settings.

## Installation

Add this to your `project/plugins.sbt` file:

```
addSbtPlugin("com.snowplowanalytics" % "sbt-snowplow-release" % "x.y.z")
```

## Plugins

### Snowplow Docker Plugin

Configure a sbt project to publish a docker image, using Snowplow's standard settings, and using `elipse-temurin:11-jre-focal` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDockerPlugin)
```

### Snowplow Distroless Docker Plugin

Configure a sbt project to publish the "distroless" flavour of a Snowplow docker image. It uses Snowplow's standard settings, and using `gcr.io/distroless/base:nonroot` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDistrolessDockerPlugin)
```
