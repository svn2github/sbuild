package de.tototec.sbuild.natures.experimental

import de.tototec.sbuild.ProjectConfigurationException
import de.tototec.sbuild.TargetRefs
import de.tototec.sbuild.TargetRefs._
import de.tototec.sbuild.Pathes
import de.tototec.sbuild.Target
import de.tototec.sbuild.TargetContext
import de.tototec.sbuild.IfNotUpToDate
import de.tototec.sbuild.Path
import de.tototec.sbuild.ant._
import de.tototec.sbuild.ant.tasks._
import de.tototec.sbuild.addons.java.Javac
import de.tototec.sbuild.TargetRef

trait JavaSourcesConfig extends ProjectConfig {
  def javaSources_sources: Seq[String] = Seq("src/main/java")
  def javaSources_encoding: String = "UTF-8"
  def javaSources_source: Option[String] = None
}

trait JavaCompileConfig extends ClassesDirConfig with JavaSourcesConfig with OutputDirConfig with ProjectConfig {
  def javaCompile_targetName: String = "compileJava"
  def javaCompile_sources: TargetRefs = javaSources_sources.map { dir => TargetRef("scan:" + dir) }
  def javaCompile_classpath: TargetRefs = "compileCp"
  def javaCompile_extraDependsOn: TargetRefs = TargetRefs()
  def javaCompile_target: String
  def javaCompile_targetIsCacheable: Boolean = true
}

trait Java5CompilerConfig extends JavaCompileConfig with JavaSourcesConfig {
  override def javaSources_source = Some("1.5")
  override def javaCompile_target = "1.5"
}

trait Java6CompilerConfig extends JavaCompileConfig with JavaSourcesConfig {
  override def javaSources_source = Some("1.6")
  override def javaCompile_target = "1.6"
}

trait Java7CompilerConfig extends JavaCompileConfig with JavaSourcesConfig {
  override def javaSources_source = Some("1.7")
  override def javaCompile_target = "1.7"
}

trait JavaCompileNature extends Nature { this: JavaCompileConfig =>
  def compileJava_outputDir: String = classesDir

  abstract override def createTargets: Seq[Target] = {

    val compile = Target("phony:" + javaCompile_targetName) dependsOn
      javaCompile_extraDependsOn ~
      javaCompile_classpath ~
      javaCompile_sources exec {

        Javac(
          classpath = javaCompile_classpath.files,
          sources = javaCompile_sources.files,
          destDir = Path(classesDir),
          source = javaSources_source.getOrElse(null),
          target = javaCompile_target,
          encoding = javaSources_encoding
        )
      }

    if (javaCompile_targetIsCacheable) compile.cacheable

    super.createTargets ++ Seq(compile)
  }
}

