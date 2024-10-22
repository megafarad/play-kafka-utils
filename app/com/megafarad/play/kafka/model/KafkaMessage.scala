package com.megafarad.play.kafka.model

import org.apache.kafka.clients.consumer.ConsumerRecord
import scala.jdk.CollectionConverters._

case class KafkaMessage[K, V](key: K,
                              value: V,
                              topic: String,
                              partition: Int,
                              offset: Long,
                              headers: Map[String, Array[Byte]],
                              timestamp: Long)
object KafkaMessage {
  def ofConsumerRecord[K, V](record: ConsumerRecord[K, V]): KafkaMessage[K, V] = {
    KafkaMessage(
      key = record.key(),
      value = record.value(),
      topic = record.topic(),
      partition = record.partition(),
      offset = record.offset(),
      headers = record.headers().iterator().asScala.map(h => h.key() -> h.value()).toMap,
      timestamp = record.timestamp()
    )
  }
}

