package com.megafarad.play.kafka

import com.codahale.metrics.{Gauge, Meter, MetricRegistry, Timer}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord, KafkaConsumer, OffsetAndMetadata}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.{Configuration, Logging}

import java.util.Properties
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class KafkaConsumerService[K, V](messageHandlerService: KafkaMessageHandlerService[K, V],
                                 config: Configuration,
                                 consumerConfig: Configuration,
                                 metrics: MetricRegistry,
                                 applicationLifecycle: ApplicationLifecycle)(implicit system: ActorSystem, ec: ExecutionContext)
  extends Logging {

  // Extract consumer-specific configurations
  val groupId: String = consumerConfig.get[String]("group.id")
  val topics: Seq[String] = consumerConfig.get[Seq[String]]("topics")
  val bootstrapServers: String = config.get[String]("kafka.bootstrap.servers")
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
  val messagesProcessed: Meter = metrics.meter(s"kafka.$groupId.messages-processed")
  val messagesFailed: Meter = metrics.meter(s"kafka.$groupId.messages-failed")
  val messagesRetried: Meter = metrics.meter(s"kafka.$groupId.messages-retried")
  val messagesDeadLettered: Meter = metrics.meter(s"kafka.$groupId.messages-dead-lettered")
  val processingTimer: Timer = metrics.timer(s"kafka.$groupId.message-processing-time")
  val pollTimer: Timer = metrics.timer(s"kafka.$groupId.poll-duration")

  // Create a Gauge to track consumer lag
  val consumerLagGauge: Gauge[Long] = metrics.register(s"kafka.$groupId.consumer-lag", new Gauge[Long] {
    override def getValue: Long = calculateConsumerLag()
  })

  // Kafka consumer instance
  val kafkaConsumer: KafkaConsumer[K, V] = createKafkaConsumer()
  val deadLetterProducer: KafkaProducer[K, V] = createDeadLetterProducer()

  private def createDeadLetterProducer(): KafkaProducer[K, V] = {
    val props = new Properties()
    props.put("bootstrap.servers", bootstrapServers)
    props.put("key.serializer", keySerializer)
    props.put("value.serializer", valueSerializer)
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

    new KafkaConsumer[K, V](props)
  }

  def startPolling(): Unit = {
    kafkaConsumer.subscribe(topics.asJava)

    // Start polling and processing messages
    logger.info(s"Kafka consumer for group $groupId started. Subscribed to topics: ${topics.mkString(", ")}")
    pollKafkaWithRetries(attempt = 1)
  }

  startPolling()

  applicationLifecycle.addStopHook(() => Future {
    kafkaConsumer.close()
    deadLetterProducer.close()
  })

  // Retry logic with exponential backoff
  private def pollKafkaWithRetries(attempt: Int): Unit = {
    pollKafka().onComplete {
      case Success(_) =>
        logger.info("Polling and message processing succeeded, updating consumer lag.")

        // Update the consumer lag after successful message processing
        val lag = calculateConsumerLag()
        logger.info(s"Consumer lag: $lag messages")

        scheduleNextPoll()

      case Failure(ex) if attempt <= maxRetries =>
        val backoffTime = baseDelay * math.pow(2, attempt - 1).toLong
        messagesRetried.mark()
        logger.warn(s"Polling failed. Retrying in $backoffTime... (Attempt $attempt)", ex)

        org.apache.pekko.pattern.after(backoffTime, system.scheduler) {
          Future(pollKafkaWithRetries(attempt + 1))
        }

      case Failure(ex) =>
        logger.error(s"Polling failed after $maxRetries retries. Sending messages to dead-letter topic.", ex)
        pollKafka().map { records =>
          sendToDeadLetterTopic(records)
        }
    }
  }

  // Schedule the next poll after the polling interval
  private def scheduleNextPoll(): Unit = {
    system.scheduler.scheduleOnce(pollingInterval) {
      pollKafkaWithRetries(attempt = 1)
    }
  }

  // Poll Kafka for messages and process them
  private def pollKafka(): Future[Iterable[ConsumerRecord[K, V]]] = {
    val context = pollTimer.time() // Start timing the poll
    Future {
      Try {
        kafkaConsumer.poll(java.time.Duration.ofMillis(100)) // Poll Kafka
      } match {
        case Failure(exception) =>
          logger.error("Failure while polling Kafka: ", exception)
          context.stop() // Stop poll timer
          Future.failed(exception) // Return a failed Future

        case Success(records) =>
          context.stop() // Stop poll timer
          logger.debug(s"Polled ${records.count()} records from Kafka.")

          // Process each record and return a Future
          val futures = records.asScala.map { record =>
            val processingContext = processingTimer.time() // Start timing message processing
            messageHandlerService.processMessage(record.key(), record.value()).andThen {
              case Success(_) =>
                processingContext.stop() // Stop message processing timer
                messagesProcessed.mark()
                logger.info(s"Successfully processed message from topic=${record.topic()}, partition=${record.partition()}, offset=${record.offset()}, key=${record.key()}")

              case Failure(ex) =>
                processingContext.stop() // Stop message processing timer
                messagesFailed.mark()
                logger.error(s"Failed to process message from topic=${record.topic()}, partition=${record.partition()}, offset=${record.offset()}, key=${record.key()}", ex)
            }
          }

          // Return a Future once all message processing is done
          Future.sequence(futures).map { _ =>
            kafkaConsumer.commitSync() // Commit offsets after all messages are processed
            logger.info("Offsets committed successfully.")
            records.asScala // Return the records for further use
          }
      }
    }.flatten // Flatten the Future to return a Future[Iterable[ConsumerRecord[K, V]]]
  }

  // Send messages to the dead-letter topic
  private def sendToDeadLetterTopic(records: Iterable[ConsumerRecord[K, V]]): Unit = {
    records.foreach { record =>
      logger.warn(s"Sending failed message with key=${record.key()} to dead-letter topic: $deadLetterTopic")

      // Publish the problematic message to the dead-letter topic
      deadLetterProducer.send(new ProducerRecord[K, V](deadLetterTopic, record.key(), record.value()))

      // Commit the offset for the problematic message so it doesn't get retried
      kafkaConsumer.commitSync(Map(new TopicPartition(record.topic(), record.partition()) ->
        new OffsetAndMetadata(record.offset() + 1)).asJava)

      messagesDeadLettered.mark() // Track the number of dead-lettered messages
    }
  }

  // Calculate the consumer lag
  private def calculateConsumerLag(): Long = {
    val partitions = kafkaConsumer.assignment().asScala
    kafkaConsumer.seekToEnd(partitions.asJava) // Get the latest offsets for each partition

    // Calculate the lag for each partition
    partitions.map { partition =>
      val latestOffset = kafkaConsumer.position(partition)
      val committedOffset = kafkaConsumer.committed(Set(partition).asJava).get(partition).offset()
      latestOffset - committedOffset
    }.sum // Return the total lag across all partitions
  }
}
