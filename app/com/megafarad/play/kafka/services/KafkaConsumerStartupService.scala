package com.megafarad.play.kafka.services

trait KafkaConsumerStartupService {

  val services: Seq[KafkaConsumerService[_, _]]

  def startAll(): Unit = services.foreach(_.startPolling())

}
