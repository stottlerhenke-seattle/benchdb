package com.lightbend.benchdb

import java.util.regex.{Pattern, PatternSyntaxException}

import com.typesafe.config.{Config, ConfigFactory, ConfigRenderOptions}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

final class RunResult(val db: DbRunResult, val rc: Config, val runId: Long, val name: String, overrideParams: Option[Map[String, String]]) {
  lazy val jvmArgs: Seq[String] = rc.getStringList("jvmArgs").asScala.toSeq

  def withExtracted(name: String, params: Map[String, String]) =
    new RunResult(db, rc, runId, name, Some(this.params ++ params))

  private[this] lazy val _params: Map[String, String] = rc.getConfig("params").entrySet().asScala.iterator.map { case me =>
    (me.getKey, String.valueOf(me.getValue.unwrapped))
  }.toMap
  lazy val params: Map[String, String] = overrideParams match {
    case Some(m) => m
    case None => _params
  }

  def getLongParam(key: String): Option[Long] =
    try Some(params(key).toLong) catch { case _: Exception => None }

  lazy val dbJvmArgs: Seq[DbJvmArg] = jvmArgs.zipWithIndex.map { case (s, idx) => new DbJvmArg(db.uuid, idx, s) }
  lazy val dbParams: Seq[DbRunResultParam] = params.iterator.map { case (k, v) => new DbRunResultParam(None, db.uuid, k, v) }.toSeq

  lazy val primaryMetric: RunResult.Metric =
    parseMetric(rc.getConfig("primaryMetric"))

  lazy val secondaryMetrics: Map[String, RunResult.Metric] = {
    val sc = rc.getConfig("secondaryMetrics")
    sc.entrySet().asScala.iterator.map { me =>
      (me.getKey, parseMetric(sc.getConfig(me.getKey)))
    }.toMap
  }

  private def parseStatistics(c: Config): Map[String, Double] =
    c.entrySet().asScala.iterator.map { me => (me.getKey, c.getDouble(me.getKey)) }.toMap

  private def parseMetric(c: Config): RunResult.Metric = {
    RunResult.Metric(c.getDouble("score"), c.getDouble("scoreError"), c.getDoubleList("scoreConfidence").asScala.toSeq.asInstanceOf[Seq[Double]],
      parseStatistics(c.getConfig("scorePercentiles")), c.getString("scoreUnit"))
  }
}

object RunResult extends Logging {
  def fromRaw(uuid: String, sequence: Int, testRunUuid: String, rc: Config): RunResult = {
    val db = new DbRunResult(uuid, sequence, testRunUuid,
      rc.getString("jmhVersion"), rc.getString("benchmark"), rc.getString("mode"), rc.getInt("forks"), rc.getString("jvm"),
      rc.getString("jdkVersion"), rc.getString("vmVersion"),
      rc.getInt("warmupIterations"), rc.getString("warmupTime"), rc.getInt("warmupBatchSize"),
      rc.getInt("measurementIterations"), rc.getString("measurementTime"), rc.getInt("measurementBatchSize"),
      rc.root.render(ConfigRenderOptions.concise()))
    new RunResult(db, rc, -1, db.benchmark, None)
  }

  def fromDb(db: DbRunResult, runId: Long, multi: Boolean): RunResult = {
    val n = if(multi) s"#$runId:${db.benchmark}" else db.benchmark
    new RunResult(db, ConfigFactory.parseString(db.rawData), runId, n, None)
  }

  final case class Metric(score: Double, scoreError: Double, scoreConfidence: Seq[Double], scorePercentiles: Map[String, Double], scoreUnit: String)

  def filterByName(globs: Seq[String], rs: Iterable[RunResult]): Iterable[RunResult] = {
    if(globs.isEmpty) rs else {
      val patterns = globs.map(compileGlobPattern)
      rs.filter(r => patterns.exists(p => p.matcher(r.name).matches()))
    }
  }

  private def compileGlobPattern(expr: String): Pattern = {
    val a = expr.split("\\*", -1)
    val b = new StringBuilder
    var i = 0
    while(i < a.length) {
      if(i != 0) b.append(".*")
      if(!a(i).isEmpty)
        b.append(Pattern.quote(a(i).replaceAll("\n", "\\n")))
      i += 1
    }
    Pattern.compile(b.toString)
  }

  private def compileExtractorPattern(expr: String): (Pattern, ArrayBuffer[String]) = {
    val b = new StringBuilder
    var i = 0
    var inGroup = false
    val groups = new ArrayBuffer[String]
    while(i < expr.length) {
      val c = expr.charAt(i)
      i += 1
      c match {
        case '(' if inGroup => throw new PatternSyntaxException("Capture groups must not be nested", expr, i-1)
        case ')' if !inGroup => throw new PatternSyntaxException("Unexpected end of capture group", expr, i-1)
        case '(' =>
          b.append('(')
          inGroup = true
          var nextEq = expr.indexOf('=', i)
          val nextClose = expr.indexOf(')', i)
          if(nextClose == -1) throw new PatternSyntaxException("Capture group not closed", expr, i)
          if(nextEq == -1 || nextEq > nextClose) groups += ""
          else {
            val n = expr.substring(i, nextEq)
            groups += n
            i = nextEq+1
          }
        case ')' =>
          b.append(')')
          inGroup = false
        case '*' => b.append(".*")
        case c => b.append(Pattern.quote(String.valueOf(c)))
      }
    }
    logger.debug(s"Compiled extractor '$expr' to $b, $groups")
    (Pattern.compile(b.toString), groups)
  }

  def extract(extractors: Seq[String], rs: Iterable[RunResult]): Iterable[RunResult] = {
    if(extractors.isEmpty) rs else {
      val patterns = extractors.map(compileExtractorPattern).filter(_._2.nonEmpty)
      rs.map { r =>
        val name = r.name
        val matcherOpt = patterns.iterator.map(p => (p._1.matcher(name), p._2)).find(_._1.matches)
        matcherOpt match {
          case Some((m, groups)) =>
            logger.debug(s"Matched ${name}")
            val groupData = groups.zipWithIndex.map { case (n, i) => (n, m.group(i+1), m.start(i+1), m.end(i+1)) }
            logger.debug(s"Group data: "+groupData)
            val params = groupData.iterator.filter(_._1.nonEmpty).map { case (k, v, _, _) => (k, v) }.toMap
            logger.debug(s"Extracted parameters: "+params)
            val b = new StringBuilder
            var copied = 0
            groupData.foreach { case (n, _, start, end) =>
              if(copied < start) b.append(name.substring(copied, start))
              if(n.nonEmpty) b.append('(').append(n).append(')')
              copied = end
            }
            if(copied < name.length) b.append(name.substring(copied))
            logger.debug("New name: "+b)
            r.withExtracted(b.toString, params)
          case None => r
        }
      }
    }
  }
}
