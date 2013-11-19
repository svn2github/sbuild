package de.tototec.sbuild.addons.aether

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL

import scala.collection.JavaConverters.enumerationAsScalaIteratorConverter

import de.tototec.sbuild.Logger
import de.tototec.sbuild.Project
import de.tototec.sbuild.Util

private object ClasspathUtil extends ClasspathUtil

private class ClasspathUtil {
  private[this] val log = Logger[ClasspathUtil]

  def extractResourceToFile(classLoader: ClassLoader, resource: String, allElements: Boolean, deleteOnVmExit: Boolean, project: Project): Seq[File] = {

    val resources = classLoader.getResources(resource)

    def save(url: URL): File = {

      val fileName = new File(url.getPath()).getName()

      val resStream = url.openStream()

      val tmpFile = File.createTempFile("$$$", fileName)
      if (deleteOnVmExit) tmpFile.deleteOnExit
      val outStream = new BufferedOutputStream(new FileOutputStream(tmpFile))

      try {
        log.debug("About to extract classpath resource info file: " + tmpFile)
        Util.copy(resStream, outStream)

      } finally {
        outStream.close
        resStream.close
      }

      tmpFile
    }

    log.debug(s"About to find ${if (allElements) "all" else "the first"} matching classpath resources: ${resource}")
    if (allElements)
      resources.asScala.toSeq.map(save(_))
    else if (resources.hasMoreElements)
      Seq(save(resources.nextElement))
    else Seq()
  }

}