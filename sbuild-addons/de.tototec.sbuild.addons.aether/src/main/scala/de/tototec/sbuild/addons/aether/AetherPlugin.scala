package de.tototec.sbuild.addons.aether

import de.tototec.sbuild.Plugin
import de.tototec.sbuild.Project
import de.tototec.sbuild.SchemeHandler
import de.tototec.sbuild.addons.aether.AetherSchemeHandler.Repository

class Aether() {
  var remoteRepos: Seq[Repository] = Seq()
  var schemeName: String = "aether"

  def config(remoteRepos: Seq[Repository] = remoteRepos,
             schemeName: String = schemeName): Aether = {
    this.remoteRepos = remoteRepos
    this.schemeName = schemeName
    this
  }

}

class AetherPlugin(implicit project: Project) extends Plugin[Aether] {
  def applyToProject(instances: Seq[(String, Aether)]): Unit = {
    instances.map {
      case (name, pluginContext) =>
        SchemeHandler(pluginContext.schemeName, new AetherSchemeHandler(remoteRepos = pluginContext.remoteRepos))
    }
  }
  def create(name: String): Aether = new Aether().config(schemeName = if (name == "") "aether" else name)
  def instanceType: Class[Aether] = classOf[Aether]
}

case class AetherDeps(deps: Seq[String], excludes: Seq[String])

class AetherContainer() {
  var remoteRepos: Seq[Repository] = Seq()
  var schemeName: String = "aether"

  def config(remoteRepos: Seq[Repository] = remoteRepos,
             schemeName: String = schemeName): AetherContainer = {
    this.remoteRepos = remoteRepos
    this.schemeName = schemeName
    this
  }

}

class AetherContainerPlugin(implicit project: Project) extends Plugin[AetherContainer] {
  def applyToProject(instances: Seq[(String, AetherContainer)]): Unit = {
    instances.map {
      case (name, pluginContext) =>
        SchemeHandler(pluginContext.schemeName, new AetherSchemeHandler(remoteRepos = pluginContext.remoteRepos))
    }
  }
  def create(name: String): AetherContainer = new AetherContainer().config(schemeName = if (name == "") "aetherContainer" else name)
  def instanceType: Class[AetherContainer] = classOf[AetherContainer]
}

