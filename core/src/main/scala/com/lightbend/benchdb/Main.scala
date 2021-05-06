package com.lightbend.benchdb

import cats.implicits._
import com.monovore.decline._
import java.nio.file.{FileSystems, Files, Path}
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.regex.PatternSyntaxException

import better.files._

import scala.collection.JavaConverters._
import scala.util.Try
import com.typesafe.config.{ConfigFactory, ConfigParseOptions, ConfigRenderOptions, ConfigValueFactory}

object Main extends Logging {

  def main(args: Array[String]) = System.exit(runToStatus(args))

  def runToStatus(args: Array[String]): Int = try {
    val config = Opts.options[Path]("config", help = "Configuration file.").map(_.toList).orElse(Opts(Nil))
    val noUserConfig = Opts.flag("no-user-config", help = "Don't read ~/.benchdb.conf").orFalse
    val props = Opts.options[String]("set", short = "D", help = "Overwrite a configuration option (key=value)").map(_.toList).orElse(Opts(Nil))

    val globalOptions = (config, noUserConfig, props).mapN(GlobalOptions).map(_.validate())

    val force = Opts.flag("force", "Required to actually perform the operation.").orFalse

    val showMetaCommand = Command[GlobalOptions => Unit](name = "show-meta", header =
      """Show the meta data for a project without storing it.
        |Use the current directory if no project-dir is specified.""".stripMargin, helpFlag = false) {
      val projectDir = Opts.argument[Path](metavar = "project-dir").withDefault(FileSystems.getDefault.getPath("").toAbsolutePath)
      projectDir.map { path => showMeta(_, path) }
    }

    val createUserConfigCommand = Command[GlobalOptions => Unit](name = "create-config", header =
      "Create a default user configuration file using an embedded H2 database.") {
      Opts(_.createUserConfig())
    }

    val initDbCommand = Command[GlobalOptions => Unit](name = "init-db", header =
      "Create the database schema.") {
      force.map { f => initDb(_, f) }
    }

    val deleteDbCommand = Command[GlobalOptions => Unit](name = "delete-db", header =
      "Drop the database schema.") {
      force.map { f => deleteDb(_, f) }
    }

    val insertRunCommand = Command[GlobalOptions => Unit](name = "insert-run", header =
      "Insert a JMH result file into the database.") {
      val projectDir = Opts.option[Path]("project-dir",
        help = "Project directory from which git data is read (defaults to the current directory)"
      ).withDefault(FileSystems.getDefault.getPath(""))
      val message = Opts.option[String]("msg", help = "Comment message to store with the database entry.").orNone
      val resultFile = Opts.argument[Path](metavar = "result-json-file")
      val jmhArgs = Opts.option[String]("jmh-args", "JMH command line arguments to store with the database entry.").orNone
      (projectDir, message, resultFile, jmhArgs).mapN { case (projectDir, message, resultFile, jmhArgs) => insertRun(_, projectDir, message, resultFile, jmhArgs) }
    }

    val runs = Opts.options[String]("run", short = "r", help = "IDs of the test runs to include (or 'last').").map(_.toList).orElse(Opts(Nil))
    val benchs = Opts.options[String]("benchmark", short = "b", help = "Glob patterns of benchmark names to include.").map(_.toList).orElse(Opts(Nil))
    val extract = Opts.options[String]("extract", help = "Extractor pattern to generate parameters from names.").map(_.toList).orElse(Opts(Nil))
    val regex = Opts.flag("regex", short = "re", help = "Interpret extract pattern as a Java regular expression.").orFalse
    val scorePrecision = Opts.option[Int]("score-precision", help = "Precision of score and error in tables (default: 3)").withDefault(3)
    val queryResultsCommand = Command[GlobalOptions => Unit](name = "results", header =
      "Query the database for test results and print them.") {
      val pivot = Opts.options[String]("pivot", help = "Parameter names to pivot in table output.").map(_.toList).orElse(Opts(Nil))
      val raw = Opts.flag("raw", "Print raw JSON data instead of a table.").orFalse
      val metric = Opts.option[String]("metric", "Secondary metric to report rather than the primary, e.g. ·gc.alloc.rate.norm").orNone
      (runs, benchs, extract, regex, scorePrecision, pivot, raw, metric).mapN { case (runs, benchs, extract, regex, sp, pivot, raw, metric) =>
      { go =>
        if(raw && pivot.nonEmpty) logger.error("Cannot pivot in raw output mode.")
        else queryResults(go, runs, benchs, extract, regex, sp, pivot, raw, metric)
      }
      }
    }
    val chartCommand = Command[GlobalOptions => Unit](name = "chart", header =
      "Create a line chart of test results.") {
      val template = Opts.option[Path]("template", "HTML template containing file.").orNone
      val out = Opts.option[Path]("out", short = "o", help = "Output file to generate, or '-' for stdout.").orNone
      val pivot = Opts.options[String]("pivot", help = "Parameter names to combine in a chart.").map(_.toList).orElse(Opts(Nil))
      val metric = Opts.option[String]("metric", "Secondary metric to report rather than the primary, e.g. ·gc.alloc.rate.norm").orNone
      (runs, benchs, extract, regex, scorePrecision, pivot, metric, template, out).mapN { case (runs, benchs, extract, regex, sp, pivot, metric, template, out) =>
        createChart(_, runs, benchs, extract, regex, sp, pivot, metric, template, out, args)
      }
    }

    val listRunsCommand = Command[GlobalOptions => Unit](name = "list", header =
      "Query the database for test runs and print them.") {
      val limit = Opts.option[Int]("limit", help = "Maximum number of runs to list.").orNone
      val gitData = Opts.flag("git-data", help = "Include git data.").orFalse
      val platformData = Opts.flag("platform-data", help = "Include platform data.").orFalse
      (limit, gitData, platformData).mapN { case (limit, gd, pd) => listRuns(_, limit, gd, pd) }
    }

    val benchdbCommand = Command("benchdb",
      s"""benchdb ${BuildInfo.version}
         |A database and query tool for jmh.""".stripMargin) {
      val sub = Opts.subcommands(showMetaCommand, createUserConfigCommand, initDbCommand, deleteDbCommand, insertRunCommand, queryResultsCommand, chartCommand, listRunsCommand)
      (globalOptions, sub).mapN { (go, cmd) => cmd(go) }
    }

    benchdbCommand.parse(args, sys.env) match {
      case Left(help) =>
        System.err.print(help)
        if(help.errors.nonEmpty) 1 else 0
      case Right(_) =>
        if(ErrorRecognitionAppender.rearm()) 1 else 0
    }
  } catch { case _: Abort => 1 }

