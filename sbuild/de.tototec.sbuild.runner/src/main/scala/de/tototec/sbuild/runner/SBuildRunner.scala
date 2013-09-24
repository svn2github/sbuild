package de.tototec.sbuild.runner

import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.util.Properties

import scala.collection.JavaConverters.asScalaBufferConverter
import scala.collection.JavaConverters.mapAsScalaMapConverter

import org.fusesource.jansi.Ansi
import org.fusesource.jansi.Ansi.Color.CYAN
import org.fusesource.jansi.Ansi.Color.GREEN
import org.fusesource.jansi.Ansi.Color.RED
import org.fusesource.jansi.Ansi.ansi
import org.fusesource.jansi.AnsiConsole

import de.tototec.cmdoption.CmdOption
import de.tototec.cmdoption.CmdlineParser
import de.tototec.cmdoption.CmdlineParserException
import de.tototec.sbuild.BuildFileProject
import de.tototec.sbuild.BuildScriptAware
import de.tototec.sbuild.ExecutionFailedException
import de.tototec.sbuild.InvalidApiUsageException
import de.tototec.sbuild.LogLevel
import de.tototec.sbuild.Project
import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.ProjectReader
import de.tototec.sbuild.SBuildConsoleLogger
import de.tototec.sbuild.SBuildException
import de.tototec.sbuild.SBuildLogger
import de.tototec.sbuild.SBuildVersion
import de.tototec.sbuild.Target
import de.tototec.sbuild.TargetAware
import de.tototec.sbuild.TargetNotFoundException
import de.tototec.sbuild.TargetRef
import de.tototec.sbuild.Util
import de.tototec.sbuild.execute.ExecutedTarget
import de.tototec.sbuild.execute.InMemoryTransientTargetCache
import de.tototec.sbuild.execute.LoggingTransientTargetCache
import de.tototec.sbuild.execute.TargetExecutor

object SBuildRunner extends SBuildRunner {

  System.setProperty("http.agent", "SBuild/" + SBuildVersion.osgiVersion)

  def main(args: Array[String]) {
    AnsiConsole.systemInstall
    val retval = run(args)
    AnsiConsole.systemUninstall
    sys.exit(retval)
  }

}

/**
 * SBuild command line application. '''API is not stable!'''
 */
class SBuildRunner {

  private[runner] var verbose = false

  private[this] var log: SBuildLogger = new SBuildConsoleLogger(LogLevel.info)

  //  private val persistentTargetCache = new PersistentTargetCache()

  /**
   * Create a new build file.
   * If a file with the same name exists in the stubDir, then this one will be copied, else a build file with one target will be created.
   * If the file already exists, it will throw an [[de.tototec.sbuild.SBuildException]].
   *
   */
  def createSBuildStub(projectFile: File, stubDir: File) {
    if (projectFile.exists) {
      throw new SBuildException("File '" + projectFile.getName + "' already exists. ")
    }

    val sbuildStub = new File(stubDir, projectFile.getName) match {

      case stubFile if stubFile.exists =>
        val source = io.Source.fromFile(stubFile)
        val text = source.mkString
        source.close
        text

      case _ =>
        val className = if (projectFile.getName.endsWith(".scala")) {
          projectFile.getName.substring(0, projectFile.getName.length - 6)
        } else { projectFile.getName }

        s"""|import de.tototec.sbuild._
          |import de.tototec.sbuild.TargetRefs._
          |import de.tototec.sbuild.ant._
          |import de.tototec.sbuild.ant.tasks._
          |
          |@version("${SBuildVersion.osgiVersion}")
          |@classpath("mvn:org.apache.ant:ant:1.8.4")
          |class ${className}(implicit _project: Project) {
          |
          |  Target("phony:hello") help "Say hello" exec {
          |    AntEcho(message = "Hello!")
          |  }
          |
          |}
          |""".stripMargin
    }

    val outStream = new PrintStream(new FileOutputStream(projectFile))
    try {
      outStream.print(sbuildStub)
    } finally {
      if (outStream != null) outStream.close
    }
  }

