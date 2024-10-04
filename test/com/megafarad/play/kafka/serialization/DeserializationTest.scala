package com.megafarad.play.kafka.serialization

import org.scalatestplus.play.PlaySpec
import play.api.libs.json.Json

class DeserializationTest extends PlaySpec {

  "JsonDeserializer" should {
    "correctly deserialize JSON to TestCaseClass" in {
      val testCaseClass = TestCaseClass("Test String", 10)
      val deserializer = new TestDeserializer
      val jsonBytes = Json.toJson(testCaseClass).toString().getBytes

      val deserialized = deserializer.deserialize("test topic", jsonBytes)

      assert(deserialized == testCaseClass)
    }

    "handle null input" in {
      val deserializer = new TestDeserializer

      val deserialized = deserializer.deserialize("test topic", null)

      assert(deserialized == null)
    }
  }
}
