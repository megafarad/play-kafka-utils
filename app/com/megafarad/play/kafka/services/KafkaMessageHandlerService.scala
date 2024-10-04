package com.megafarad.play.kafka.services

import scala.concurrent.Future

trait KafkaMessageHandlerService[K, V] {

  def processMessage(key: K, value: V): Future[Unit]

}
