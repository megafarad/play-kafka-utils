package com.megafarad.play.kafka.services

import javax.inject.{Inject, Named}

class StartupService @Inject()(@Named("consumer1") consumerService1: KafkaConsumerService[String, String],
                               @Named("consumer2") consumerService2: KafkaConsumerService[String, String],
                               @Named("consumer3") consumerService3: KafkaConsumerService[String, String])
  extends KafkaConsumerStartupService {

  val services: Seq[KafkaConsumerService[_, _]] = Seq(consumerService1, consumerService2, consumerService3)

  startAll()
}
