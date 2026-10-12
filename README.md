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
| `sparkLog4jConfigFile` | `None` | A log4j2 properties file layered on top of Spark's default log4j2 config, in both driver and executors (see below) |

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

#### Tuning log4j2 logging

Spark, Hadoop and your app all log through log4j2 in the image. To change log
levels or filter out noisy lines, commit a log4j2 **properties-format** file to
your repo and point `sparkLog4jConfigFile` at it:

```scala
sparkLog4jConfigFile := Some(baseDirectory.value / "src" / "spark" / "log4j2-overrides.properties")
```

The file holds only what you change. It's layered on top of Spark's own
default log4j2 config (root level, appender, pattern) using log4j2's composite
configuration, and applies to the driver and to every executor. For example:

```properties
# Drop a chatty logger to WARN
logger.codec.name = org.apache.hadoop.io.compress.CodecPool
logger.codec.level = warn

# Keep a logger at INFO but drop one kind of line
logger.commitops.name = org.apache.hadoop.fs.s3a.commit.impl.CommitOperations
logger.commitops.filter.starting.type = RegexFilter
logger.commitops.filter.starting.regex = Starting: Committing file .*
logger.commitops.filter.starting.onMatch = DENY
logger.commitops.filter.starting.onMismatch = NEUTRAL
```

Things to know:

- The file is copied into the image byte for byte and read with log4j2's
  normal rules for the properties format. In particular `\` is an escape
  character, so a regex `\d` is written `\\d`, and log4j2 performs `${…}`
  lookups, so a literal `${` is written `$${`.
- Only `.properties` files are accepted, and a missing file fails the build.
- Logger names must match exactly. A wrong name fails silently: the rule just
  doesn't apply.
- **Don't** put the file under `src/main/resources`. Files there end up in
  your app's fat jar, which isn't on the classpath when log4j2 initialises,
  so it would have no effect.
- When unset (the default), the plugin adds nothing and Spark uses its own
  defaults, exactly as before.
- With this set, `spark.log.structuredLogging.enabled=true` has no effect.
  Spark only switches to its JSON log profile when log4j2 is otherwise
  unconfigured.
- At deploy time, `-Dlog4j2.configurationFile=…` in
  `spark.driver.extraJavaOptions`/`spark.executor.extraJavaOptions` still
  takes precedence over this, if you ever need to replace the config entirely.
- With this set, log4j2 no longer auto-discovers a `log4j2.properties` (or
  other log4j2 config file) on the classpath, so one placed in Spark's conf
  dir at deploy time is ignored. Use `-Dlog4j2.configurationFile` instead.

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