  /**
   * Check all targets of the given projects for problems.
   *
   * Checked problems are:
   * - invalid target names
   * - missing dependencies
   * - cycles in dependencies
   * - missing scheme handlers
   *
   * @return A sequence of pairs of problematic target and a error message.
   */
  def checkTargets(projects: Seq[Project])(implicit baseProject: Project): Seq[(Target, String)] = {

    val cache = new TargetExecutor.DependencyCache(baseProject)

    val targetsToCheck = projects.flatMap { p =>
      p.targets
    }
    // Console.println("About to check targets: "+targetsToCheck.mkString(", "))
    var errors: Seq[(Target, String)] = Seq()
    targetsToCheck.foreach { target =>
      log.log(LogLevel.Info, "Checking target: " + target.formatRelativeTo(baseProject))
      try {
        cache.fillTreeRecursive(target, Nil)
        log.log(LogLevel.Info, "  \t" + fOk("OK"))
      } catch {
        case e: SBuildException =>
          log.log(LogLevel.Info, "  \t" + fError("FAILED: " + e.getMessage))
          errors ++= Seq(target -> e.getMessage)
      }
    }
    errors
  }

  /**
   * Check a project for target definition with cacheable or evictCache property.
   * If cacheable targets are found but not at least one evictCache target, return a error message.
   */
  def checkCacheableTargets(project: Project, printWarning: Boolean)(implicit baseProject: Project): Option[String] = {
    val targets = project.targets
    val cacheable = targets.filter(_.isCacheable)
    val evict = targets.filter(_.evictsCache.isDefined)

    if (evict.isEmpty && !cacheable.isEmpty) {
      val msg = s"""Project ${formatProject(project)}" contains ${cacheable.size} cacheable target, but does not declare any target with "evictCache"."""
      if (printWarning)
        project.log.log(LogLevel.Warn, msg)
      Some(msg)
    } else None
  }

  private[this] def errorOutput(e: Throwable, msg: String = null) = {
    log.log(LogLevel.Error, "")

    if (msg != null)
      log.log(LogLevel.Error, fError(msg).toString)

    if (e.isInstanceOf[BuildScriptAware] && e.asInstanceOf[BuildScriptAware].buildScript.isDefined)
      log.log(LogLevel.Error, fError("Project: ").toString + fErrorEmph(e.asInstanceOf[BuildScriptAware].buildScript.get.getPath).toString)

    if (e.isInstanceOf[TargetAware] && e.asInstanceOf[TargetAware].targetName.isDefined)
      log.log(LogLevel.Error, fError("Target:  ").toString + fErrorEmph(e.asInstanceOf[TargetAware].targetName.get).toString)

    log.log(LogLevel.Error, fError("Details: " + e.getLocalizedMessage).toString)

    if (e.getCause() != null && e.getCause().isInstanceOf[InvocationTargetException] && e.getCause().getCause() != null)
      log.log(LogLevel.Error, fError("Reflective invokation message: " + e.getCause().getCause().getLocalizedMessage()).toString())
  }

  private[this] def readAndApplyGlobal(config: Config) {
    val rcFile = new File(System.getProperty("user.home"), ".sbuildrc")
    if (!rcFile.exists()) log.log(LogLevel.Debug, "No global settings file found: " + rcFile)
    else {
      log.log(LogLevel.Debug, "About to read global settings file: " + rcFile)
      val props = new Properties()
      try {
        props.load(new BufferedReader(new FileReader(rcFile)))
      } catch {
        case e: Exception => log.log(LogLevel.Error, s"""Could not read settings file "${rcFile.getPath}".""", e)
      }

      def readSetting(key: String, applyFunction: String => Unit) = try {
        props.getProperty(key) match {
          case null =>
          case value => applyFunction(value)
        }
      } catch {
        case e: Exception => log.log(LogLevel.Error, s"""Could not read setting "${key}" from settings file "${rcFile.getPath}".""", e)
      }

      readSetting("jobs", jobCount => if (config.parallelJobs.isEmpty) config.parallelJobs = Some(jobCount.toInt))
    }
  }

