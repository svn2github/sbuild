package de.tototec.sbuild.natures.experimental

import de.tototec.sbuild.Project

/**
 * Base trait containing the project.
 */
trait ProjectConfig {
  implicit def project: Project
}

/**
 * Configuration of an output directory.
 *
 * @see CleanNature
 */
trait OutputDirConfig {
  /**
   * The primary output directory.
   */
  def outputDir: String = "target"
}

trait ClassesDirConfig extends OutputDirConfig {
  def classesDir: String = outputDir + "/classes"
}

trait TestClassesDirConfig extends OutputDirConfig {
  def testClassesDir: String = outputDir + "/test-classes"
}

/**
 * Configures a concrete artifact which has a name and a version.
 */
trait ArtifactConfig {
  def artifact_name: String
  def artifact_version: String
}

/**
 * Configures a artifactGroup, which might be required in some Maven-compatibility situations.
 */
trait ArtifactGroupConfig {
  def artifactGroup: String
}
