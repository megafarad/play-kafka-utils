package com.megafarad.play.kafka.services

import com.typesafe.config.ConfigFactory
import io.github.embeddedkafka.EmbeddedKafka
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.inject.DefaultApplicationLifecycle

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class KafkaProducerServiceSpec extends AnyWordSpec with EmbeddedKafka with ScalaFutures with Eventually {

  //Common arrangements
  val testKey = "test.key"
  val testValue = "test.value"
  val topic = "topic1"
  val config: Configuration = Configuration(ConfigFactory.parseResources("reference.conf"))
  val producerConfig: Configuration = config.get[Configuration]("kafka.producer1")
  val metrics = new SimpleMeterRegistry()
  val applicationLifecycle = new DefaultApplicationLifecycle()
  implicit val deserializer: StringDeserializer = new StringDeserializer()

  "KafkaProducerService" should {
    "Send records synchronously to Kafka with sendSync" in {

      withRunningKafka {
        //Arrange
        val producerService = new KafkaProducerService[String, String](config, producerConfig, metrics,
          applicationLifecycle)

        //Act
        producerService.sendSync(topic, testKey, testValue)

        //Assert
        EmbeddedKafka.withConsumer[String, String, org.scalatest.Assertion] {
          consumer =>
            consumer.subscribe(Set(topic).asJava)
            eventually(timeout(30.seconds)) {
              val records = consumer.poll(java.time.Duration.ofMillis(100))
              assert(records.count() == 1)
            }
        }

        //Cleanup
        applicationLifecycle.stop().futureValue
      }
    }

    "Send records asynchronously to Kafka with sendAsync" in {
      withRunningKafka {
        //Arrange
        val producerService = new KafkaProducerService[String, String](config, producerConfig, metrics,
          applicationLifecycle)

        //Act
        producerService.sendAsync(topic, testKey, testValue)

        //Assert
        EmbeddedKafka.withConsumer[String, String, org.scalatest.Assertion] {
          consumer =>
            consumer.subscribe(Set(topic).asJava)
            eventually(timeout(30.seconds)) {
              val records = consumer.poll(java.time.Duration.ofMillis(100))
              assert(records.count() == 1)
            }
        }

        //Cleanup
        applicationLifecycle.stop().futureValue
      }
    }
  }

}
