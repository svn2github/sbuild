package de.tototec.sbuild

import java.io.File

import de.tototec.sbuild.internal.WithinTargetExecution

object TargetRef {

  implicit def fromTarget(target: Target): TargetRef = TargetRef(target)
  implicit def fromString(name: String)(implicit project: Project): TargetRef = TargetRef(name)
  implicit def fromFile(file: File)(implicit project: Project): TargetRef = TargetRef(file)

  def apply(name: String)(implicit project: Project): TargetRef = new TargetRef(name)
  def apply(target: Target): TargetRef = new TargetRef(target.name)(target.project)
  def apply(file: File)(implicit project: Project): TargetRef = new TargetRef("file:" + file.getPath)

}

class TargetRef(val ref: String)(implicit project: Project) {

  private[this] def log = Logger[TargetRef]

  val (explicitProject: Option[File], name: String) = ref.split("::", 2) match {
    case Array(p, n) => (Some(Path(p)), n)
    case Array(n) => (None, n)
  }

  val explicitProto: Option[String] = name.split(":", 2) match {
    case Array(proto, name) => Some(proto)
    case Array(name) => None
  }

  def explicitNonStandardProto: Option[String] = explicitProto match {
    case Some(p) if p != "file" && p != "phony" => Some(p)
    case _ => None
  }

  val nameWithoutProto = name.split(":", 2) match {
    case Array(_, name) => name
    case _ => name
  }

  def nameWithoutStandardProto = name.split(":", 2) match {
    case Array(p, name) if p == "phony" || p == "file" => name
    case Array(_, _) => name
    case _ => name
  }

  override def toString = ref

  protected[sbuild] def targetProject: Option[Project] =
    if (explicitProject == None || project.projectFile == explicitProject.get)
      Some(project)
    else
      project.findModule(explicitProject.get.getName)

  protected[sbuild] def safeTargetProject: Project =
    targetProject match {
      case Some(p) => p
      case _ =>
        val e = new TargetNotFoundException(s"""Project "${explicitProject.get}" not found.""")
        e.buildScript = Some(project.projectFile)
        throw e
    }

  /**
   * Get the files, this TargetRef is referencing or producing, if any.
   * Should only call from inside an execution block of a target.
   */
  def files: Seq[File] = {
    WithinTargetExecution.safeWithinTargetExecution("TargetRef.files", Some(project)) {
      withinTargetExec =>
        // Find the TargetContext of the already executed dependency, that matches this TargetRef

        // as all dependencies were already executed,
        // they all should have an associated Target instance, which we can search for.
        // So, we must find a target for the used TargetRef. 
        // When not, the used TargetRef was not part of the dependencies

        project.findTarget(this, searchInAllProjects = true, includeImplicit = true) match {
          case None =>
            // No target found, so this TargetRef can not be part of the dependencies 
            val ex = ProjectConfigurationException.localized("'TargetRef.files' is used for dependency \"{0}\", that is not declared with 'dependsOn'.", this.toString)
            ex.buildScript = Some(project.projectFile)
            ex.targetName = Some(withinTargetExec.targetContext.name)
            throw ex

          case Some(referencedTarget) =>
            // search the associated TargetContext for that target

            withinTargetExec.directDepsTargetContexts.find { ctx => ctx.target == referencedTarget } match {
              case None =>
                // No target context found, so this TargetRef can not be part of the dependencies 
                log.debug("referencedTarget = " + referencedTarget + "\ndirectDepsTargetContexts = " + withinTargetExec.directDepsTargetContexts.mkString(",\n  "))
                val ex = ProjectConfigurationException.localized("'TargetRef.files' is used for dependency \"{0}\", that is not declared with 'dependsOn'.", this.toString)
                ex.buildScript = Some(project.projectFile)
                ex.targetName = Some(withinTargetExec.targetContext.name)
                throw ex

              case Some(foundDepCtx) =>
                foundDepCtx.targetFiles
            }
        }
    }
  }

  @deprecated("When this kind of utility function is required, RichFile should provide it.", "0.6.0.9002")
  def filesRelativeTo(baseDir: File): Seq[String] = {
    val baseUri = baseDir.toURI()
    files.map { f =>
      val absFile = if (f.isAbsolute) f else new File(project.projectDirectory, f.getPath)
      baseUri.relativize(absFile.toURI).getPath
    }
  }

}