  /**
   * Run the SBuild (command line) application with the given arguments.
   *
   * @return The exit value, `0` means no errors.
   */
  def run(args: Array[String]): Int = {
    val bootstrapStart = System.currentTimeMillis

    val aboutAndVersion = "SBuild " + SBuildVersion.version + " (c) 2011 - 2013, ToToTec GbR, Tobias Roeser"

    try {

      val config = new Config()
      val classpathConfig = new ClasspathConfig()
      val cmdlineConfig = new {
        @CmdOption(names = Array("--version"), isHelp = true, description = "Show SBuild version.")
        var showVersion = false

        @CmdOption(names = Array("--help", "-h"), isHelp = true, description = "Show this help screen.")
        var help = false

        @CmdOption(names = Array("--no-color"), description = "Disable colored output.")
        var noColor = false

        @CmdOption(names = Array("--no-global"), description = "Do not read global settings from <USER HOME>/.sbuildrc.")
        var noGlobal = false
      }
      val cp = new CmdlineParser(config, classpathConfig, cmdlineConfig)
      cp.setResourceBundle("de.tototec.sbuild.runner.Messages", getClass.getClassLoader())
      cp.setAboutLine(aboutAndVersion)
      cp.setProgramName("sbuild")

      cp.parse(args: _*)
      if (!cmdlineConfig.noGlobal) {
        readAndApplyGlobal(config)
      }

      if (cmdlineConfig.noColor)
        Ansi.setEnabled(false)

      if (cmdlineConfig.help) {
        cp.usage
        return 0
      }

      if (cmdlineConfig.showVersion) {
        println(aboutAndVersion)
        return 0
      }

      if (config.justClean || config.justCleanRecursive) {
        def cleanStateDir(dir: File, recursive: Boolean): Boolean = {
          var success = true
          val stateDir = new File(dir, ".sbuild")
          if (stateDir.exists && stateDir.isDirectory) {
            log.log(LogLevel.Info, "Deleting " + stateDir.getPath())
            success = Util.delete(stateDir) && success
          }
          if (recursive) {
            val files = dir.listFiles
            if (files != null) files.map { file =>
              if (file.isDirectory) {
                success = cleanStateDir(file, recursive) && success
              }
            }
          }
          success
        }
        val baseDir = new File(new File(".").getAbsoluteFile.toURI.normalize)
        if (cleanStateDir(baseDir, config.justCleanRecursive)) return 0 else return 1
      }

      run(config = config, classpathConfig = classpathConfig, bootstrapStart = bootstrapStart)

    } catch exceptionHandler(rethrowInVerboseMode = true)
  }

  def exceptionHandler(rethrowInVerboseMode: Boolean): PartialFunction[Throwable, Int] = {
    case e: CmdlineParserException =>
      errorOutput(e, "SBuild commandline was invalid. Please use --help for supported commandline options.")
      if (rethrowInVerboseMode && verbose) throw e
      1
    case e: InvalidApiUsageException =>
      errorOutput(e, "SBuild detected a invalid usage of SBuild API. Please consult the API Refence Documentation at http://sbuild.tototec.de .")
      if (rethrowInVerboseMode && verbose) throw e
      1
    case e: ProjectConfigurationException =>
      errorOutput(e, "SBuild detected a failure in the project configuration or the build scripts.")
      if (rethrowInVerboseMode && verbose) throw e
      1
    case e: TargetNotFoundException =>
      errorOutput(e, "SBuild failed because an invalid target was requested. For a list of available targets use --list-targets or --list-targets-recursive. Use --help for a list of other commandline options.")
      if (rethrowInVerboseMode && verbose) throw e
      1
    case e: ExecutionFailedException =>
      errorOutput(e, "SBuild detected a failure in a target execution.")
      if (rethrowInVerboseMode && verbose) throw e
      1
    case e: Exception =>
      errorOutput(e, "SBuild failed with an unexpected exception (" + e.getClass.getSimpleName + ").")
      if (rethrowInVerboseMode && verbose) throw e
      1
  }

