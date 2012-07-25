package de.tototec.sbuild

import java.io.File

class Project(_projectFile: File, projectReader: ProjectReader, _projectPool: Option[ProjectPool]) {

  def this(projectFile: File, projectReader: ProjectReader) {
    this(projectFile, projectReader, None)
  }

  val projectFile: File = _projectFile.getAbsoluteFile.getCanonicalFile
  if (!projectFile.exists)
    throw new ProjectConfigurationException("Project file '" + projectFile + "' does not exists")

  val projectDirectory: File = projectFile.getParentFile
  require(projectDirectory.exists, "Project directory '" + projectDirectory + "' does not exists")
  require(projectDirectory.isDirectory, "Project directory '" + projectDirectory + "' is not an directory")

  private[sbuild] val projectPool = _projectPool match {
    case Some(p) => p
    case None => new ProjectPool(this)
  }

  private var _modules: List[Project] = List()
  def modules: List[Project] = _modules

  private[sbuild] def findOrCreateModule(dirOrFile: String): Project = {
    // various checks
    if (projectReader == null) {
      throw new SBuildException("Does not know how to read the sub project")
    }

    val newProjectDirOrFile = Path(dirOrFile)(this)
    if (!newProjectDirOrFile.exists) {
      throw new ProjectConfigurationException("Subproject/module '" + dirOrFile + "' does not exists")
    }

    val newProjectFile = if (newProjectDirOrFile.isFile) {
      newProjectDirOrFile
    } else {
      new File(newProjectDirOrFile, "SBuild.scala")
    }

    if (!newProjectFile.exists) {
      throw new ProjectConfigurationException("Subproject/module '" + dirOrFile + "' does not exists")
    }

    // file exists checks passed, now check for double-added projects

    val projectAlreadyIncluded = projectPool.projects.find { p =>
      p.projectFile == newProjectFile
    }

    val module = projectAlreadyIncluded match {
      case Some(existing) => existing
      case _ =>
        val newProject = new Project(newProjectFile, projectReader, Some(projectPool))
        properties foreach {
          case (key, value) => newProject.addProperty(key, value)
        }

        projectReader.readProject(newProject, newProjectFile)

        projectPool.addProject(newProject)

        newProject
    }

    _modules = modules ::: List(module)

    module
  }

  /**
   * Map(file -> Target) of targets.
   */
  private[sbuild] var targets: Map[File, Target] = Map()

  def findOrCreateTarget(targetRef: TargetRef): Target = findTarget(targetRef) match {
    case Some(t) => t
    case None => createTarget(targetRef)
  }

  def createTarget(targetRef: TargetRef): Target = {
    if (explicitForeignProject(targetRef).isDefined) {
      throw new ProjectConfigurationException("Cannot create Target which explicitly references a different project in its name: " + targetRef)
    }

    val UniqueTargetFile(file, phony, handler) = uniqueTargetFile(targetRef)
    val proto = targetRef.explicitProto match {
      case Some(x) => x
      case None => "file"
    }

    val target: Target = new ProjectTarget(targetRef.name, file, phony, handler, this)
    targets += (file -> target)
    target
  }

  def findTarget(targetRef: TargetRef): Option[Target] = findTarget(targetRef, searchInAllProjects = false)

  /**
   * @param targetRef The target name to find.
   * @param searchInAllProjects If <code>true</code and no target was found in
   *        the current project and the TargetRef did not contain a project
   *        referrer, search in all other projects.
   */
  def findTarget(targetRef: TargetRef, searchInAllProjects: Boolean): Option[Target] = {
    explicitForeignProject(targetRef) match {
      case Some(pFile) =>
        projectPool.propjectMap.get(pFile) match {
          case None => throw new TargetNotFoundException("Could not found target: " + targetRef + ". Unknown project: " + pFile)
          case Some(p) => p.findTarget(targetRef, false)
        }
      case None =>
        uniqueTargetFile(targetRef) match {
          case UniqueTargetFile(file, phony, handler) => targets.get(file) match {
            // If nothing was found and the target in question is a file target and searchInAllProjects was requested, then search in other projects
            case None if searchInAllProjects && !phony =>
              // search in other projects
              val allCandidates = projectPool.projects.map { otherProj =>
                if (otherProj == this) {
                  None
                } else {
                  otherProj.targets.get(file) match {
                    // If the found one is phony, it is not a perfect match
                    case Some(t) if t.phony => None
                    case x => x
                  }
                }
              }
              val candidates = allCandidates.filter(_.isDefined)
              candidates.size match {
                case 0 => None
                case 1 => candidates.head
                case x =>
                  // Found more than one. What should we do about it? 
                  // For now just fail
                  throw new SBuildException("Found more than one match for dependency '" + file +
                    " in all registered modules. Occurences:" +
                    candidates.map {
                      case Some(t) => "\n - " + t.name + " [" + t.project.projectFile + "]"
                      case _ => // to avoid compiler warning
                    }.mkString)
              }

            case x => x
          }
        }
    }
  }

  case class UniqueTargetFile(file: File, phony: Boolean, handler: Option[SchemeHandler])

  def explicitForeignProject(targetRef: TargetRef): Option[File] = {
    val ownerProject: File = targetRef.explicitProject match {
      case Some(p) => if (p.isDirectory) {
        new File(p, "SBuild.scala")
      } else p
      case None => projectFile
    }

    if (ownerProject != projectFile) {
      Some(ownerProject)
    } else {
      None
    }
  }