  def showMeta(go: GlobalOptions, projectDir: Path): Unit = {
    val gd = new GitData(projectDir)
    val pd = new PlatformData
    val m = new java.util.HashMap[String, AnyRef]
    m.put("git", gd.javaMapData)
    m.put("platform", pd.javaMapData)
    println(ConfigFactory.parseMap(m).root.render(ConfigRenderOptions.defaults().setOriginComments(false)))
  }

  def initDb(go: GlobalOptions, force: Boolean): Unit = {
    new Global(go).use { g =>
      if(force) {
        g.dao.run(g.dao.createDb)
        println("Database initialized.")
      } else {
        println(g.dao.run(g.dao.getDbInfo))
        println("Use --force to actually initialize the database.")
      }
    }
  }

  def deleteDb(go: GlobalOptions, force: Boolean): Unit = {
    new Global(go).use { g =>
      if(force) {
        g.dao.run(g.dao.dropDb)
        println("Database deleted.")
      } else {
        println(g.dao.run(g.dao.getDbInfo))
        println("Use --force to actually delete the database.")
      }
    }
  }

  def insertRun(go: GlobalOptions, projectDir: Path, message: Option[String], resultFile: Path, jmhArgs: Option[String]): Unit = {
    new Global(go).use { g =>
      val gd = new GitData(projectDir)
      val pd = new PlatformData
      val run = new DbTestRun(UUID.randomUUID().toString, -1, Timestamp.from(Instant.now()), message, jmhArgs,
        gd.getHeadDate.map(d => Timestamp.from(Instant.ofEpochMilli(d.getTime))), gd.getHeadSHA, gd.getOriginURL, gd.getUpstreamURL,
        Option(pd.hostname), Option(pd.javaVendor), Option(pd.javaVersion), Option(pd.javaVmName), Option(pd.javaVmVersion),
        Option(pd.userName),
        gd.toJsonString, pd.toJsonString)
      if(!Files.isRegularFile(resultFile))
        logger.error(s"Result file s$resultFile not found.")
      else {
        val resultJson = "data = " + File(resultFile).contentAsString(charset = "UTF-8")
        val resultConfigs = ConfigFactory.parseString(resultJson).getConfigList("data").asScala
        val daoData = resultConfigs.iterator.zipWithIndex.map { case (rc, idx) =>
          val rr = RunResult.fromRaw(UUID.randomUUID().toString, idx, run.uuid, rc)
          (rr.db, rr.dbJvmArgs, rr.dbParams)
        }.toSeq
        val runResults = daoData.map(_._1)
        val jvmArgs = daoData.flatMap(_._2)
        val runResultParams = daoData.flatMap(_._3)
        val runId = g.dao.run(g.dao.checkVersion andThen g.dao.insertRun(run, runResults, jvmArgs, runResultParams))
        println(s"Test run #${runId} with ${runResults.size} results inserted.")
      }
    }
  }

