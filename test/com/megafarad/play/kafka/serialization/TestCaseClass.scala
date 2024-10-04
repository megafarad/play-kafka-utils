package com.megafarad.play.kafka.serialization

import play.api.libs.json._

case class TestCaseClass(data: String, number: Int)

object TestCaseClass {
  implicit val format: OFormat[TestCaseClass] = Json.format[TestCaseClass]
}