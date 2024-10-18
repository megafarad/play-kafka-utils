package com.megafarad.play.kafka.macros

import com.megafarad.play.kafka.services.{KafkaConsumerService, KafkaMessageHandlerService}

import scala.language.experimental.macros
import scala.reflect.macros.blackbox


object KafkaProvidesMacros {
  def generateConsumerProvidesMethod[K, V, KMHS <: KafkaMessageHandlerService[K, V]](name: String): KafkaConsumerService[K, V] = macro generateConsumerProvidesMethodImpl[K, V, KMHS]

  def generateConsumerProvidesMethodImpl[K: c.WeakTypeTag, V: c.WeakTypeTag, KMHS <: KafkaMessageHandlerService[K, V]: c.WeakTypeTag](c: blackbox.Context)(name: c.Expr[String]): c.Expr[KafkaConsumerService[K, V]] = {
    import c.universe._

    // Get the type of the class (KafkaConsumerService[K, V])
    val tpe = weakTypeOf[KafkaConsumerService[K, V]]
    val keyType = weakTypeOf[K]
    val valueType = weakTypeOf[V]
    val handlerType = weakTypeOf[KMHS]

    // Extract the provided name
    val Literal(Constant(bindingName: String)) = name.tree
    val methodName = TermName(s"provide${bindingName.capitalize}${tpe.typeSymbol.name.decodedName.toString}")

    // Generate the @Provides method for KafkaConsumerService[K, V]
    val providesMethod = q"""
        @com.google.inject.Provides
        @com.google.inject.Singleton
        @com.google.inject.name.Named($bindingName)
        def $methodName(messageHandlerService: $handlerType,
                        config: play.api.Configuration,
                        metrics: io.micrometer.core.instrument.MeterRegistry,
                        lifecycle: play.api.inject.ApplicationLifecycle)
                       (implicit system: org.apache.pekko.actor.ActorSystem, ec: scala.concurrent.ExecutionContext): com.megafarad.play.kafka.services.KafkaConsumerService[$keyType, $valueType] = {
          val consumerConfig = config.get[play.api.Configuration]("kafka." + $bindingName)
          new com.megafarad.play.kafka.services.KafkaConsumerService[$keyType, $valueType](messageHandlerService, config, consumerConfig, metrics, lifecycle)
        }
    """

    // Return the generated method
    c.Expr[KafkaConsumerService[K, V]](providesMethod)
  }
}