  def uniqueTargetFile(targetRef: TargetRef): UniqueTargetFile = {
    val foreignProject = explicitForeignProject(targetRef)

    // file of phony is: projectfile + "/" + targetRef.name
    // as projectfile is a file, 

    targetRef.explicitProto match {
      // case Some("phony") => UniqueTargetFile(new File(projectFile, targetRef.nameWithoutProto), true, None)
      case Some("phony") => UniqueTargetFile(Path(targetRef.nameWithoutProto)(this), true, None)
      case None | Some("file") => UniqueTargetFile(Path(targetRef.nameWithoutProto)(this), false, None)
      case Some(proto) if foreignProject.isDefined => throw new ProjectConfigurationException("Cannot handle custom scheme target reference '" + targetRef + "' of foreign projects.")
      case Some(proto) => schemeHandlers.get(proto) match {
        case Some(handler) =>
          val handlerOutput = handler.localPath(targetRef.nameWithoutProto)
          val outputRef = new TargetRef(handlerOutput)(this)
          val phony = outputRef.explicitProto match {
            case Some("phony") => true
            case Some("file") => false
            case _ => throw new UnsupportedSchemeException("The defined scheme \"" + outputRef.explicitProto + "\" did not resolve to phony or file protocol.")
          }
          UniqueTargetFile(Path(outputRef.nameWithoutProto)(this), phony, Some(handler))
        case None => throw new UnsupportedSchemeException("No scheme handler registered, that supports scheme:" + proto)
      }
    }
  }

  def prerequisites(target: Target): List[Target] = prerequisites(target, searchInAllProjects = false)

  def prerequisites(target: Target, searchInAllProjects: Boolean): List[Target] = target.dependants.map { dep =>
    findTarget(dep, searchInAllProjects) match {
      case Some(target) => target
      case None =>
        // TODO: if none target was found, look in other project if they provide the target
        dep.explicitProto match {
          case Some("phony") =>
            throw new TargetNotFoundException("Non-existing prerequisite '" + dep.name + "' found for target: " + target)
          case None | Some("file") =>
            // try to find a file
            createTarget(dep) exec {
              val file = Path(dep.name)(this)
              if (!file.exists || !file.isDirectory) {
                throw new ProjectConfigurationException("Don't know how to build prerequisite: " + dep)
              }
            }
          case _ =>
            // A scheme handler might be able to resolve this thing
            createTarget(dep)
        }
    }
  }.toList

  //  def prerequisitesMap: Map[Target, List[Target]] = targets.values.map(goal => (goal, prerequisites(goal))).toMap

  private var schemeHandlers: Map[String, SchemeHandler] = Map()

  def registerSchemeHandler(scheme: String, handler: SchemeHandler) {
    schemeHandlers += ((scheme, handler))
  }

  private var _properties: Map[String, String] = Map()
  private[sbuild] def properties: Map[String, String] = _properties
  def addProperty(key: String, value: String) = if (_properties.contains(key)) {
    Util.verbose("Ignoring redefinition of property: " + key)
  } else {
    Util.verbose("Defining property: " + key + " with value: " + value)
    _properties += (key -> value)
  }

  private[sbuild] var antProject: Option[Any] = None

  /**
   * Check if the target is up-to-date. This check will respect the up-to-date state of direct dependencies.
   */
  def isTargetUpToDate(target: Target, dependenciesWhichWereUpToDateStates: Map[Target, Boolean] = Map()): Boolean = {
    lazy val prefix = "Target " + target.name + ": "
    def verbose(msg: => String) = Util.verbose(prefix + msg)
    def exit(cause: String): Boolean = {
      Util.verbose(prefix + "Not up-to-date: " + cause)
      false
    }

    if (target.phony) {
      if (target.action != null) exit("Target is phony")
      else {
        val deps = prerequisites(target)
        val firstNoUpToDateTarget = deps.find(t => !dependenciesWhichWereUpToDateStates.getOrElse(t, false))
        if (firstNoUpToDateTarget.isDefined) {
          exit("The dependency " + firstNoUpToDateTarget + " was not up-to-date")
        } else {
          // EXPERIMENTAL: an empty phony target with complete up-to-date dependency set
          true
        }
      }
    } else {
      if (target.targetFile.isEmpty || !target.targetFile.get.exists) exit("Target file does not exists") else {
        val (phonyPrereqs, filePrereqs) = prerequisites(target).partition(_.phony)
        val phonyNonUpToDateTarget = phonyPrereqs.find(t => !dependenciesWhichWereUpToDateStates.getOrElse(t, false))
        if (phonyNonUpToDateTarget.isDefined) {
          // phony targets can only be considered up-to-date, if they retrieved their up-to-date state themselves while beeing executed
          exit("The phony dependency " + phonyNonUpToDateTarget.get.name + " was not up-to-date")
        } else {
          if (filePrereqs.exists(t => t.targetFile.isEmpty || !t.targetFile.get.exists)) exit("Some prerequisites does not exists") else {

            val fileLastModified = target.targetFile.get.lastModified
            verbose("Target file last modified: " + fileLastModified)

            val prereqsLastModified = filePrereqs.foldLeft(0: Long)((max, goal) => math.max(max, goal.targetFile.get.lastModified))
            verbose("Prereqisites last modified: " + prereqsLastModified)

            if (fileLastModified < prereqsLastModified) exit("Prerequisites are newer") else true
          }
        }
      }
    }
  }

  override def toString: String =
    getClass.getSimpleName + "(" + projectFile + ",targets=" + targets.map { case (f, p) => p.name }.mkString(",") + ")"
}

class ProjectPool(project: Project) {
  private var _projects: Map[File, Project] = Map((project.projectFile -> project))

  def addProject(project: Project) {
    _projects += (project.projectFile -> project)
  }

  def projects: Seq[Project] = _projects.values.toSeq
  def propjectMap: Map[File, Project] = _projects
}