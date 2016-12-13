lazy val circeVersion = "0.6.1"

lazy val logbackVersion = "1.1.7"

lazy val commonSettings = Seq(
  githubProject := "cedi-dtrace",
  contributors ++= Seq(
    Contributor("sbuzzard", "Steve Buzzard")
  ),
  libraryDependencies ++= Seq(
    "co.fs2" %% "fs2-core" % "0.9.2",
    "org.scalatest" %% "scalatest" % "3.0.1" % "test"
  ),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.3")
)

lazy val root = project.in(file(".")).aggregate(core, logging).settings(commonSettings).settings(noPublish)

lazy val core = project.in(file("core")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "dtrace-core",
    buildOsgiBundle("com.ccadllc.cedi.dtrace")
  )

lazy val logging = project.in(file("logging")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "dtrace-logging",
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-java8" % circeVersion,
      "org.slf4j" % "slf4j-api" % "1.7.21",
      "ch.qos.logback" % "logback-core" % logbackVersion % "test",
      "ch.qos.logback" % "logback-classic" % logbackVersion % "test",
      "org.scalacheck" %% "scalacheck" % "1.13.4" % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logging")
  ).dependsOn(core % "compile->compile;test->test")

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).settings(
  tutSettings,
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core, logging)
