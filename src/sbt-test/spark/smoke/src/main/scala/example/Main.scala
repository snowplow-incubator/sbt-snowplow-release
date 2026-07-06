package example

import cats.syntax.foldable._
import org.apache.spark.sql.SparkSession

object Main {
  def main(args: Array[String]): Unit = {
    // Proves the wrapper entrypoint's "--" sentinel split actually forwards
    // post-separator tokens to the application at runtime (not just that
    // spark-submit accepted the pre-separator options and the job ran).
    require(
      args.contains("--smoke-marker"),
      s"self-deploying entrypoint did not forward the application arg past '--'; got: ${args.mkString(" ")}"
    )

    // Checks the fat jar actually carries the app's own, non-Spark dependency.
    // combineAll uses cats' Monoid[Int], a class that exists only in cats-core
    // (Spark ships no cats-* jar), so if cats didn't make it into the assembly
    // this throws NoClassDefFoundError before Spark even starts a job.
    val total = List(1, 2, 3).combineAll
    require(total == 6, s"cats-core not on the app classpath: got $total")

    val spark = SparkSession.builder().appName("snowplow-spark-smoke").getOrCreate()
    import spark.implicits._
    val groups =
      spark.range(1000000L).repartition(8).groupBy($"id" % 100).count().count()
    require(groups == 100L, s"expected 100 groups, got $groups")

    // Exercises parquet write + read end to end, so the parquet-jackson bump in
    // the distribution is genuinely validated (the shuffle above never touches
    // parquet). /tmp is world-writable in the container and the job runs as UID
    // 65534, so this write succeeds as nobody.
    val dir = "file:///tmp/smoke-parquet"
    spark.range(1000L).toDF("n").write.mode("overwrite").parquet(dir)
    val readBack = spark.read.parquet(dir).count()
    require(readBack == 1000L, s"parquet round-trip failed: got $readBack")

    spark.stop()
  }
}
