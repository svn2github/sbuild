package de.tototec.sbuild.runner

import java.io.File

import org.scalatest.FunSuite

import de.tototec.sbuild.BuildFileProject
import de.tototec.sbuild.Target
import de.tototec.sbuild.TargetRef.fromString
import de.tototec.sbuild.TargetRefs.fromTarget

class ChainCreatorTest extends FunSuite {

  SBuildRunner.verbose = true
  private implicit val project = new BuildFileProject(new File("SBuild.scala"), null)

  private val g1 = Target("phony:1")
  private val g2 = Target("phony:2") dependsOn g1
  private val g3 = Target("phony:3")
  private val gA = Target("phony:a")
  private val gB = Target("phony:b")
  private val gC = Target("phony:c")

  test("build chain test 1") {
    assert(Seq(g1) === SBuildRunner.preorderedDependenciesForest(Seq(g1)).flatMap(_.linearized).map(_.target))
    assert(Seq(g1) === SBuildRunner.preorderedDependenciesForest(Seq(g1), skipExec = true).flatMap(_.linearized).map(_.target))
  }
  test("build chain test 2") {
    assert(Seq(g1, g2) === SBuildRunner.preorderedDependenciesForest(Seq(g2)).flatMap(_.linearized).map(_.target))
    assert(Seq(g1, g2) === SBuildRunner.preorderedDependenciesForest(Seq(g2), skipExec = true).flatMap(_.linearized).map(_.target))
  }
  test("build chain test 3") {
    assert(Seq(g1, g1, g2) === SBuildRunner.preorderedDependenciesForest(Seq(g1, g2)).flatMap(_.linearized).map(_.target))
    assert(Seq(g1, g1, g2) === SBuildRunner.preorderedDependenciesForest(Seq(g1, g2), skipExec = true).flatMap(_.linearized).map(_.target))
  }
  test("build chain test 4") {
    assert(Seq(g1, g2, g1) === SBuildRunner.preorderedDependenciesForest(Seq(g2, g1)).flatMap(_.linearized).map(_.target))
    assert(Seq(g1, g2, g1) === SBuildRunner.preorderedDependenciesForest(Seq(g2, g1), skipExec = true).flatMap(_.linearized).map(_.target))
  }

}