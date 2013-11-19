import de.tototec.sbuild._

@version("0.6.0.9003")
@classpath("target/de.tototec.sbuild.addons.aether-plugin-0.6.0.9003.jar")
class Test(implicit _project: Project) {

  Plugin[de.tototec.sbuild.addons.aether.Aether]("aether")
  Plugin[de.tototec.sbuild.addons.aether.Aether]("aetherContainer")

  val dep = "aether:org.testng:testng:6.8"

  Target("phony:test") dependsOn "aether:org.testng:testng:6.8" exec {
    println("Files: " + dep.files)
  }

}
