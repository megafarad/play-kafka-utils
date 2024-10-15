package com.megafarad.play.kafka.module

import com.google.inject.name.Names
import com.megafarad.play.kafka.services.KafkaProducerService
import io.github.embeddedkafka.EmbeddedKafka
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest.Assertion
import org.scalatest.concurrent.Eventually
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.inject.{BindingKey, QualifierInstance}

import scala.jdk.CollectionConverters._

class TestModuleSpec extends PlaySpec with GuiceOneAppPerTest with EmbeddedKafka with Eventually {

  "TestModule" should {
    "Bind named instances of KafkaProducerService" ignore {
      withRunningKafka {
        //Arrange
        val producer1 = app.injector.instanceOf(BindingKey(classOf[KafkaProducerService[String, String]],
          Some(QualifierInstance(Names.named("producer1")))))
        val producer2 = app.injector.instanceOf(BindingKey(classOf[KafkaProducerService[String, String]],
          Some(QualifierInstance(Names.named("producer2")))))
        val producer3 = app.injector.instanceOf(BindingKey(classOf[KafkaProducerService[String, String]],
          Some(QualifierInstance(Names.named("producer3")))))

        //Act
        producer1.sendSync("topic1", "key", "value")
        producer2.sendSync("topic2", "key", "value")
        producer3.sendSync("topic3", "key", "value")

        //Assert
        assertMessageCount("topic1", 1)
        assertMessageCount("topic2", 1)
        assertMessageCount("topic3", 1)
      }
    }
  }

  def assertMessageCount(topic: String, count: Int): Assertion = {
    implicit val deserializer: StringDeserializer = new StringDeserializer
    EmbeddedKafka
      .withConsumer[String, String, org.scalatest.Assertion] {
        consumer =>
          consumer.subscribe(Set(topic).asJava)
          eventually {
            assert(consumer.poll(java.time.Duration.ofMillis(1000)).count() == count)
          }
      }
  }
}
