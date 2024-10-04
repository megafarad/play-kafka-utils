package com.megafarad.play.kafka.serialization

import org.scalatestplus.play.PlaySpec
import play.api.libs.json._

class SerializationTest extends PlaySpec {

  "JsonSerializer" should {
    "correctly serialize a TestCaseClass to JSON" in {
      val testCaseClass = TestCaseClass("Test", 1)
      val serializer = new TestSerializer

      val serialized = serializer.serialize("test topic", testCaseClass)
      val expectedJson = Json.toJson(testCaseClass).toString().getBytes()

      assert(serialized sameElements expectedJson)
    }

    "handle null input" in {
      val serializer = new TestSerializer

      val serialized = serializer.serialize("test topic", null)

      assert(serialized == null)
    }
  }
}