  /**
   * Sort projects by their fully qualified build file name.
   * The base project is always sorted to top position.
   */
  def projectSorter(baseProject: Project)(l: Project, r: Project): Boolean = (l, r) match {
    // ensure main project file is on top
    case (l, r) if l.eq(baseProject) => true
    case (l, r) if r.eq(baseProject) => false
    case (l, r) => l.projectFile.compareTo(r.projectFile) < 0
  }

  def run(config: Config, classpathConfig: ClasspathConfig, bootstrapStart: Long = System.currentTimeMillis): Int = {

    SBuildRunner.verbose = config.verbose
    if (verbose) {
      log = new SBuildConsoleLogger(LogLevel.debug)
      Util.log = log
    }

    val projectFile = new File(config.buildfile)

    if (config.createStub) {
      createSBuildStub(projectFile, new File(classpathConfig.sbuildHomeDir, "stub"))
      return 0
    }

    log.log(LogLevel.Info, "Initializing project...")
    val fileLocker = new FileLocker()
    val projectReader: ProjectReader = new SimpleProjectReader(classpathConfig, log, clean = config.clean, fileLocker)
    val project = projectReader.readAndCreateProject(projectFile, config.defines.asScala.toMap, None, Some(log)).asInstanceOf[BuildFileProject]

    val additionalProjects = config.additionalBuildfiles.map { buildfile =>
      project.findModule(buildfile) match {
        case None =>
          // Create and add new module and copy configs
          val module = project.findOrCreateModule(new File(buildfile).getAbsolutePath, copyProperties = false)
          config.defines.asScala foreach {
            case (key, value) => module.addProperty(key, value)
          }
          module

        case Some(module) => module // Module already defined
      }
    }

    // Now, that we loaded all projects, we can release some resources. 
    ProjectScript.dropCaches

    log.log(LogLevel.Debug, "Targets: \n" + project.targets.mkString("\n"))

    /**
     * Format all non-implicit targets of a project.
     * If the project is not the main/entry project, to project name will included in the formatted name.
     */
    def formatTargetsOf(project: Project, baseProject: Project): String = {
      project.targets.sortBy(_.name).map { t =>
        t.formatRelativeTo(baseProject) + " \t" + (t.help match {
          case null => ""
          case s: String => s
        })
      }.mkString("\n")
    }

    // Format listing of target and return
    if (config.listTargets || config.listTargetsRecursive) {
      val projectsToList = if (config.listTargetsRecursive) {
        project.projectPool.projects
      } else {
        project :: additionalProjects.toList
      }
      val out = projectsToList.sortWith(projectSorter(project) _).map { p => formatTargetsOf(p, project) }
      log.log(LogLevel.Info, out.mkString("\n\n"))
      // early exit
      return 0
    }

    if (config.listModules) {
      val moduleNames = project.projectPool.projects.sortWith(projectSorter(project) _).map {
        p => formatProject(p)(project)
      }
      log.log(LogLevel.Info, moduleNames.mkString("\n"))
      return 0
    }

    // Check targets requested from cmdline an throw a exception, if invalid targets were requested
    val (requested: Seq[Target], invalid: Seq[String]) =
      determineRequestedTargets(targets = config.params.asScala, supportCamelCaseShortCuts = true)(project)
    if (!invalid.isEmpty) {
      throw new TargetNotFoundException("Invalid target" + (if (invalid.size > 1) "s" else "") + " requested: " + invalid.mkString(", ") + ".");
    }
    val targets = requested

    // The dependencyTree will be populated by the treePrinter, in case it was requested on commandline

    class DependencyTree(baseProject: Project) {
      private var dependencyTree = List[(Int, Target)]()

      def addNode(depth: Int, node: Target): Unit = dependencyTree = (depth -> node) :: dependencyTree

      def format(showGoUp: Boolean = true): String = {
        var lastDepth = 0
        var lastShown = Map[Int, Target]()
        val lines = dependencyTree.reverse.map {
          case (depth, target) =>

            var prefix = if (lastDepth > depth && depth > 0) {
              List.fill(depth - 1)("  ").mkString + "  (" + lastShown(depth - 1).formatRelativeTo(baseProject) + ")\n"
            } else ""

            lastDepth = depth
            lastShown += (depth -> target)
            val line = prefix + List.fill(depth)("  ").mkString + "  " + target.formatRelativeTo(baseProject)

            line
        }
        lines.mkString("\n")
      }
    }

    val depTree =
      if (config.showDependencyTree) Some(new DependencyTree(project))
      else None

    val treePrinter: Option[(Int, Target) => Unit] = depTree.map { t => t.addNode _ }

    val dependencyCache = new TargetExecutor.DependencyCache(project)

    val targetExecutor = new TargetExecutor(project, log)

    // The execution plan (chain) will be evaluated on first need
    lazy val chain: Seq[ExecutedTarget] = {
      if (!targets.isEmpty && !config.noProgress) {
        log.log(LogLevel.Debug, "Calculating dependency tree...")
      }
      val chain = targetExecutor.preorderedDependenciesForest(targets, skipExec = true, treePrinter = treePrinter, dependencyCache = dependencyCache)
      log.log(LogLevel.Debug, "Target Dependency Cache: " + dependencyCache.cached.map {
        case (t, d) => "\n  " + t.formatRelativeTo(project) + " -> " + d.flatten.map {
          dep => dep.formatRelativeTo(project)
        }.mkString(", ")
      })
      chain
    }

    depTree.foreach { t =>
      // trigger chain
      chain
      // print output
      log.log(LogLevel.Info, "Dependency tree:\n" + t.format())
      // early exit
      return 0
    }

    // Execution plan
    def execPlan(chain: Seq[ExecutedTarget]) = {
      var line = 0
      var plan: List[String] = "Execution plan:" :: Nil

      def preorderDepthFirst(nodes: Seq[ExecutedTarget]): Unit = nodes.foreach { node =>
        node.dependencies match {
          case Seq() =>
            line += 1
            plan = ("  " + line + ". " + node.target.formatRelativeTo(project)) :: plan
          case deps =>
            preorderDepthFirst(deps)
        }
      }

      preorderDepthFirst(chain)
      plan.reverse.mkString("\n")
    }

    if (config.showExecutionPlan) {
      log.log(LogLevel.Info, execPlan(chain))
      // early exit
      return 0
    } else {
      log.log(LogLevel.Debug, execPlan(chain))
    }

    if (config.check || config.checkRecusive) {

      val projectsToCheck = if (config.checkRecusive) {
        project.projectPool.projects
      } else {
        Seq(project) ++ additionalProjects
      }

      val projects = projectsToCheck.sortWith(projectSorter(project) _)
      val errors = checkTargets(projects)(project)

      if (!errors.isEmpty) log.log(LogLevel.Error, s"Found the following ${fError(errors.size.toString)} problems:")
      errors.foreach {
        case (target, message) =>
          log.log(LogLevel.Error, fError(target.formatRelativeTo(project) + ": " + message).toString)
      }

      // also check for possible caching problems
      projects.foreach { p => checkCacheableTargets(p, printWarning = true)(project) }

      // When errors, then return with 1 else with 0
      return if (errors.isEmpty) 0 else 1
    }

    val bootstrapTime = System.currentTimeMillis - bootstrapStart

    val localExceptionHandler: PartialFunction[Throwable, Int] =
      if (config.repeatAfterSec > 0) exceptionHandler(rethrowInVerboseMode = false)
      else { case t: Throwable => throw t }

    var repeat = true
    var lastRepeatStart = 0L
    while (repeat) {
      repeat = config.repeatAfterSec > 0
      if (repeat) {
        val nextStart = lastRepeatStart + (config.repeatAfterSec * 1000)
        nextStart - System.currentTimeMillis match {
          case msec if msec > 0 =>
            log.log(LogLevel.Info, s"Repeating execution in ${msec} milliseconds...")
            Thread.sleep(msec)
          case _ =>
            log.log(LogLevel.Info, s"Repeating execution...")
        }
      }
      lastRepeatStart = System.currentTimeMillis

      val parallelExecContext = config.parallelJobs.flatMap {
        case 1 => None
        case jobCount =>
          // Multiple jobs
          val explicitJobCount = jobCount match {
            case x if x > 1 => Some(x)
            case 0 => None
            case _ => None // TODO: this should be a config error
          }
          log.log(LogLevel.Debug, "Enabled parallel processing. Explicit parallel threads (None = nr of cpu cores): " + explicitJobCount.toString)
          Some(new TargetExecutor.ParallelExecContext(threadCount = explicitJobCount, baseProject = project))
      }

      try {
        // force evaluation of lazy val chain, if required, and switch afterwards from bootstrap to execution time benchmarking.
        val execProgress =
          if (config.noProgress) None
          else Some(new TargetExecutor.ExecProgress(maxCount = chain.foldLeft(0) { (a, b) => a + b.treeSize }))

        if (!targets.isEmpty && !config.noProgress) {
          log.log(LogLevel.Info, fPercent("[0%]") + " Executing...")
          log.log(LogLevel.Debug, "Requested targets: " + targets.map(_.formatRelativeTo(project)).mkString(" ~ "))
        }

        targetExecutor.preorderedDependenciesForest(targets, execProgress = execProgress, dependencyCache = dependencyCache,
          transientTargetCache = Some(new InMemoryTransientTargetCache() with LoggingTransientTargetCache),
          treeParallelExecContext = parallelExecContext)
        if (!targets.isEmpty && !config.noProgress) {
          log.log(LogLevel.Info, fPercent("[100%]") + " Execution finished. SBuild init time: " + bootstrapTime +
            " msec, Execution time: " + (System.currentTimeMillis - lastRepeatStart) + " msec")
        }

      } catch localExceptionHandler
    }

    log.log(LogLevel.Debug, "Finished")
    // return with 0, indicating no errors
    0
  }

