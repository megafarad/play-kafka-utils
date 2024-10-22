package com.megafarad.play.kafka.serialization
import org.apache.kafka.common.serialization.Deserializer
import play.api.libs.json._

/**
 * A Play JSON Deserializer for Kafka
 *
 * @param format The Play JSON Format
 * @tparam T  The type to deserialize to
 */
class JsonDeserializer[T](implicit format: OFormat[T]) extends Deserializer[T] {
  override def deserialize(topic: String, data: Array[Byte]): T = {
    if (data == null) null.asInstanceOf[T]
    else {
      val jsonString = new String(data)
      Json.parse(jsonString).as[T]
    }
  }

  override def close(): Unit = {}
}
