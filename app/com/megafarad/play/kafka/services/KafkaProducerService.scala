package com.megafarad.play.kafka
import org.apache.kafka.clients.producer.{Callback, KafkaProducer, ProducerConfig, ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import com.codahale.metrics.{Meter, MetricRegistry}
import play.api.Configuration

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._
import java.util.Properties

class KafkaProducerService[K, V](config: Configuration, metrics: MetricRegistry)(implicit ec: ExecutionContext) {

  // Extract producer-specific configurations
  val bootstrapServers: String = config.get[String]("kafka.bootstrap.servers")
  val acks: String = config.getOptional[String]("kafka.acks").getOrElse("all") // default to 'all' for safety
  val retries: Int = config.getOptional[Int]("kafka.retries").getOrElse(3)
  val batchSize: Int = config.getOptional[Int]("kafka.batch.size").getOrElse(16384)
  val lingerMs: Int = config.getOptional[Int]("kafka.linger.ms").getOrElse(1)

  // Metrics to track messages sent and failed
  val messagesSent: Meter = metrics.meter("kafka.producer.messages-sent")
  val messagesFailed: Meter = metrics.meter("kafka.producer.messages-failed")

  // Create the Kafka producer
  val kafkaProducer: KafkaProducer[K, V] = createKafkaProducer()

  private def createKafkaProducer(): KafkaProducer[K, V] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.ACKS_CONFIG, acks)
    props.put(ProducerConfig.RETRIES_CONFIG, retries.toString)
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize.toString)
    props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs.toString)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)

    new KafkaProducer[K, V](props)
  }

  // Synchronously send a message to a Kafka topic
  def sendSync(topic: String, key: K, value: V): RecordMetadata = {
    val record = new ProducerRecord[K, V](topic, key, value)
    try {
      val metadata = kafkaProducer.send(record).get() // Synchronous blocking send
      messagesSent.mark() // Mark message as sent successfully
      metadata
    } catch {
      case ex: Exception =>
        messagesFailed.mark() // Mark message failure
        throw ex
    }
  }

  // Asynchronously send a message to Kafka with a Future result
  def sendAsync(topic: String, key: K, value: V): Future[RecordMetadata] = {
    val record = new ProducerRecord[K, V](topic, key, value)
    val promise = Promise[RecordMetadata]()

    val callback = new Callback {
      def onCompletion(metadata: RecordMetadata, exception: Exception): Unit = {
        if (exception != null) {
          messagesFailed.mark() // Mark failure
          promise.failure(exception)
        } else {
          messagesSent.mark() // Mark success
          promise.success(metadata)
        }
      }
    }
    kafkaProducer.send(record, callback)

    promise.future
  }

  // Shutdown the producer to free resources
  def close(): Unit = {
    kafkaProducer.close()
  }
}
