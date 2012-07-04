/**
 * Copyright (C) 2012 Typesafe, Inc. <http://www.typesafe.com>
 */

package com.typesafe.zinc

import java.io.File
import sbt.{ ConsoleLogger, Level, Logger, Relation }
import sbt.inc.Analysis

object Util {

  //
  // Logging
  //

  /**
   * Create a new logger based on quiet, level, and color settings.
   */
  def logger(quiet: Boolean, level: Level.Value, color: Boolean): Logger = {
    if (quiet) {
      new SilentLogger
    } else {
      val log = ConsoleLogger(useColor = ConsoleLogger.formatEnabled && color); log.setLevel(level); log
    }
  }

  /**
   * A logger that does nothing.
   */
  class SilentLogger extends Logger {
    def trace(t: => Throwable): Unit = ()
    def success(message: => String): Unit = ()
    def log(level: Level.Value, message: => String): Unit = ()
  }

  //
  // Time
  //

  /**
   * Current timestamp and time passed since start time.
   */
  def timing(start: Long): String = {
    val end = System.currentTimeMillis
    "at %s [%s]" format (dateTime(end), duration(end - start))
  }

  /**
   * Format a minutes:seconds.millis time.
   */
  def duration(millis: Long): String = {
    val secs = millis / 1000
    val (m, s, ms) = (secs / 60, secs % 60, millis % 1000)
    if (m > 0) "%d:%02d.%03ds" format (m, s, ms)
    else "%d.%03ds" format (s, ms)
  }

  /**
   * Creating a readable timestamp.
   */
  def dateTime(time: Long): String = {
    java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(time))
  }

  //
  // Normalising files
  //

  /**
   * Normalise file in relation to actual current working directory.
   */
  def normalise(cwd: Option[File])(file: File): File = {
    if (cwd.isDefined && !file.isAbsolute) new File(cwd.get, file.getPath) else file
  }

  /**
   * Normalise optional file in relation to actual current working directory.
   */
  def normaliseOpt(cwd: Option[File])(optFile: Option[File]): Option[File] = {
    if (cwd.isDefined) optFile map normalise(cwd) else optFile
  }

  /**
   * Normalise sequence of files in relation to actual current working directory.
   */
  def normaliseSeq(cwd: Option[File])(files: Seq[File]): Seq[File] = {
    if (cwd.isDefined) files map normalise(cwd) else files
  }

  /**
   * Normalise file map in relation to actual current working directory.
   */
  def normaliseMap(cwd: Option[File])(mapped: Map[File, File]): Map[File, File] = {
    if (cwd.isDefined) mapped map { case (l, r) => (normalise(cwd)(l), normalise(cwd)(r)) } else mapped
  }

  //
  // Properties
  //

  /**
   * Create int from system property.
   */
  def intProperty(name: String, default: Int): Int = {
    val value = System.getProperty(name)
    if (value ne null) try value.toInt catch { case _: Exception => default } else default
  }

  /**
   * Create set of strings, split by comma, from system property.
   */
  def stringSetProperty(name: String, default: Set[String]): Set[String] = {
    val value = System.getProperty(name)
    if (value ne null) (value split ",").toSet else default
  }

  /**
   * Create a file, default empty, from system property.
   */
  def fileProperty(name: String): File = new File(System.getProperty(name, ""))

  /**
   * Create an option file from system property.
   */
  def optFileProperty(name: String): Option[File] = Option(System.getProperty(name, null)).map(new File(_))

  /**
   * Get a property from a properties file resource in the classloader.
   */
  def propertyFromResource(resource: String, property: String, classLoader: ClassLoader): Option[String] = {
    val props = propertiesFromResource(resource, classLoader)
    Option(props.getProperty(property))
  }

  /**
   * Get all properties from a properties file resource in the classloader.
   */
  def propertiesFromResource(resource: String, classLoader: ClassLoader): java.util.Properties = {
    val props = new java.util.Properties
    val stream = classLoader.getResourceAsStream(resource)
    try { props.load(stream) }
    catch { case e: Exception => }
    finally { stream.close }
    props
  }

  /**
   * Set system properties.
   */
  def setProperties(props: Seq[String]): Unit = {
    for (prop <- props) {
      val kv = prop split "="
      if (kv.length == 2) System.setProperty(kv(0), kv(1))
    }
  }

  //
  // Timers
  //

  /**
   * Simple duration regular expression.
   */
  val Duration = """(\d+)([hms])""".r

  /**
   * Milliseconds from string duration of the form Nh|Nm|Ns, otherwise default.
   */
  def duration(arg: String, default: Long): Long =
    arg match {
      case Duration(length, unit) =>
        val multiplier = unit match {
          case "h" => 60 * 60 * 1000
          case "m" => 60 * 1000
          case "s" => 1000
          case _ => 0
        }
        try { length.toLong * multiplier } catch { case _: Exception => default }
      case _ => default
    }

  /**
   * Schedule a resettable timer.
   */
  def timer(delay: Long)(body: => Unit) = new Alarm(delay)(body)

  /**
   * Resettable timer.
   */
  class Alarm(delay: Long)(body: => Unit) {
    import java.util.{ Timer, TimerTask }

    private[this] var timer: Timer = _
    private[this] var task: TimerTask = _

    schedule()

    private[this] def schedule(): Unit = {
      if ((task eq null) && delay > 0) {
        if (timer eq null) timer = new Timer(true) // daemon = true
        task = new TimerTask { def run = body }
        timer.schedule(task, delay)
      }
    }

    def reset(): Unit = synchronized {
      if (task ne null) { task.cancel(); task = null }
      schedule()
    }

    def cancel(): Unit = if (timer ne null) timer.cancel()
  }

  //
  // Analysis
  //

  /**
   * Print analysis relations to file.
   */
  def printRelations(analysis: Analysis, output: Option[File], cwd: Option[File]): Unit = {
    for (file <- output) {
      val userDir = (cwd getOrElse Setup.Defaults.userDir) + "/"
      def noCwd(path: String) = path stripPrefix userDir
      def keyValue(kv: (Any, Any)) = "   " + noCwd(kv._1.toString) + " -> " + noCwd(kv._2.toString)
      def relation(r: Relation[_, _]) = (r.all.toSeq map keyValue).sorted.mkString("\n")
      import analysis.relations.{ srcProd, binaryDep, internalSrcDep, externalDep, classes }
      val relationStrings = Seq(srcProd, binaryDep, internalSrcDep, externalDep, classes) map relation
      val output = """
        |products:
        |%s
        |binary dependencies:
        |%s
        |source dependencies:
        |%s
        |external dependencies:
        |%s
        |class names:
        |%s
        """.trim.stripMargin.format(relationStrings: _*)
      sbt.IO.write(file, output)
    }
  }

  //
  // Debug output
  //

  /**
   * General utility for displaying objects for debug output.
   */
  def show(thing: Any, output: String => Unit, prefix: String = "", level: Int = 0): Unit = {
    def out(s: String) = output(("   " * level) + s)
    thing match {
      case (label: Any, value: Any) => show(value, output, label.toString + " = ", level)
      case Some(value: Any) => show(value, output, prefix, level)
      case None => out(prefix)
      case t: Traversable[_] if t.isEmpty => out(prefix + "{}")
      case t: Traversable[_] =>
        out(prefix + "{")
        t foreach { a => show(a, output, "", level + 1) }
        out("}")
      case any => out(prefix + any.toString)
    }
  }

  def counted(count: Int, prefix: String, single: String, plural: String): String = {
    count.toString + " " + prefix + (if (count == 1) single else plural)
  }
}