  def regressionTest(
    go: GlobalOptions, testRuns: Seq[String], referenceRuns: Seq[String], benchs: Seq[String], 
    extract: Seq[String], regex: Boolean, metric: Option[String], tolerance: Double): Try[Unit] =
    Try {
      new Global(go).use { g =>
        if ((testRuns.toSet intersect referenceRuns.toSet).nonEmpty) {
          throw new IllegalArgumentException("The test runs and reference runs must be mutually exclusive.")
        }
        val runs = testRuns ++ referenceRuns
        val multi = runs.size > 1

        val allRs = g.dao.run(g.dao.checkVersion andThen g.dao.queryResults(runs))
          .map { case (rr, runId) => RunResult.fromDb(rr, runId, multi = multi)}
        val rs = RunResult.extract(extract, regex, RunResult.filterByName(benchs, allRs)).toSeq
        val allParamNames = rs.flatMap(_.params.keys).distinct.toVector
        if (rs.isEmpty) {
          throw new IllegalArgumentException("No results found for specified test or reference runs.")
        }

        def paramsStr(params: Map[String, String]): String = 
          params map { case (k, v) => s"$k=$v"} mkString ", "

        def computeReferenceValue(params: Map[String, String]): Double = {
          val scores = 
            rs filter { 
              r => (referenceRuns contains r.runId.toString) && (r.params == params)
            } map (_.primaryMetricOr(metric).score)
          if (scores.isEmpty) {
            throw new RuntimeException(s"No reference value found for params: ${paramsStr(params)}")
          }
          val (count, sumScores) = scores.toVector.foldLeft((0, 0.0)) {
            case ((count, sumScores), score) => (count + 1, sumScores + score)
          }
          val meanScore = sumScores / count
          val sdScore = math.sqrt(scores.foldLeft(0.0) { (sum, score) => 
            (score - meanScore) * (score - meanScore) 
          } / (count - 1))
          meanScore
        }

        val lastId = rs.map(_.runId).max
        if (((testRuns contains "last") && (referenceRuns contains lastId.toString)) ||
            ((referenceRuns contains "last") && (testRuns contains lastId.toString))) {
          throw new IllegalArgumentException("Test test runs and reference runs must be mutually exclusive.")
        }
        val regressionTestFailures = rs filter { r => 
          (testRuns contains r.runId.toString) || ((testRuns contains "last") && (r.runId == lastId))
        } flatMap { r =>
          val score = r.primaryMetricOr(metric).score
          val referenceValue = computeReferenceValue(r.params)
          val positiveDeviation = (score - referenceValue) / referenceValue
          val negativeDeviation = (referenceValue - score) / referenceValue
          if (positiveDeviation > tolerance) {
            Seq(f"Error: score for run ${r.runId}%s (params: ${paramsStr(r.params)}) exceeds reference value by ${positiveDeviation*100}%2.1f%% ($score%s).")
          } else if (negativeDeviation > tolerance) {
            Seq(f"Warning: score for run ${r.runId}%s (params: ${paramsStr(r.params)}) is less than reference value by ${negativeDeviation*100}%2.1f%% ($score%s).")
          } else {
            Nil
          }
        }
        if (regressionTestFailures.nonEmpty) {
          throw new RuntimeException(s"One or more regression test failures occurred:\n${regressionTestFailures mkString "\n"}")
        }
      }
    }

