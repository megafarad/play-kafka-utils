package com.megafarad.play.kafka.services

import com.codahale.metrics.{Meter, MetricRegistry}
import org.apache.kafka.clients.producer._
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import java.util.Properties
import scala.concurrent.{ExecutionContext, Future, Promise}

class KafkaProducerService[K, V](config: Configuration,
                                 producerConfig: Configuration,
                                 metrics: MetricRegistry,
                                 applicationLifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  // Extract producer-specific configurations
  val bootstrapServers: String = config.get[String]("kafka.bootstrap.servers")
  val acks: String = producerConfig.getOptional[String]("acks").getOrElse("all") // default to 'all' for safety
  val retries: Int = producerConfig.getOptional[Int]("retries").getOrElse(3)
  val batchSize: Int = producerConfig.getOptional[Int]("batch.size").getOrElse(16384)
  val lingerMs: Int = producerConfig.getOptional[Int]("linger.ms").getOrElse(1)
  val keySerializer: String = producerConfig.get[String]("key.serializer")
  val valueSerializer: String = producerConfig.get[String]("value.serializer")
  val metricsName: String = producerConfig.get[String]("metrics.name")


  // Metrics to track messages sent and failed
  val messagesSent: Meter = metrics.meter(s"kafka.$metricsName.messages-sent")
  val messagesFailed: Meter = metrics.meter(s"kafka.$metricsName.messages-failed")

  // Create the Kafka producer
  val kafkaProducer: KafkaProducer[K, V] = createKafkaProducer()

  private def createKafkaProducer(): KafkaProducer[K, V] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ProducerConfig.ACKS_CONFIG, acks)
    props.put(ProducerConfig.RETRIES_CONFIG, retries.toString)
    props.put(ProducerConfig.BATCH_SIZE_CONFIG, batchSize.toString)
    props.put(ProducerConfig.LINGER_MS_CONFIG, lingerMs.toString)
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, keySerializer)
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, valueSerializer)

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

  applicationLifecycle.addStopHook(() => Future {
    close()
  })
}
