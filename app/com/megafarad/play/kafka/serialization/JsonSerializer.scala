package com.megafarad.play.kafka.serialization

import org.apache.kafka.common.serialization.Serializer
import play.api.libs.json._

/**
 * A Play JSON Serializer for Kafka
 *
 * @param format  The Play JSON Format
 * @tparam T      The type of data to serialize
 */
class JsonSerializer[T](implicit format: OFormat[T]) extends Serializer[T] {
  override def serialize(topic: String, data: T): Array[Byte] = {
    if (data == null) null
    else Json.toJson(data).toString().getBytes
  }

  override def close(): Unit = {}
}
