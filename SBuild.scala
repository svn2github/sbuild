import de.tototec.sbuild._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.TargetRefs._

@version("0.1.4")
class SBuild(implicit project: Project) {

  val modules = Modules("sbuild", "sbuild-eclipse-plugin")

  Target("phony:clean") dependsOn modules.map(m => m("clean"))
  Target("phony:all") dependsOn modules.map(m => m("all"))

}
