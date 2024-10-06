package com.megafarad.play.kafka.services

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.ConfigFactory
import io.github.embeddedkafka.EmbeddedKafka
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.inject.DefaultApplicationLifecycle
import play.api.{Configuration, Logging}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class KafkaConsumerServiceSpec extends AnyWordSpec with BeforeAndAfterAll with MockitoSugar with Logging
  with ScalaFutures with Eventually with EmbeddedKafka {

  implicit val actorSystem: ActorSystem = ActorSystem("TestActorSystem")
  implicit val ec: ExecutionContext = actorSystem.dispatcher

  //Common arrangements
  val testKey = "test.key"
  val testValue = "test.value"
  val config: Configuration = Configuration(ConfigFactory.parseResources("reference.conf"))
  val consumerConfig: Configuration = config.get[Configuration]("kafka.consumer1")
  val metrics = new MetricRegistry()
  val applicationLifecycle = new DefaultApplicationLifecycle()
  implicit val serializer: StringSerializer = new StringSerializer()
  implicit val deserializer: StringDeserializer = new StringDeserializer()


  "KafkaConsumerService" should {
    "Poll for records" in {

      withRunningKafka {
        //Arrange
        val messageHandlerService = mock[KafkaMessageHandlerService[String, String]]
        Mockito.when(messageHandlerService.processMessage(testKey, testValue)).thenReturn(Future.successful(()))
        EmbeddedKafka.withProducer[String, String, Unit] {
          producer => producer.send(new ProducerRecord[String, String]("topic1", testKey, testValue))
        }

        //Act - the mere act of instantiating the class should start polling.
        val service = new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics,
          applicationLifecycle)

        //Assert
        //TODO: I do not like this - at all. But "eventually" keeps failing with the error:
        // Last failure message: Cannot invoke "scala.concurrent.Future.recoverWith(scala.PartialFunction,
        // scala.concurrent.ExecutionContext)" because the return value of "scala.Function0.apply()" is null.
        Thread.sleep(20000)

        Mockito.verify(messageHandlerService, Mockito.times(1)).processMessage(testKey, testValue)

        //Cleanup
        service.stopPolling()

        applicationLifecycle.stop().futureValue
      }

    }

    "Send failed records processing to the dead letter topic" in {
      withRunningKafka {
        //Arrange
        val messageHandlerService = mock[KafkaMessageHandlerService[String, String]]
        Mockito.when(messageHandlerService.processMessage(testKey, testValue))
          .thenReturn(Future.failed(new RuntimeException("Failed to process message")))
        EmbeddedKafka.withProducer[String, String, Unit] {
          producer => producer.send(new ProducerRecord[String, String]("topic1", testKey, testValue))
        }

        //Act
        val service = new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics,
          applicationLifecycle)

        //Assert
        EmbeddedKafka.withConsumer[String, String, org.scalatest.Assertion] {
          consumer =>
            consumer.subscribe(Set("dead-letter-topic1").asJava)
            eventually(timeout(1.minute)) {
              assert {
                val records = consumer.poll(java.time.Duration.ofMillis(100))
                logger.info(s"Records: ${records.asScala}")
                records.count() == 1
              }
            }
        }

        service.stopPolling()
        applicationLifecycle.stop().futureValue
      }

    }
  }

}
