ThisBuild / organization := "com.megafarad"
ThisBuild / organizationName := "Megafarad"
ThisBuild / organizationHomepage := Some(url("http://megafarad.com/"))

ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/megafarad/play-kafka-utils"),
    "scm:git@github.com:megafarad/play-kafka-utils.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "SirHC1977",
    name = "Chris Carrington",
    email = "chris@megafarad.com",
    url = url("https://megafarad.com")
  )
)

ThisBuild / description := "A collection of services and serialization utilities for utilizing Kafka in a Play Framework environment."
ThisBuild / licenses := List(
  "MIT" -> url("https://opensource.org/licenses/MIT")
)
ThisBuild / homepage := Some(url("https://github.com/megafarad/play-kafka-utils"))

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishTo := {
  // For accounts created after Feb 2021:
  val nexus = "https://s01.oss.sonatype.org/"
  if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
  else Some("releases" at nexus + "service/local/staging/deploy/maven2")
}
ThisBuild / publishMavenStyle := true