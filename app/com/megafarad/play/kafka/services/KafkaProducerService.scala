package com.megafarad.play.kafka.services

import io.micrometer.core.instrument.{Counter, MeterRegistry, Tag}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer._
import org.apache.kafka.common.config.{SaslConfigs, SslConfigs}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle

import java.util.Properties
import javax.inject.Inject
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.jdk.CollectionConverters._

class KafkaProducerService[K, V] @Inject()(config: Configuration,
                                           producerConfig: Configuration,
                                           metrics: MeterRegistry,
                                           applicationLifecycle: ApplicationLifecycle)(implicit ec: ExecutionContext) {

  val bootstrapServers: String = config.get[String]("kafka.bootstrap.servers")
  val securityProtocol: Option[String] = config.getOptional[String]("kafka.security.protocol")
  val saslMechanism: Option[String] = config.getOptional[String]("kafka.sasl.mechanism")
  val jaasConfig: Option[String] = config.getOptional[String]("kafka.sasl.jaas.config")
  val trustStoreLocation: Option[String] = config.getOptional[String]("kafka.ssl.truststore.location")
  val trustStorePassword: Option[String] = config.getOptional[String]("kafka.ssl.truststore.password")
  val keystoreLocation: Option[String] = config.getOptional[String]("kafka.ssl.keystore.location")
  val keystorePassword: Option[String] = config.getOptional[String]("kafka.ssl.keystore.password")
  val keyPassword: Option[String] = config.getOptional[String]("kafka.ssl.key.password")

  // Extract producer-specific configurations
  val acks: String = producerConfig.getOptional[String]("acks").getOrElse("all") // default to 'all' for safety
  val retries: Int = producerConfig.getOptional[Int]("retries").getOrElse(3)
  val batchSize: Int = producerConfig.getOptional[Int]("batch.size").getOrElse(16384)
  val lingerMs: Int = producerConfig.getOptional[Int]("linger.ms").getOrElse(1)
  val keySerializer: String = producerConfig.get[String]("key.serializer")
  val valueSerializer: String = producerConfig.get[String]("value.serializer")
  val metricsTags: immutable.Iterable[Tag] = producerConfig.get[Map[String, String]]("metrics.tags").map {
    case (key, value) => Tag.of(key, value)
  }

  // Metrics to track messages sent and failed
  val messagesSent: Counter = Counter.builder("kafka.messages-sent").tags(metricsTags.asJava).register(metrics)
  val messagesFailed: Counter = Counter.builder("kafka.messages-failed").tags(metricsTags.asJava).register(metrics)

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

    securityProtocol.foreach(props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, _))
    saslMechanism.foreach(props.put(SaslConfigs.SASL_MECHANISM, _))
    jaasConfig.foreach(props.put(SaslConfigs.SASL_JAAS_CONFIG, _))
    trustStoreLocation.foreach(props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, _))
    trustStorePassword.foreach(props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, _))
    keystoreLocation.foreach(props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, _))
    keystorePassword.foreach(props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, _))
    keyPassword.foreach(props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, _))

    new KafkaProducer[K, V](props)
  }

  // Synchronously send a message to a Kafka topic
  def sendSync(topic: String, key: K, value: V): RecordMetadata = {
    val record = new ProducerRecord[K, V](topic, key, value)
    try {
      val metadata = kafkaProducer.send(record).get() // Synchronous blocking send
      messagesSent.increment() // Mark message as sent successfully
      metadata
    } catch {
      case ex: Exception =>
        messagesFailed.increment() // Mark message failure
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
          messagesFailed.increment() // Mark failure
          promise.failure(exception)
        } else {
          messagesSent.increment() // Mark success
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
