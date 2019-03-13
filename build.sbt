import sbtcrossproject.crossProject

lazy val catsEffectVersion = "1.2.0"

lazy val catsCoreVersion = "1.6.0"

lazy val circeVersion = "0.11.1"

lazy val http4sVersion = "0.20.0-M6"

lazy val logbackVersion = "1.2.3"

lazy val slf4jVersion = "1.7.26"

lazy val sloggingVersion = "0.6.1"

lazy val commonSettings = Seq(
  githubProject := "cedi-dtrace",
  parallelExecution in Global := false,
  crossScalaVersions := Seq("2.12.8", "2.11.12"),
  contributors ++= Seq(
    Contributor("sbuzzard", "Steve Buzzard"),
    Contributor("mpilquist", "Michael Pilquist")
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion
  ) ++ (CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, v)) if v >= 13 => Seq(
      "org.scalatest" %% "scalatest" % "3.0.6-SNAP5" % "test",
      "org.scalacheck" %% "scalacheck" % "1.14.0" % "test"
    )
    case _ => Seq(
      "org.scalatest" %% "scalatest" % "3.0.5" % "test",
      "org.scalacheck" %% "scalacheck" % "1.13.5" % "test"
    )
  }),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9"),
  pomExtra := (
    <url>http://github.com/ccadllc/{githubProject.value}</url>
    <developers>
      {for (Contributor(username, name) <- contributors.value) yield
      <developer>
        <id>{username}</id>
        <name>{name}</name>
        <url>https://github.com/{username}</url>
      </developer>
      }
    </developers>
  )
)

lazy val root = project.in(file(".")).aggregate(
  coreJVM,
  coreJS,
  loggingJVM,
  loggingJS,
  logstash,
  xb3JVM,
  xb3JS,
  moneyJVM,
  moneyJS,
  http4s
).settings(commonSettings).settings(noPublish)

lazy val core = crossProject(JVMPlatform, JSPlatform).in(file("core")).
  settings(commonSettings).
  settings(
    name := "dtrace-core",
    libraryDependencies += "org.typelevel" %% "cats-effect-laws" % catsEffectVersion % "test"
  )

lazy val coreJVM = core.jvm.enablePlugins(SbtOsgi).
  settings(buildOsgiBundle("com.ccadllc.cedi.dtrace"))

lazy val coreJS = core.js

lazy val logging = crossProject(JVMPlatform, JSPlatform).in(file("logging")).
  settings(commonSettings).settings(
    name := "dtrace-logging",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion
    )
  )

lazy val loggingJVM = logging.jvm.enablePlugins(SbtOsgi).
  settings(
    // TODO: This is only temporary until slogging publishes for 2.13
    // Replace this libDependencies and the two skips with just a
    // libDeps for the slogging lib
    libraryDependencies := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) if v >= 13 => Seq.empty
      case _ => libraryDependencies.value ++ Seq(
        "biz.enef" %% "slogging" % sloggingVersion,
        "biz.enef" %% "slogging-slf4j" % sloggingVersion,
        "org.slf4j" % "slf4j-api" % slf4jVersion,
        "ch.qos.logback" % "logback-core" % logbackVersion % "test",
        "ch.qos.logback" % "logback-classic" % logbackVersion % "test",
        "net.logstash.logback" % "logstash-logback-encoder" % "5.3" % "optional"
      )
    }),
    skip in compile := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) => v >= 13
      case _ => false
    }),
    skip in publish := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) => v >= 13
      case _ => false
    }),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logging")
  ).dependsOn(coreJVM % "compile->compile;test->test")

lazy val loggingJS = logging.js.
  settings(
    libraryDependencies ++= Seq(
      "biz.enef" %%% "slogging" % sloggingVersion,
      "biz.enef" %%% "slogging-winston" % sloggingVersion
    )
  ).dependsOn(coreJS % "compile->compile;test->test")

lazy val logstash = project.in(file("logstash")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "dtrace-logstash",
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "net.logstash.logback" % "logstash-logback-encoder" % "5.3",
      "ch.qos.logback" % "logback-core" % logbackVersion % "test",
      "ch.qos.logback" % "logback-classic" % logbackVersion % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logstash")
  ).dependsOn(coreJVM % "compile->compile;test->test")

lazy val xb3 = crossProject(JVMPlatform, JSPlatform).
  in(file("xb3")).settings(commonSettings).settings(
    name := "dtrace-xb3",
    libraryDependencies += ("org.scodec" %% "scodec-bits" % "1.1.7")
  )

lazy val xb3JVM = xb3.jvm.enablePlugins(SbtOsgi).settings(
  buildOsgiBundle("com.ccadllc.cedi.dtrace.interop.xb3")
).dependsOn(coreJVM % "compile->compile;test->test")

lazy val xb3JS = xb3.js.dependsOn(coreJS % "compile->compile;test->test")

lazy val money = crossProject(JVMPlatform, JSPlatform).
  in(file("money")).settings(commonSettings).settings(name := "dtrace-money")

lazy val moneyJVM = money.jvm.enablePlugins(SbtOsgi).settings(
  buildOsgiBundle("com.ccadllc.cedi.dtrace.interop.money")
).dependsOn(coreJVM % "compile->compile;test->test")

lazy val moneyJS = money.js.dependsOn(coreJS % "compile->compile;test->test")

lazy val http4s = project.in(file("http4s")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "dtrace-http4s",
    // TODO: This is only temporary until http4s publishes for 2.13
    // Replace this libDependencies and the two skips with just a
    // libDeps for the two http4s libs
    libraryDependencies := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) if v >= 13 => Seq.empty
      case _ => libraryDependencies.value ++ Seq(
        "org.http4s" %% "http4s-core" % http4sVersion,
        "org.http4s" %% "http4s-dsl" % http4sVersion % "test"
      )
    }),
    skip in compile := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) => v >= 13
      case _ => false
    }),
    skip in publish := (CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, v)) => v >= 13
      case _ => false
    }),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.interop.http4s")
  ).dependsOn(coreJVM % "compile->compile;test->test", moneyJVM % "compile->test", xb3JVM % "compile->test")

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(coreJVM, loggingJVM)
