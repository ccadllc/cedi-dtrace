lazy val circeVersion = "0.9.0-M2"

lazy val logbackVersion = "1.1.7"

lazy val commonSettings = Seq(
  githubProject := "cedi-dtrace",
  contributors ++= Seq(
    Contributor("sbuzzard", "Steve Buzzard"),
    Contributor("mpilquist", "Michael Pilquist")
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-effect" % "0.5",
    "org.scalatest" %% "scalatest" % "3.0.4" % "test",
    "org.scalacheck" %% "scalacheck" % "1.13.5" % "test"
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
      "ch.qos.logback" % "logback-classic" % logbackVersion % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logging")
  ).dependsOn(core % "compile->compile;test->test")

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(core, logging)
