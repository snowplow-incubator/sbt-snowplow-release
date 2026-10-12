sys.props.get("plugin.version") match {
  case Some(x) => addSbtPlugin("com.snowplowanalytics" % "sbt-snowplow-release" % x)
  case None    => sys.error("The system property 'plugin.version' is not defined.")
}
