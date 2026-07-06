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

Configure a sbt project to publish the "distroless" flavour of a Snowplow docker image. It uses Snowplow's standard settings, and using `gcr.io/distroless/java21-debian13` as the base image.

```scala
lazy val subproject = project
  .enablePlugins(SnowplowDistrolessDockerPlugin)
```

### Snowplow Spark Plugin

Configure an sbt project to build a self-contained, non-root Docker image containing a slimmed Apache Spark distribution, for running Spark on Kubernetes via Spark's native scheduler. The plugin owns the Spark version and pins a curated set of security-patched dependency versions (netty, jackson, log4j, and more) into the distribution, so CVE fixes are managed in one place; bump the plugin version to pick them up. There is no distroless variant, as Spark's entrypoint needs a shell.

Compilation stays your project's responsibility: declare the Spark modules you build against as `provided` (so they compile but aren't bundled), using the plugin's `sparkVersion` setting so they match the shipped distribution, alongside your own non-Spark dependencies. The plugin then resolves the Spark distribution separately into `/opt/spark` and packages your application as a fat jar at `/opt/snowplow/app.jar`. Note that the distribution's jars take classpath precedence, so if you pin a library that Spark also ships, Spark's version wins at runtime (use `spark.driver.userClassPathFirst`/`spark.executor.userClassPathFirst` or shading if you need your version instead).

```scala
lazy val sparkApp = project
  .enablePlugins(SnowplowSparkPlugin)
  .settings(
    libraryDependencies += "org.apache.spark" %% "spark-sql" % sparkVersion.value % Provided,
    // ... your own (non-Spark) dependencies
  )
```

| Setting        | Default | Description |
|----------------|---------|-------------|
| `sparkVersion` | `4.1.2` | The Apache Spark version to bundle |
| `sparkConfig`  | `Map.empty` | Intrinsic Spark conf baked into the image's `spark-defaults.conf` (see below) |

#### Running the image

The image is self-deploying: run it and it launches your application as the
Spark driver. You do not run `spark-submit`, pass `driver`, name the main class,
or know the jar path — the plugin's entrypoint does all of that. Pass
spark-submit options directly, and separate any application arguments with a
`--` sentinel:

    docker run <image> <spark-submit options> -- <application args>

For example, a Spark-on-Kubernetes driver pod passes its k8s master URL and
per-deployment `--conf` options as the spark-submit options, then `--` and the
application's own arguments. The first `--` is consumed as this separator, so
a literal `--` cannot itself be passed through as an application argument.

#### Intrinsic Spark configuration

`sparkConfig` is for Spark configuration that is *intrinsic to your
application* — conf it cannot run without, or that references your own code
(e.g. a custom S3A credentials-provider class that lives in your jar). It is
rendered into `/opt/spark/conf/spark-defaults.conf`, Spark's lowest-precedence
conf source, so anything passed as `--conf` at deploy time overrides it.

    sparkConfig := Map(
      "spark.hadoop.fs.s3a.aws.credentials.provider" -> "com.example.MyCredentialsProvider"
    )

#### Overriding versions from your application

Everything the plugin pins is a *default* — you can override any of it from your own build, so you never have to wait for a new plugin release to react to a CVE.

Change the Spark version (this drives the whole distribution; keep your `provided` Spark deps on `sparkVersion.value` so they stay in lockstep):

```scala
sparkVersion := "4.1.3"
```

Bump a library the distribution ships — e.g. to take a netty or jackson fix early — by adding it to the `SparkDistribution` configuration. This affects the shipped distribution *only* (not your app's compile classpath), and Coursier's "highest version wins" pulls the version up:

```scala
// bump the specific module carrying the fix; its POM cascades matching siblings
libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.21.5" % SparkDistribution

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
