package com.megafarad.play.kafka.module

import com.google.inject.name.Named
import com.google.inject.{AbstractModule, Provides, Singleton}
import com.megafarad.play.kafka.model.KafkaMessage
import com.megafarad.play.kafka.services.{KafkaConsumerService, KafkaConsumerStartupService, KafkaMessageHandlerService, KafkaProducerService, StartupService}
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import net.codingwell.scalaguice.ScalaModule
import org.apache.pekko.actor.ActorSystem
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logging}

import scala.concurrent.{ExecutionContext, Future}

class TestModule extends AbstractModule with ScalaModule with Logging {

  override def configure(): Unit = {
    val messageHandler = mock(classOf[KafkaMessageHandlerService[String, String]])
    when(messageHandler.processMessage(any[KafkaMessage[String, String]]())).thenReturn(Future.successful{
      logger.info("Processed message")
    })
    bind[KafkaMessageHandlerService[String, String]].toInstance(messageHandler)
    bind[MeterRegistry].toInstance(new SimpleMeterRegistry())
    bind[KafkaConsumerStartupService].to[StartupService].asEagerSingleton()
  }

  @Provides
  @Singleton
  @Named("consumer1")
  def provideConsumer1Service(messageHandlerService: KafkaMessageHandlerService[String, String],
                              config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit system: ActorSystem,
                              ec: ExecutionContext): KafkaConsumerService[String, String] = {
    val consumerConfig = config.get[Configuration]("kafka.consumer1")
    new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics, lifecycle)
  }

  @Provides
  @Singleton
  @Named("consumer2")
  def provideConsumer2Service(messageHandlerService: KafkaMessageHandlerService[String, String],
                              config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit system: ActorSystem,
                              ec: ExecutionContext): KafkaConsumerService[String, String] = {
    val consumerConfig = config.get[Configuration]("kafka.consumer2")
    new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics, lifecycle)
  }

  @Provides
  @Singleton
  @Named("consumer3")
  def provideConsumer3Service(messageHandlerService: KafkaMessageHandlerService[String, String],
                              config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit system: ActorSystem,
                              ec: ExecutionContext): KafkaConsumerService[String, String] = {
    val consumerConfig = config.get[Configuration]("kafka.consumer3")
    new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics, lifecycle)
  }

  @Provides
  @Singleton
  @Named("producer1")
  def provideProducer1Service(config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit ec: ExecutionContext): KafkaProducerService[String, String] = {
    val producerConfig = config.get[Configuration]("kafka.producer1")
    new KafkaProducerService[String, String](config, producerConfig, metrics, lifecycle)

  }

  @Provides
  @Singleton
  @Named("producer2")
  def provideProducer2Service(config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit ec: ExecutionContext): KafkaProducerService[String, String] = {
    val producerConfig = config.get[Configuration]("kafka.producer2")
    new KafkaProducerService[String, String](config, producerConfig, metrics, lifecycle)

  }

  @Provides
  @Singleton
  @Named("producer3")
  def provideProducer3Service(config: Configuration,
                              metrics: MeterRegistry,
                              lifecycle: ApplicationLifecycle)
                             (implicit ec: ExecutionContext): KafkaProducerService[String, String] = {
    val producerConfig = config.get[Configuration]("kafka.producer3")
    new KafkaProducerService[String, String](config, producerConfig, metrics, lifecycle)

  }


}