  def queryResults(go: GlobalOptions, runs: Seq[String], benchs: Seq[String], extract: Seq[String], regex: Boolean, scorePrecision: Int, pivot: Seq[String], raw: Boolean, metric: Option[String]): Unit = try {
    new Global(go).use { g =>
      val multi = runs.size > 1
      val allRs = g.dao.run(g.dao.checkVersion andThen g.dao.queryResults(runs))
        .map { case (rr, runId) => RunResult.fromDb(rr, runId, multi) }
      val rs = RunResult.extract(extract, regex, RunResult.filterByName(benchs, allRs)).toSeq
      val benchmarkLabel = metric match {
        case Some(id) => s"Benchmark [$id]"
        case None => "Benchmark"
      }
      if(raw) {
        print("[")
        rs.zipWithIndex.foreach { case (r, idx) =>
          if(idx == 0) println()
          else println(",")
          val c = ConfigFactory.parseString(r.db.rawData)
          val c2 = ConfigFactory.parseMap(
            (Map("benchmark" -> r.name) ++ r.params.map { case (k, v) => ("params."+k, v) }).asJava
          ).withFallback(c).resolve()
          val lines = c2.root.render(ConfigRenderOptions.defaults().setOriginComments(false)).linesIterator.filterNot(_.isEmpty).toIndexedSeq
          print(lines.mkString("    ", "\n    ", ""))
        }
        println()
        println("]")
      } else {
        import TableFormatter._
        val allParamNames = rs.flatMap(_.params.keys).distinct.toVector
        val pivotSet = pivot.toSet
        val paramNames = allParamNames.filterNot(pivotSet.contains)
        if(pivot.isEmpty) {
          val columns = Vector(
            Seq(Format()),
            paramNames.map(s => Format(Align.Right)),
            Seq(Format(), Format(Align.Right), Format(Align.Right), Format(Align.Right), Format())
          ).flatten
          val header = ((benchmarkLabel +: paramNames.map(s => s"($s)")) ++ Vector("Mode", "Cnt", "Score", "Error", "Units")).map(formatHeader)
          val data = rs.iterator.map { r =>
            val score = r.primaryMetricOr(metric)
            Vector(
              Seq(r.name),
              paramNames.map(k => r.params.getOrElse(k, "")),
              Seq(r.db.mode, r.cnt,
                ScoreFormatter(score.score, scorePrecision), ScoreFormatter(score.scoreError, scorePrecision),
                score.scoreUnit)
            ).flatten
          }.toVector
          val table = new TableFormatter(go).apply(columns, Vector(header, null) ++ data)
          table.foreach(println)
        } else if(paramNames.length + pivotSet.size != allParamNames.length) {
            logger.error(s"Illegal pivot parameters.")
        } else {
          val (pivoted, pivotSets) = RunResult.pivot(rs, pivot, paramNames, metric)
          val columns = Vector(
            Seq(Format()),
            paramNames.map(s => Format(Align.Right)),
            Seq(Format(), Format(Align.Right)),
            pivotSets.flatMap(_ => Seq(Format(Align.Right), Format(Align.Right))),
            Seq(Format())
          ).flatten
          val header1 = Vector(
            Seq(Formatted(pivot.mkString("(", ", ", ")"), align=Some(Align.Right), style=Some(Style.Header))),
            (0 until (paramNames.length+2)).map(_ => ""),
            pivotSets.flatMap(ps => Seq(Formatted(ps.mkString(", "), colspan=2, align=Some(Align.Center)))),
            Seq(""),
          ).flatten
          val header2 = Vector(
            Seq(benchmarkLabel),
            paramNames.map(s => s"($s)"),
            Seq("Mode", "Cnt"),
            pivotSets.flatMap(_ => Seq("Score", "Error")),
            Seq("Units"),
          ).flatten.map(formatHeader)
          val data = pivoted.iterator.map { case (r, pivotData) =>
            val scoreData = pivotData.flatMap {
              case Some(rr) =>
                val score = rr.primaryMetricOr(metric)
                Seq(ScoreFormatter(score.score, scorePrecision), ScoreFormatter(score.scoreError, scorePrecision))
              case None => Seq(null, null)
            }
            Vector(
              Seq(r.name),
              paramNames.map(k => r.params.getOrElse(k, "")),
              Seq(r.db.mode, r.cnt),
              scoreData,
              Seq(r.primaryMetricOr(metric).scoreUnit)
            ).flatten
          }.toVector
          val table = new TableFormatter(go).apply(columns, Vector(header1, header2, null) ++ data)
          go.validate()
          table.foreach(println)
        }
      }
    }
  } catch {
    case ex: PatternSyntaxException => logger.error(ex.toString)
  }

