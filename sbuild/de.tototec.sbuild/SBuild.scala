import de.tototec.sbuild._
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.TargetRefs._

@version("0.4.0")
@include("../SBuildConfig.scala")
@classpath("mvn:org.apache.ant:ant:1.8.4")
class SBuild(implicit _project: Project) {

  val namespace = "de.tototec.sbuild"
  val jar = s"target/${namespace}-${SBuildConfig.sbuildVersion}.jar"
  val sourcesZip = s"target/${namespace}-${SBuildConfig.sbuildVersion}-sources.jar"

  val compileCp =
    SBuildConfig.scalaLibrary ~
      SBuildConfig.jansi ~
      SBuildConfig.slf4jApi // optional

  val testCp = compileCp ~
    s"mvn:org.scalatest:scalatest_${SBuildConfig.scalaBinVersion}:2.0.RC2"

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
package ${namespace}

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
    val versionClass = cl.loadClass(s"${namespace}.SBuildVersion")
    val readVersion = versionClass.getMethod("version").invoke(null)
    val readOsgiVersion = versionClass.getMethod("osgiVersion").invoke(null)
    if (readVersion != SBuildConfig.sbuildVersion || readOsgiVersion != SBuildConfig.sbuildOsgiVersion) {
      throw new Exception(s"Versions in SBuildVersion class do not match current values! read version: ${readVersion} , read osgiVersion: ${readOsgiVersion}")
    } else {
      AntEcho(message = "Versions match")
    }
  }

  Target("phony:compile").cacheable dependsOn SBuildConfig.compilerPath ~ compileCp ~ versionScalaFile ~
    "scan:src/main/scala;regex=.+\\.scala" ~ "scan:src/main/java;regex=.+\\.java" exec {

      val output = "target/classes"

      // compile scala files
      addons.scala.Scalac(
        compilerClasspath = SBuildConfig.compilerPath.files,
        classpath = compileCp.files,
        sources = "scan:src/main/scala;regex=.+\\.scala".files ++ "scan:src/main/java;regex=.+\\.java".files ++ versionScalaFile.files,
        destDir = Path(output),
        unchecked = true, deprecation = true, debugInfo = "vars"
      )

      // compile java files
      addons.java.Javac(
        sources = "scan:src/main/java;regex=.+\\.java".files,
        destDir = Path(output),
        classpath = compileCp.files,
        source = "1.6",
        target = "1.6",
        debugInfo = "all"
      )
    }

  Target(jar) dependsOn "compile" ~ "compile-messages" ~ "scan:src/main/resources" ~ "LICENSE.txt" exec { ctx: TargetContext =>
    new AntJar(destFile = ctx.targetFile.get, baseDir = Path("target/classes"),
      manifestEntries = Map("I18n-Catalog" -> "de.tototec.sbuild.internal.Messages")
    ) {
      if (Path("src/main/resources").exists) add(AntFileSet(dir = Path("src/main/resources")))
      if (Path("target/po-classes").exists) add(AntFileSet(dir = Path("target/po-classes")))
      add(AntFileSet(file = Path("LICENSE.txt")))
    }.execute
  }

  Target(sourcesZip) dependsOn versionScalaFile ~ "scan:src/main" ~ "scan:target/generated-scala" ~ "scan:LICENSE.txt" exec { ctx: TargetContext =>
    AntZip(destFile = ctx.targetFile.get, fileSets = Seq(
      AntFileSet(dir = Path("src/main/scala")),
      AntFileSet(dir = Path("src/main/java")),
      AntFileSet(dir = Path("src/main/po")),
      // AntFileSet(dir = Path("src/main/resources")),
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
    //    addons.scalatest.ScalaTest(
    //      classpath = testCp.files ++ jar.files,
    //      runPath = Seq("target/test-classes"),
    //      reporter = "oF",
    //      // FIXME: reenable, when we can use >= SBuild 0.6.0.9001
    //      //      standardOutputSettings = "FD",
    //      //      xmlOutputDir = Path("target/test-output"),
    //      fork = true)

    addons.support.ForkSupport.runJavaAndWait(
      classpath = testCp.files ++ jar.files,
      arguments = Array("org.scalatest.tools.Runner", "-p", Path("target/test-classes").getPath, "-oF", "-u", Path("target/test-output").getPath)
    )
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

  val scalaStyleCp =
    s"mvn:org.scalastyle:scalastyle_${SBuildConfig.scalaBinVersion}:0.3.2" ~
      SBuildConfig.scalaLibrary ~
      s"mvn:org.scalariform:scalariform_${SBuildConfig.scalaBinVersion}:0.1.4" ~
      s"mvn:com.github.scopt:scopt_${SBuildConfig.scalaBinVersion}:2.1.0"

  val scalaStyleConf = "http://www.scalastyle.org/scalastyle_config.xml"

  Target("phony:scalaStyle") dependsOn "scan:source/main/scala" ~ scalaStyleConf ~ scalaStyleCp exec {
    addons.support.ForkSupport.runJavaAndWait(
      classpath = scalaStyleCp.files,
      arguments = Array("org.scalastyle.Main", "--config", scalaStyleConf.files.head.getPath, Path("src/main/scala").getPath)
    )
  }

  val i18n = new I18n()
  i18n.targetCatalogDir = Path("target/po-classes/de/tototec/sbuild/internal")
  i18n.applyAll

}
