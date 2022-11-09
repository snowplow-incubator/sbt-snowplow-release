# SBT Snowplow

SBT plugins to help build Snowplow pipeline applications. A place to store shared common configuration settings.

## Usage

Add this to your `project/plugins.sbt` file:

```
addSbtPlugin("com.snowplowanalytics" % "sbt-snowplow" % "x.y.z")
```

Configure a sbt project to publish a docker image, using Snowplow's standard settings, and using `elipse-temurin:11-jre-focal` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDockerPlugin)
```

Or to publish a docker image based off `gcr.io/distroless/base:nonroot`.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDistrolessDockerPlugin)
```