  def createChart(go: GlobalOptions, runs: Seq[String], benchs: Seq[String], extract: Seq[String], regex: Boolean, scorePrecision: Int, pivot: Seq[String], metric: Option[String], template: Option[Path], out: Option[Path], cmdLine: Array[String]): Unit = {
    new Global(go).use { g =>
      val multi = runs.size > 1
      val allRs = g.dao.run(g.dao.checkVersion andThen g.dao.queryResults(runs))
        .map { case (rr, runId) => RunResult.fromDb(rr, runId, multi) }
      val rs = RunResult.extract(extract, regex, RunResult.filterByName(benchs, allRs)).toSeq
      val allParamNames = rs.flatMap(_.params.keys).distinct.toVector
      val pivotSet = pivot.toSet
      val paramNames = allParamNames.filterNot(pivotSet.contains)
      val gen = new GenerateCharts(go, scorePrecision, metric)
      val data =
        if(pivot.isEmpty) gen.generate(rs)
        else if(paramNames.length + pivotSet.size != allParamNames.length) {
          logger.error(s"Illegal pivot parameters.")
          ""
        } else {
          val (pivoted, pivotSets) = RunResult.pivot(rs, pivot, paramNames, metric)
          gen.generatePivoted(pivoted, pivotSets, pivot, paramNames)
        }
      val templateHtml = template match {
        case Some(p) =>
          if(!Files.isRegularFile(p)) {
            logger.error(s"Template file $p not found.")
            None
          } else Some(File(p).contentAsString(charset = "UTF-8"))
        case None => Some(Resource.getAsString("chart-template.html")(charset = "UTF-8"))
      }
      go.validate()
      templateHtml.foreach { tmpl =>
        val html = tmpl.replaceAllLiterally("@benchmarkData", data).replaceAllLiterally("@commandLine", cmdLine.mkString(" "))
        out match {
          case Some(path) =>
            if(path.toString == "-") println(html.trim)
            else {
              File(path).write(html)(charset = "UTF-8")
              println(s"Charts written to '$path'.")
            }
          case None =>
            val f = File(java.io.File.createTempFile("charts-", ".html").toPath)
            f.write(html)(charset = "UTF-8")
            go.openInBrowser(f.uri)
        }
      }
    }
  }

  def listRuns(go: GlobalOptions, limit: Option[Int], gitData: Boolean, platformData: Boolean): Unit = {
    new Global(go).use { g =>
      val runs = g.dao.run(g.dao.checkVersion andThen g.dao.listTestRuns(limit))
      import TableFormatter._
      var columns = Vector(Format(Align.Right), Format(), Format())
      var headers = Vector("ID", "Timestamp", "Msg")
      if(gitData) {
        columns ++= Vector(Format(), Format(), Format(), Format())
        headers ++= Vector("Git SHA", "Git Timestamp", "Git Origin", "Git Upstream")
      }
      if(platformData) {
        columns ++= Vector(Format(), Format(), Format(), Format(), Format(), Format())
        headers ++= Vector("Host Name", "User Name", "Java Vendor", "Java Version", "JVM Name", "JVM Version")
      }
      val data = runs.iterator.map { r =>
        var line = IndexedSeq(r.runId, r.timestamp, r.message)
        if(gitData) line = line ++ IndexedSeq(r.gitSha.map(_.take(7)), r.gitTimestamp, r.gitOrigin, r.gitUpstream)
        if(platformData) line = line ++ IndexedSeq(r.hostname, r.username, r.javaVendor, r.javaVersion, r.jvmName, r.jvmVersion)
        line
      }.toIndexedSeq
      val table = new TableFormatter(go).apply(columns, Vector(headers.map(formatHeader), null) ++ data)
      table.foreach(println)
      val reached = if(limit.getOrElse(-1) == runs.size) " (limit reached)" else ""
      println(s"${runs.size} test runs found$reached.")
    }
  }
}
