name := """play-kafka-utils"""

version := "1.0.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.15"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
libraryDependencies += "org.apache.kafka" % "kafka-clients" % "3.8.0"
libraryDependencies += "io.dropwizard.metrics" % "metrics-core" % "4.2.25"
libraryDependencies ++= Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % "2.17.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.17.0",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.17.2"
)
libraryDependencies += "com.typesafe" % "config" % "1.4.3"
libraryDependencies += "io.github.embeddedkafka" %% "embedded-kafka" % "3.8.0" % Test