  def determineRequestedTargets(targets: Seq[String], supportCamelCaseShortCuts: Boolean = false)(implicit project: Project): (Seq[Target], Seq[String]) = {

    // The compile will throw a warning here, so we use the erasure version and keep the intent as comment
    // val (requested: Seq[Target], invalid: Seq[String]) =
    val (requested, invalid) = targets.map { t =>
      project.determineRequestedTarget(targetRef = TargetRef(t), searchInAllProjects = false, supportCamelCaseShortCuts = supportCamelCaseShortCuts) match {
        case None => t
        case Some(target) => target
      }
    }.partition(_.isInstanceOf[Target])

    (requested.asInstanceOf[Seq[Target]], invalid.asInstanceOf[Seq[String]])
  }

  def formatProject(project: Project)(implicit baseProject: Project) =
    if (baseProject != project)
      baseProject.projectDirectory.toURI.relativize(project.projectFile.toURI).getPath
    else project.projectFile.getName

  import org.fusesource.jansi.Ansi._
  import org.fusesource.jansi.Ansi.Color._

  // It seems, under windows bright colors are not displayed correctly
  val isWindows = System.getProperty("os.name").toLowerCase().contains("win")

  def fPercent(text: => String) =
    if (isWindows) ansi.fg(CYAN).a(text).reset
    else ansi.fgBright(CYAN).a(text).reset
  def fTarget(text: => String) = ansi.fg(GREEN).a(text).reset
  def fMainTarget(text: => String) = ansi.fg(GREEN).bold.a(text).reset
  def fOk(text: => String) = ansi.fgBright(GREEN).a(text).reset
  def fError(text: => String) =
    if (isWindows) ansi.fg(RED).a(text).reset
    else ansi.fgBright(RED).a(text).reset
  def fErrorEmph(text: => String) =
    if (isWindows) ansi.fg(RED).bold.a(text).reset
    else ansi.fgBright(RED).bold.a(text).reset

}

