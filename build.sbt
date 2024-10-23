name := """play-kafka-utils"""

version := "1.0.0"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.13.15"

crossPaths := false

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test
libraryDependencies += "org.apache.kafka" % "kafka-clients" % "3.8.0"
libraryDependencies += "io.micrometer" % "micrometer-core" % "1.13.1"
libraryDependencies ++= Seq(
  "com.fasterxml.jackson.core" % "jackson-core" % "2.17.2",
  "com.fasterxml.jackson.core" % "jackson-databind" % "2.17.0",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.17.2"
)
libraryDependencies += "net.codingwell" %% "scala-guice" % "6.0.0"
libraryDependencies += "com.typesafe" % "config" % "1.4.3"
libraryDependencies += "io.github.embeddedkafka" %% "embedded-kafka" % "3.8.0" % Test