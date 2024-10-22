package com.megafarad.play.kafka.services

import com.megafarad.play.kafka.model.KafkaMessage

import scala.concurrent.Future

trait KafkaMessageHandlerService[K, V] {

  def processMessage(message: KafkaMessage[K, V]): Future[Unit]

}
