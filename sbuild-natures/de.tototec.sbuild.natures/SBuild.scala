import de.tototec.sbuild._
import de.tototec.sbuild.TargetRefs._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.addons.scala.Scalac

@version("0.4.0")
@classpath("mvn:org.apache.ant:ant:1.9.0")
class SBuild(implicit _project: Project) {

  val scalaVersion = "2.10.1"
  val sbuildVersion = "0.4.0"

  val jar = "target/de.tototec.sbuild.natures-0.0.0.9002.jar"

  val compileCp =
    s"mvn:org.scala-lang:scala-library:${scalaVersion}" ~
      s"http://sbuild.tototec.de/sbuild/attachments/download/58/de.tototec.sbuild-${sbuildVersion}.jar" ~
      s"http://sbuild.tototec.de/sbuild/attachments/download/59/de.tototec.sbuild.addons-${sbuildVersion}.jar" ~
      s"http://sbuild.tototec.de/sbuild/attachments/download/60/de.tototec.sbuild.ant-${sbuildVersion}.jar" ~
      "mvn:org.apache.ant:ant:1.8.4"

  ExportDependencies("eclipse.classpath", compileCp)

  Target("phony:clean").evictCache exec {
    AntDelete(dir = Path("target"))
  }

  Target("phony:all") dependsOn "jar"
  Target("phony:jar") dependsOn jar

  val compilerClasspath =
    s"mvn:org.scala-lang:scala-library:${scalaVersion}" ~
    s"mvn:org.scala-lang:scala-compiler:${scalaVersion}" ~
    s"mvn:org.scala-lang:scala-reflect:${scalaVersion}"


  Target("phony:compile").cacheable dependsOn compileCp ~ "scan:src/main/scala" ~ compilerClasspath exec {
    Scalac(
      compilerClasspath = compilerClasspath.files,
      classpath = compileCp.files,
      sources = "scan:src/main/scala".files,
      destDir = Path("target/classes"),
      debugInfo = "vars"
    )
  }

  Target(jar) dependsOn "compile" exec { ctx: TargetContext =>
    AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/classes"))
  }

//  val nat1 = new CleanNature with CompileScalaNature with JarNature with ScalaSourcesNature {
//    override def artifact_name = "de.tototec.sbuild.natures.experimental"
//    override def artifact_version = sbuildNaturesVersion
//    override def compileScala_compileClasspath = compileCp
//    override def jar_dependsOn = compileScala_targetName
//  }

//  tAll dependsOn nat1.jar_output
//  nat1.createTargets

}
