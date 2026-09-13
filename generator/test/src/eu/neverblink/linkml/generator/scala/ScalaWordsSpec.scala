package eu.neverblink.linkml.generator.scala

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.annotation.nowarn

class ScalaWordsSpec extends AnyWordSpec, Matchers {
  "ScalaWords" should {
    "not compile keywords as class names" in {
      assertDoesNotCompile("class abstract")
      assertDoesNotCompile("class case")
      assertDoesNotCompile("class catch")
      assertDoesNotCompile("class class")
      assertDoesNotCompile("class def")
      assertDoesNotCompile("class do")
      assertDoesNotCompile("class else")
      assertDoesNotCompile("class enum")
      assertDoesNotCompile("class export")
      assertDoesNotCompile("class extends")
      assertDoesNotCompile("class false")
      assertDoesNotCompile("class final")
      assertDoesNotCompile("class finally")
      assertDoesNotCompile("class for")
      assertDoesNotCompile("class given")
      assertDoesNotCompile("class if")
      assertDoesNotCompile("class implicit")
      assertDoesNotCompile("class import")
      assertDoesNotCompile("class lazy")
      assertDoesNotCompile("class match")
      assertDoesNotCompile("class new")
      assertDoesNotCompile("class null")
      assertDoesNotCompile("class object")
      assertDoesNotCompile("class override")
      assertDoesNotCompile("class package")
      assertDoesNotCompile("class private")
      assertDoesNotCompile("class protected")
      assertDoesNotCompile("class return")
      assertDoesNotCompile("class sealed")
      assertDoesNotCompile("class super")
      assertDoesNotCompile("class then")
      assertDoesNotCompile("class this")
      assertDoesNotCompile("class throw")
      assertDoesNotCompile("class trait")
      assertDoesNotCompile("class true")
      assertDoesNotCompile("class try")
      assertDoesNotCompile("class type")
      assertDoesNotCompile("class val")
      assertDoesNotCompile("class var")
      assertDoesNotCompile("class while")
      assertDoesNotCompile("class with")
      assertDoesNotCompile("class yield")
    }
    "compile soft keywords as class names" in {
      @nowarn class as {}
      @nowarn class derives {}
      @nowarn class end {}
      @nowarn class extension {}
      @nowarn class infix {}
      @nowarn class inline {}
      @nowarn class into {}
      @nowarn class opaque {}
      @nowarn class open {}
      @nowarn class transparent {}
      @nowarn class using {}
    }
  }
}
