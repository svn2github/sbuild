import de.tototec.sbuild._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.TargetRefs._

@version("0.4.0")
@include("../SBuildConfig.scala")
@classpath("mvn:org.apache.ant:ant:1.8.4") 
class SBuild(implicit _project: Project) {

  val jar = s"target/de.tototec.sbuild-${SBuildConfig.sbuildVersion}.jar"
  val sourcesZip = s"target/de.tototec.sbuild-${SBuildConfig.sbuildVersion}-sources.jar"

  val compileCp =
    s"mvn:org.scala-lang:scala-library:${SBuildConfig.scalaVersion}" ~
      "http://repo.fusesource.com/nexus/content/groups/public/org/fusesource/jansi/jansi/1.9/jansi-1.9.jar" ~
      SBuildConfig.cmdOptionSource

  val testCp = compileCp ~
      s"mvn:org.scalatest:scalatest_${SBuildConfig.scalaBinVersion}:1.9.1" ~
      s"mvn:org.scala-lang:scala-actors:${SBuildConfig.scalaVersion}"

  ExportDependencies("eclipse.classpath", testCp)

  Target("phony:all") dependsOn jar ~ sourcesZip ~ "test"

  val versionScalaFile = "target/generated-scala/scala/de/tototec/sbuild/SBuildVersion.scala"

  Target("phony:clean").evictCache exec {
    AntDelete(dir = Path("target"))
  }

  Target(versionScalaFile) dependsOn "../SBuildConfig.scala" exec { ctx: TargetContext =>
    AntMkdir(dir = ctx.targetFile.get.getParentFile)
    AntEcho(message = s"Generating versions file for version: ${SBuildConfig.sbuildVersion} / ${SBuildConfig.sbuildOsgiVersion}")
    AntEcho(file = ctx.targetFile.get, message = s"""// Generated by SBuild from file SBuild.scala
package de.tototec.sbuild

object SBuildVersion {
  def version = "${SBuildConfig.sbuildVersion}"
  def osgiVersion = "${SBuildConfig.sbuildOsgiVersion}" 
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
    if (readVersion != SBuildConfig.sbuildVersion || readOsgiVersion != SBuildConfig.sbuildOsgiVersion) {
      throw new Exception(s"Versions in SBuildVersion class do not match current values! read version: ${readVersion} , read osgiVersion: ${readOsgiVersion}")
    } else {
      AntEcho(message = "Versions match")
    }
  }

  Target("phony:compile").cacheable dependsOn SBuildConfig.compilerPath ~ compileCp ~ versionScalaFile ~
      "scan:src/main/scala" ~ "scan:src/main/java" exec {

    val output = "target/classes"

    // compile scala files
    addons.scala.Scalac(
      compilerClasspath = SBuildConfig.compilerPath.files,
      classpath = compileCp.files,
      sources = "scan:src/main/scala".files ++ "scan:src/main/java".files ++ versionScalaFile.files,
      destDir = Path(output),
      unchecked = true, deprecation = true, debugInfo = "vars"
    )

    // compile java files
    addons.java.Javac(
      sources = "scan:src/main/java".files,
      destDir = Path(output),
      classpath = compileCp.files,
      source = "1.6",
      target = "1.6",
      debugInfo = "all"
    )
  }

  Target(jar) dependsOn "compile" ~ "scan:src/main/resources" ~ "LICENSE.txt" exec { ctx: TargetContext =>
    new AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/classes")) {
      if (Path("src/main/resources").exists) add(AntFileSet(dir = Path("src/main/resources")))
      add(AntFileSet(file = Path("LICENSE.txt")))
    }.execute
  }

  Target(sourcesZip) dependsOn versionScalaFile ~ "scan:src/main" ~ "scan:target/generated-scala" ~ "scan:LICENSE.txt" exec { ctx: TargetContext =>
    AntZip(destFile = ctx.targetFile.get, fileSets = Seq(
      AntFileSet(dir = Path("src/main/scala")),
      AntFileSet(dir = Path("src/main/java")),
      AntFileSet(dir = Path("src/main/resources")),
      AntFileSet(dir = Path("target/generated-scala")),
      AntFileSet(file = Path("LICENSE.txt"))
    ))
  }

  Target("phony:testCompile").cacheable dependsOn SBuildConfig.compilerPath ~ testCp ~ jar ~ "scan:src/test/scala" exec {
    addons.scala.Scalac(
      compilerClasspath = SBuildConfig.compilerPath.files,
      classpath = testCp.files ++ jar.files,
      sources = "scan:src/test/scala".files,
      destDir = Path("target/test-classes"),
      deprecation = true, unchecked = true, debugInfo = "vars"
    )
  }

  Target("phony:test") dependsOn testCp ~ jar ~ "testCompile" exec {
    addons.scalatest.ScalaTest(
      classpath = testCp.files ++ jar.files,
      runPath = Seq("target/test-classes"),
      reporter = "oF",
      fork = true)
  }

  Target("phony:scaladoc").cacheable dependsOn SBuildConfig.compilerPath ~ compileCp ~ "scan:src/main/scala" ~ versionScalaFile exec {
    addons.scala.Scaladoc(
      scaladocClasspath = SBuildConfig.compilerPath.files,
      classpath = compileCp.files,
      sources = "scan:src/main/scala".files ++ versionScalaFile.files,
      destDir = Path("target/scaladoc"),
      deprecation = true, unchecked = true, implicits = true,
      docVersion = SBuildConfig.sbuildVersion,
      docTitle = s"SBuild API Reference"
    )
  }

}
