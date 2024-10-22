# **play-kafka-utils**

**play-kafka-utils** is a Scala library that provides seamless integration between **Kafka** and the **Play Framework**, abstracting Kafka producers and consumers into dependency injection (DI) services. It aims to simplify working with Kafka in a Play application by offering support for asynchronous message processing, error handling, retries, and metrics using **Micrometer**.

## **Features**
- **Kafka Consumer and Producer Abstractions**: Easy-to-use, DI-friendly Kafka consumer and producer services.
- **Retry Logic**: Support for retries with exponential backoff for message consumption.
- **Dead-letter Topic Handling**: Configurable dead-letter topic support for failed messages.
- **Metrics**: Micrometer-based metrics integration with support for Prometheus, Graphite, Datadog, and other monitoring backends.
- **Flexible Authentication**: Supports SASL/PLAIN, SASL/SCRAM, SSL, and OAuth for authenticating with Kafka brokers.
- **Customizable via Configuration**: Allows users to define consumer/producer behavior, retry policies, and more through Play’s configuration system.
- **Pluggable Micrometer Backend**: The library user can choose their preferred metrics backend (Prometheus, Graphite, Datadog, etc.) by configuring the `MeterRegistry`.

## **Table of Contents**
- [Installation](#installation)
- [Quick Start](#usage-instructions)
- [Configuration](#configuration)
- [Metrics](#metrics)
- [Error Handling and Retries](#error-handling-and-retries)
- [Authentication](#authentication)
- [Contributing](#contributing)
- [License](#license)

---

## **Installation**

To install **play-kafka-utils**, add the following dependency to your `build.sbt` file:

```scala
libraryDependencies += "com.megafarad" % "play-kafka-utils" % "1.0.0"
```

If you're using specific backends for Micrometer (e.g., Prometheus, Graphite, Datadog), include the relevant dependencies as well:

```scala
libraryDependencies += "io.micrometer" % "micrometer-registry-prometheus" % "1.13.2"
```

```scala
libraryDependencies +=   "io.micrometer" % "micrometer-registry-graphite" % "1.13.2"
```

```scala
libraryDependencies += "io.micrometer" % "micrometer-registry-datadog" % "1.13.2"
```

---

## **Usage Instructions**

### **Step 1: Configuration**

Add the necessary Kafka configuration in your `application.conf` file:

```hocon
kafka {
  bootstrap.servers = "localhost:9092"
  
  consumer {
    group.id = "consumer-group-1"
    topics = ["your-topic"]
    key.deserializer = "org.apache.kafka.common.serialization.StringDeserializer"
    value.deserializer = "org.apache.kafka.common.serialization.StringDeserializer"
    key.serializer = "org.apache.kafka.common.serialization.StringSerializer"
    value.serializer = "org.apache.kafka.common.serialization.StringSerializer"
    auto.offset.reset = "earliest"
    
    max-retries = 5
    base-delay = 500 millis
    polling-interval = 5 seconds
    dead-letter-topic = "dead-letter-topic"
  }

  producer {
    metrics.tags = {
      name = "producer1"
    }
    key.serializer = "org.apache.kafka.common.serialization.StringSerializer"
    value.serializer = "org.apache.kafka.common.serialization.StringSerializer"
  }
}
```
### **Step 2: Implement a message handler service**

This sample implements a message handler that simply logs the Kafka message:

```scala
import com.megafarad.play.kafka.services.KafkaMessageHandlerService
import com.megafarad.play.kafka.model.KafkaMessage
import play.api.Logging

import javax.inject.Inject
import scala.concurrent.Future

class SampleKafkaMessageHandlerService @Inject() extends KafkaMessageHandlerService[String, String] with Logging {
  def processMessage(message: KafkaMessage[String, String]): Future[Unit] = Future.successful({
    logger.info(s"Processed message ${message.key} -> ${message.value}")
  })
}
```

### **Step 3: Implement a startup service**

This service starts polling in KafkaConsumerService instances.

```scala
import javax.inject.{Inject, Named}

//Be sure to inject all KafkaConsumerService instances with their named annotations here...
class StartupService @Inject()(@Named("consumer") consumerService: KafkaConsumerService[String, String])
  extends KafkaConsumerStartupService {

  val services: Seq[KafkaConsumerService[_, _]] = Seq(consumerService) //...and be sure you include all class parameters here.

  //Be sure to call startAll() in the constructor.
  startAll()
}

```

### **Step 4: Bind services in a module**

Create a module that looks something like this:

```scala
class KafkaModule extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    //Binds a named KafkaMessageHandlerService. You can have multiple instances of the service... 
    bind[KafkaMessageHandlerService[String, String]]
      .annotatedWith(Names.named("handler")) // ...just be sure to name them uniquely here.
      .to[SampleKafkaMessageHandlerService]
    //Binds your startup service as an eager singleton - so that it starts polling.
    bind[KafkaConsumerStartupService].to[StartupService].asEagerSingleton()
    //Binds the MeterRegistry. You should choose a backend. Let's say you chose Prometheus. Your binding should look 
    //something like:
    bind[PrometheusMeterRegistry].toInstance(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
    //You'd then inject an instance of PrometheusMeterRegistry in the following Provides method.
    //OR: If you don't care about metrics, just bind MetricsRegistry to a SimpleMeterRegistry instance:
    bind[MeterRegistry].toInstance(new SimpleMeterRegistry())
    
  }

  //This binds KafkaConsumerService. You can bind multiple instances with multiple @Provides methods... 
  @Provides
  @Singleton
  @Named("consumer")//...just be sure to name them uniquely...
  def provideConsumerService(@Named("handler") messageHandlerService: KafkaMessageHandlerService[String, String],
                              config: Configuration,
                              metrics: MeterRegistry, //If using Prometheus (or other backend), inject PrometheusMeterRegistry (or some other MeterRegistry) instead
                              lifecycle: ApplicationLifecycle)
                             (implicit system: ActorSystem,
                              ec: ExecutionContext): KafkaConsumerService[String, String] = {
    val consumerConfig = config.get[Configuration]("kafka.consumer") //...and be sure to extract the right configuration key for the consumer.
    new KafkaConsumerService[String, String](messageHandlerService, config, consumerConfig, metrics, lifecycle)
  }

  //This binds KafkaProducerService. You can bind multiple instances with multiple @Provides methods...
  @Provides
  @Singleton
  @Named("producer")//...just be sure to name them uniquely...
  def provideProducerService(config: Configuration,
                              metrics: MeterRegistry,//If using Prometheus (or other backend), inject PrometheusMeterRegistry (or some other MeterRegistry) instead
                              lifecycle: ApplicationLifecycle)
                             (implicit ec: ExecutionContext): KafkaProducerService[String, String] = {
    val producerConfig = config.get[Configuration]("kafka.producer")//...and be sure to extract the right configuration key for the producer.
    new KafkaProducerService[String, String](config, producerConfig, metrics, lifecycle)

  }
}
```
Once you create the module, include it in your `application.conf`

```hocon
play.modules.enabled += "com.yourorg.modules.KafkaModule"
```

---

## **Configuration**

### **Kafka Consumer Configuration**

You can configure Kafka consumers using the `application.conf` file. Below is an example of typical Kafka consumer configuration:

```hocon
kafka.consumer {
  group.id = "consumer-group-1"
  topics = ["your-topic"]
  key.deserializer = "org.apache.kafka.common.serialization.StringDeserializer"
  value.deserializer = "org.apache.kafka.common.serialization.StringDeserializer"
  key.serializer = "org.apache.kafka.common.serialization.StringSerializer"
  value.serializer = "org.apache.kafka.common.serialization.StringSerializer"
  auto.offset.reset = "earliest"
  
  max-retries = 5
  base-delay = 500 millis
  polling-interval = 5 seconds
  dead-letter-topic = "dead-letter-topic"
}
```

### **Kafka Producer Configuration**

Similarly, configure Kafka producers in `application.conf`:

```hocon
kafka.producer {
  metrics.tags = {
    name = "producer"
  }
  key.serializer = "org.apache.kafka.common.serialization.StringSerializer"
  value.serializer = "org.apache.kafka.common.serialization.StringSerializer"
}
```

---

## **Metrics**

**play-kafka-utils** uses **Micrometer** for metrics instrumentation. Users can choose their preferred monitoring 
backend by binding an instance of its registry in a module. For example, for Prometheus - first, add a dependency for
its backend:

```scala
libraryDependencies += "io.micrometer" % "micrometer-registry-prometheus" % "1.13.2"
```
...and then, binding an instance of its registry:

```scala
class KafkaModule extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    //Other bindings
    bind[PrometheusMeterRegistry].toInstance(new PrometheusMeterRegistry(PrometheusConfig.DEFAULT))
    //Other bindings
  }
  //Provides methods
}
```

### Exposing Metrics:

You can expose metrics through a Play controller:

```scala
import javax.inject.Inject
import io.micrometer.prometheus.PrometheusMeterRegistry
import play.api.mvc._

class MetricsController @Inject()(cc: ControllerComponents, registry: PrometheusMeterRegistry) extends AbstractController(cc) {
  def metrics() = Action {
    Ok(registry.scrape()).as("text/plain")
  }
}
```

**Note:** it is strongly recommended to secure this endpoint with something like 
[Silhouette](https://github.com/playframework/play-silhouette).

---

## **Error Handling and Retries**

You can configure retries for Kafka consumers in `application.conf`:

```hocon
kafka.consumer {
  max-retries = 5  // Number of retry attempts
  base-delay = 500 millis  // Base delay between retries
}
```
The retry logic is to increase the delay exponentially with each successive retry. With the above configuration, the 
consumer will retry five times with the delay of 500ms, 1000ms, 2000ms, 4000ms, and 8000ms. 

Messages that fail after maximum retries are sent to a **dead-letter topic**:
```hocon
kafka.consumer.dead-letter-topic = "dead-letter-topic"
```

---

## **Authentication**

**play-kafka-utils** supports Kafka authentication mechanisms like SASL/PLAIN, SASL/SCRAM, and SSL. Add the necessary configuration in `application.conf`:

```hocon
kafka {
  security.protocol = "SASL_PLAINTEXT"
  sasl.mechanism = "PLAIN"
  sasl.jaas.config = "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"your-username\" password=\"your-password\";"
}
```

You can also use SSL for encrypted communication:

```hocon
kafka {
  security.protocol = "SSL"
  ssl.keystore.location = "/path/to/keystore.jks"
  ssl.keystore.password = "your-keystore-password"
  ssl.truststore.location = "/path/to/truststore.jks"
  ssl.truststore.password = "your-truststore-password"
}
```

---

## **Contributing**

Contributions to **play-kafka-utils** are welcome! Feel free to submit issues, bug fixes, or feature requests.

### **Development Setup**

1. Fork the repository
2. Clone your fork locally: `git clone https://github.com/megafarad/play-kafka-utils.git`
3. Create a new branch for your feature/fix: `git checkout -b feature-branch`
4. Submit a pull request with a clear description of your changes.

---

## **License**

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

