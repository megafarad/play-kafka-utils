package com.megafarad.play.kafka.services

import io.micrometer.core.instrument.{Counter, Gauge, MeterRegistry, Timer}
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecords, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerConfig, ProducerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.{SaslConfigs, SslConfigs}
import org.apache.pekko.actor.{ActorSystem, Cancellable}
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logging}

import java.util.Properties
import java.util.function.ToDoubleFunction
import javax.inject.Inject
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class KafkaConsumerService[K, V] @Inject()(messageHandlerService: KafkaMessageHandlerService[K, V],
                                           config: Configuration,
                                           consumerConfig: Configuration,
                                           metrics: MeterRegistry,
                                           applicationLifecycle: ApplicationLifecycle)(implicit system: ActorSystem,
                                                                                                ec: ExecutionContext)
  extends Logging {

  val bootstrapServers: String = config.get[String]("kafka.bootstrap.servers")
  val securityProtocol: Option[String] = config.getOptional[String]("kafka.security.protocol")
  val saslMechanism: Option[String] = config.getOptional[String]("kafka.sasl.mechanism")
  val jaasConfig: Option[String] = config.getOptional[String]("kafka.sasl.jaas.config")
  val trustStoreLocation: Option[String] = config.getOptional[String]("kafka.ssl.truststore.location")
  val trustStorePassword: Option[String] = config.getOptional[String]("kafka.ssl.truststore.password")
  val keystoreLocation: Option[String] = config.getOptional[String]("kafka.ssl.keystore.location")
  val keystorePassword: Option[String] = config.getOptional[String]("kafka.ssl.keystore.password")
  val keyPassword: Option[String] = config.getOptional[String]("kafka.ssl.key.password")

  // Extract consumer-specific configurations
  val groupId: String = consumerConfig.get[String]("group.id")
  val topics: Seq[String] = consumerConfig.get[Seq[String]]("topics")
  val keyDeserializer: String = consumerConfig.get[String]("key.deserializer")
  val valueDeserializer: String = consumerConfig.get[String]("value.deserializer")
  val keySerializer: String = consumerConfig.get[String]("key.serializer")
  val valueSerializer: String = consumerConfig.get[String]("value.serializer")
  val autoOffsetReset: String = consumerConfig.get[String]("auto.offset.reset")
  val maxRetries: Int = consumerConfig.get[Int]("max-retries")
  val baseDelay: FiniteDuration = consumerConfig.get[FiniteDuration]("base-delay")
  val pollingInterval: FiniteDuration = consumerConfig.get[FiniteDuration]("polling-interval")
  val deadLetterTopic: String = consumerConfig.get[String]("dead-letter-topic")

  // Metrics
  val messagesProcessed: Counter = Counter.builder("kafka.messages-processed").tag("groupId", groupId).register(metrics)
  val messagesFailed: Counter = Counter.builder("kafka.messages-failed").tag("groupId", groupId).register(metrics)
  val messagesRetried: Counter = Counter.builder("kafka.messages-retried").tag("groupId", groupId).register(metrics)
  val messagesDeadLettered: Counter = Counter.builder("kafka.messages-dead-lettered").tag("groupId", groupId)
    .register(metrics)
  val processingTimer: Timer = Timer.builder("kafka.message-processing-time").tag("groupId", groupId).register(metrics)
  val pollTimer: Timer = Timer.builder("kafka.poll-duration").tag("groupId", groupId).register(metrics)

  // Create a Gauge to track consumer lag
  val consumerLagFunction: ToDoubleFunction[KafkaConsumerService[K, V]] = (service: KafkaConsumerService[K, V]) => {
    service.calculateConsumerLag().toDouble
  }
  val consumerLagGauge: Gauge = Gauge.builder("kafka.consumer-lag", this, consumerLagFunction).tag("groupId", groupId)
    .register(metrics)

  // Kafka consumer instance
  val kafkaConsumer: KafkaConsumer[K, V] = createKafkaConsumer()
  val deadLetterProducer: KafkaProducer[K, V] = createDeadLetterProducer()

  private def createDeadLetterProducer(): KafkaProducer[K, V] = {
    val props = new Properties()
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
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

  // Initialize Kafka consumer and subscribe to topics
  private def createKafkaConsumer(): KafkaConsumer[K, V] = {
    val props = new Properties()
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId)
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, keyDeserializer)
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, valueDeserializer)
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")

    securityProtocol.foreach(props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, _))
    saslMechanism.foreach(props.put(SaslConfigs.SASL_MECHANISM, _))
    jaasConfig.foreach(props.put(SaslConfigs.SASL_JAAS_CONFIG, _))
    trustStoreLocation.foreach(props.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, _))
    trustStorePassword.foreach(props.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, _))
    keystoreLocation.foreach(props.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, _))
    keystorePassword.foreach(props.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, _))
    keyPassword.foreach(props.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, _))

    new KafkaConsumer[K, V](props)
  }

  def startPolling(): Unit = {
    kafkaConsumer.subscribe(topics.asJava)

    // Start polling and processing messages
    logger.info(s"Kafka consumer for group $groupId started. Subscribed to topics: ${topics.mkString(", ")}")
    pollKafkaWithRetries(attempt = 1)
  }

  @volatile private var isRunning: Boolean = true

  applicationLifecycle.addStopHook(() => Future {
    stopPolling()
    kafkaConsumer.wakeup()
    kafkaConsumer.close()
    deadLetterProducer.close()
  })

  def stopPolling(): Unit = {
    isRunning = false
  }

  // Retry logic with exponential backoff
  private def pollKafkaWithRetries(attempt: Int): Unit = {
    (for {
      records <- pollKafka()
      _ <- processRecords(records).andThen {
        case Failure(ex) if attempt <= maxRetries =>
          val backoffTime = baseDelay * math.pow(2, attempt - 1).toLong
          messagesRetried.increment()

          if (isRunning) {
            logger.warn(s"Polling failed. Retrying in $backoffTime... (Attempt $attempt)", ex)

            org.apache.pekko.pattern.after(backoffTime, system.scheduler) {
              Future(pollKafkaWithRetries(attempt + 1))
            }
          }
        case Failure(ex) =>
          logger.error(s"Polling failed after $maxRetries retries. Sending messages to dead-letter topic.", ex)
          sendToDeadLetterTopic(records)
          if (isRunning) scheduleNextPoll()
        case Success(_) =>
          logger.info("Polling and message processing succeeded, updating consumer lag.")
          // Update the consumer lag after successful message processing
          val lag = calculateConsumerLag()
          logger.info(s"Consumer lag: $lag messages")

          if (isRunning) scheduleNextPoll()
      }
    } yield ()).onComplete {
      case Failure(exception) =>
        logger.error(s"Poll kafka with retries failed at attempt $attempt", exception)
      case Success(_) =>
        logger.debug(s"Kafka Polling Attempt $attempt Complete")
    }
  }

  // Schedule the next poll after the polling interval
  private def scheduleNextPoll(): Cancellable = {
    system.scheduler.scheduleOnce(pollingInterval) {
      pollKafkaWithRetries(attempt = 1)
    }
  }

  private def pollKafka(): Future[ConsumerRecords[K, V]] = {
    val context = Timer.start(metrics)
    Future {
      Try {
        kafkaConsumer.poll(java.time.Duration.ofMillis(100))
      } match {
        case Failure(exception) =>
          logger.error("Failure while polling Kafka: ", exception)
          context.stop(pollTimer)
          throw exception
        case Success(records) =>
          logger.info(s"Polled ${records.count()} records from Kafka.")
          context.stop(pollTimer)
          records
      }
    }
  }

  private def processRecords(records: ConsumerRecords[K, V]): Future[Unit] = {
    val futures = records.asScala.map { record =>
      val processingContext = Timer.start(metrics) // Start timing message processing
      messageHandlerService.processMessage(record.key(), record.value()).andThen {
        case Success(_) =>
          processingContext.stop(processingTimer) // Stop message processing timer
          messagesProcessed.increment()
          logger.info(s"Successfully processed message from topic=${record.topic()}, partition=${record.partition()}, offset=${record.offset()}, key=${record.key()}")

        case Failure(ex) =>
          processingContext.stop(processingTimer) // Stop message processing timer
          messagesFailed.increment()
          logger.error(s"Failed to process message from topic=${record.topic()}, partition=${record.partition()}, offset=${record.offset()}, key=${record.key()}", ex)
          records.partitions().forEach {
            partition =>
              val lastOffset = records.records(partition).getLast.offset()
              kafkaConsumer.seek(partition, lastOffset)
          }
      }
    }
    // Return a Future once all message processing is done
    Future.sequence(futures).map { _ =>
      kafkaConsumer.commitSync() // Commit offsets after all messages are processed
      logger.info("Offsets committed successfully.")
    }
  }

  // Send messages to the dead-letter topic
  private def sendToDeadLetterTopic(records: ConsumerRecords[K, V]): Unit = {
    records.asScala.foreach { record =>
      logger.warn(s"Sending failed message with key=${record.key()} to dead-letter topic: $deadLetterTopic")

      // Publish the problematic message to the dead-letter topic
      deadLetterProducer.send(new ProducerRecord[K, V](deadLetterTopic, record.key(), record.value()))

      // Commit the offset for the problematic message so it doesn't get retried
      kafkaConsumer.commitSync(Map(new TopicPartition(record.topic(), record.partition()) ->
        new OffsetAndMetadata(record.offset() + 1)).asJava)

      kafkaConsumer.seek(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1))

      messagesDeadLettered.increment() // Track the number of dead-lettered messages
    }
  }

  // Calculate the consumer lag
  private def calculateConsumerLag(): Long = {
    val partitions = kafkaConsumer.assignment().asScala
    val currentPositions = partitions.map(p => p -> kafkaConsumer.position(p)).toMap
    kafkaConsumer.seekToEnd(partitions.asJava)
    val lag = partitions.map { partition =>
      val latestOffset = kafkaConsumer.position(partition)
      val committedOffset = Option(kafkaConsumer.committed(Set(partition).asJava).get(partition))
        .map(_.offset()).getOrElse(0L)
      latestOffset - committedOffset
    }.sum
    currentPositions.foreach { case (partition, position) =>
      kafkaConsumer.seek(partition, position) // Restore original position
    }
    lag
  }
}
