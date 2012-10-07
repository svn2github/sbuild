import de.tototec.sbuild._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.TargetRefs._

@version("0.1.1")
@classpath(
  "http://repo1.maven.org/maven2/org/apache/ant/ant/1.8.3/ant-1.8.3.jar",
  "http://repo1.maven.org/maven2/org/apache/ant/ant-launcher/1.8.3/ant-launcher-1.8.3.jar",
  "http://repo1.maven.org/maven2/junit/junit/4.10/junit-4.10.jar",
  "http://repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.9.2/scala-compiler-2.9.2.jar"
)
class SBuild(implicit project: Project) {

  SchemeHandler("http", new HttpSchemeHandler(Path(".sbuild/http")))
  SchemeHandler("mvn", new MvnSchemeHandler(Path(Prop("mvn.repo", ".sbuild/mvn"))))

  val version = Prop("SBUILD_VERSION", "svn")
  val osgiVersion = Prop("SBUILD_OSGI_VERSION", "svn")
  val jar = "target/de.tototec.sbuild.jar"

  val scalaVersion = "2.9.2"
  val compileCp =
    ("mvn:org.scala-lang:scala-library:" + scalaVersion) ~
      "http://cmdoption.tototec.de/cmdoption/attachments/download/3/de.tototec.cmdoption-0.1.0.jar"

  val testCp = compileCp ~
    ("mvn: org.scalatest:scalatest_" + scalaVersion + ":1.6.1")

  ExportDependencies("eclipse.classpath", testCp)

  Target("phony:all") dependsOn jar ~ "test"

  val versionScalaFile = "src/main/scala/de/tototec/sbuild/SBuildVersion.scala"

  Target("phony:clean") exec {
    AntDelete(dir = Path("target"))
    AntDelete(file = Path(versionScalaFile))
  }

  Target(versionScalaFile) dependsOn project.projectFile exec { ctx: TargetContext =>
    AntEcho(message = "Generating versions file for version: " + version + " / " + osgiVersion)
    AntEcho(file = ctx.targetFile.get, message = """// Generated by SBuild from file SBuild.scala
package de.tototec.sbuild

object SBuildVersion {
  def version = """" + version + """"
  def osgiVersion = """" + osgiVersion + """" 
}
""")
  }

  def validateGeneratedVersions(deps: Seq[java.io.File]) {
    // validate versions
    val cl = new java.net.URLClassLoader(
      (Seq(Path("target/classes").toURI.toURL) ++ deps.map(_.toURI.toURL)).toArray,
      null)
    val versionClass = cl.loadClass("de.tototec.sbuild.SBuildVersion")
    val readVersion = versionClass.getMethod("version").invoke(null)
    val readOsgiVersion = versionClass.getMethod("osgiVersion").invoke(null)
    if (readVersion != version || readOsgiVersion != osgiVersion) {
      throw new Exception("Versions in SBuildVersion class do not match current values! read version: " +
        readVersion + ", read osgiVersion: " + readOsgiVersion)
    } else {
      AntEcho(message = "Versions match")
    }
  }

  def antScalac = new scala_tools_ant.AntScalac(
    target = "jvm-1.5",
    encoding = "UTF-8",
    deprecation = "on",
    unchecked = "on",
    debugInfo = "vars",
    // this is necessary, because the scala ant tasks outsmarts itself 
    // when more than one scala class is defined in the same .scala file
    force = true)

  Target("phony:compile") dependsOn (compileCp ~ versionScalaFile) exec { ctx: TargetContext =>
    val input = "src/main/scala"
    val output = "target/classes"
    AntMkdir(dir = Path(output))
    IfNotUpToDate(srcDir = Path(input), stateDir = Path("target"), ctx = ctx) {
      val scalac = antScalac
      scalac.setSrcDir(AntPath(input))
      scalac.setDestDir(Path(output))
      scalac.setClasspath(AntPath(compileCp))
      scalac.execute
    }

    validateGeneratedVersions(ctx.fileDependencies)
  }

  Target("phony:copyResources") exec {
    val resources = Path("src/main/resources")
    if (resources.exists) {
      new AntCopy(toDir = Path("target/classes")) {
        add(AntPath(resources))
      }.execute
    }
  }

  Target(jar) dependsOn ("compile" ~ "copyResources") exec { ctx: TargetContext =>
    val jarTask = new AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/classes"))
    jarTask.addFileset(AntFileSet(dir = Path("."), includes = "LICENSE.txt"))
    jarTask.execute
  }

  Target("target/sources.jar") exec { ctx: TargetContext =>
    AntCopy(file = Path("src/main/scala"), toDir = Path("target/sources/src/main/scala"))
    AntCopy(file = Path("src/main/resources"), toDir = Path("target/sources/src/main/resources"))
    AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/sources"))
  }

  Target("phony:scaladoc") dependsOn compileCp exec { ctx: TargetContext =>
    AntMkdir(dir = Path("target/scaladoc"))
    scala_tools_ant.AntScaladoc(
      deprecation = "on",
      unchecked = "on",
      classpath = AntPath(locations = ctx.fileDependencies),
      srcDir = AntPath(path = "src/main/scala"),
      destDir = Path("target/scaladoc")
    )
  }

  Target("phony:testCompile") dependsOn (testCp ~ jar) exec { ctx: TargetContext =>
    val input = "src/test/scala"
    val output = Path("target/test-classes")
    AntMkdir(dir = output)
    IfNotUpToDate(Path(input), Path("target"), ctx) {
      val scalac = antScalac
      scalac.setSrcDir(AntPath(path = input))
      scalac.setDestDir(output)
      scalac.setClasspath(AntPath(locations = ctx.fileDependencies))
      scalac.execute
    }
  }

  Target("phony:test") dependsOn (testCp ~ jar ~ "testCompile") exec { ctx:TargetContext =>
// This will require SBuild 0.1.3 because 0.1.2 included itself into classpath to early, which prevents testing itself with changed API.
//    new de.tototec.sbuild.addons.scalatest.ScalaTest(
//      classpath = ctx.fileDependencies,
//      runPath = Seq("target/test-classes"),
//      reporter = "oF").execute

    // scala [-classpath scalatest-<version>.jar:...] org.scalatest.tools.Runner 
    // [-D<key>=<value> [...]] [-p <runpath>] [reporter [...]] 
    // [-n <includes>] [-l <excludes>] [-c] [-s <suite class name> 
    // [...]] [-j <junit class name> [...]] [-m <members-only suite path> 
    // [...]] [-w <wildcard suite path> [...]] [-t <TestNG config file 
    // path> [...]]
    new AntJava(
      fork = true,
      className = "org.scalatest.tools.Runner",
      classpath = AntPath(locations = ctx.fileDependencies),
      args = "-oF -p target/test-classes"
    ) {
      setFailonerror(true)
    }.execute

  }

}
