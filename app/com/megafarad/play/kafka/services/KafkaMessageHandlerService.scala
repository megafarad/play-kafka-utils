package com.megafarad.play.kafka.services

import com.megafarad.play.kafka.model.KafkaMessage

import scala.concurrent.Future

/**
 * Trait representing a service responsible for processing messages consumed from Kafka.
 *
 * @tparam K the type of the key in the Kafka message
 * @tparam V the type of the value in the Kafka message
 *
 * Implementations of this trait are designed to handle the logic for processing
 * Kafka messages after they are consumed. The messages are passed to the service
 * in a custom `KafkaMessage` facade that decouples the logic from Kafka's internal
 * `ConsumerRecord` class.
 *
 * Each implementing service should provide its own logic to handle messages and
 * return a `Future[Unit]` to represent asynchronous completion of message processing.
 */
trait KafkaMessageHandlerService[K, V] {

  /**
   * Processes a Kafka message.
   *
   * @param message the `KafkaMessage[K, V]` containing the key, value, partition, offset,
   *                headers, and other metadata related to the consumed Kafka message
   * @return a `Future[Unit]` representing the asynchronous processing of the message
   *
   * Implement this method to define how messages should be handled after consumption.
   * The method will be invoked for each message consumed from the configured Kafka topic.
   */
  def processMessage(message: KafkaMessage[K, V]): Future[Unit]

}
