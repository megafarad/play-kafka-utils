package com.megafarad.play.kafka.services

/**
 * A trait for library users to extend that provides a method to start polling on defined [[KafkaConsumerService]]s
 */
trait KafkaConsumerStartupService {

  val services: Seq[KafkaConsumerService[_, _]]

  def startAll(): Unit = services.foreach(_.startPolling())

}
