import sbtcrossproject.crossProject

lazy val catsEffectVersion = "2.0.0"

lazy val catsCoreVersion = "2.1.0"

lazy val circeVersion = "0.12.3"

lazy val http4sVersion = "0.21.0-M6"

lazy val kindProjectorVersion = "0.10.3"

lazy val logbackVersion = "1.2.3"

lazy val logstashVersion = "6.3"

lazy val scalacheckVersion = "1.14.3"

lazy val scalatestVersion = "3.1.0"

lazy val scalatestDisciplineVersion = "1.0.0"

lazy val scodecBitsVersion = "1.1.12"

lazy val slf4jVersion = "1.7.30"

lazy val log4catsVersion = "1.0.1"

lazy val log4sVersion = "1.8.2"

lazy val commonSettings = Seq(
  githubProject := "cedi-dtrace",
  parallelExecution in Global := !scala.util.Properties.propIsSet("disableParallel"),
  crossScalaVersions := Seq("2.13.1", "2.12.10"),
  scalacOptions --= Seq("-Ywarn-unused-import", "-Xfuture"),
  scalacOptions ++= Seq("-language:higherKinds") ++ (CrossVersion.partialVersion(scalaBinaryVersion.value) match {
     case Some((2, v)) if v <= 12 => Seq("-Xfuture", "-Ywarn-unused-import", "-Ypartial-unification", "-Yno-adapted-args")
     case _ => Seq.empty
  }),
  scalacOptions in (Compile, console) ~= (_ filterNot Set("-Xfatal-warnings", "-Ywarn-unused-import").contains),
  contributors ++= Seq(
    Contributor("sbuzzard", "Steve Buzzard"),
    Contributor("mpilquist", "Michael Pilquist")
  ),
  libraryDependencies ++= Seq(
    "org.typelevel" %% "cats-core" % catsCoreVersion,
    "org.typelevel" %% "cats-effect" % catsEffectVersion,
    "org.typelevel" %% "discipline-scalatest" % scalatestDisciplineVersion % "test",
    "org.scalatest" %% "scalatest" % scalatestVersion % "test",
    "org.scalacheck" %% "scalacheck" % scalacheckVersion % "test"
  ),
  addCompilerPlugin("org.typelevel" % "kind-projector" % kindProjectorVersion cross CrossVersion.binary),
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
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "io.chrisdavenport" %% "log4cats-core" % log4catsVersion,
      "io.chrisdavenport" %% "log4cats-slf4j" % log4catsVersion,
      "ch.qos.logback" % "logback-core" % logbackVersion % "test",
      "ch.qos.logback" % "logback-classic" % logbackVersion % "test",
      "net.logstash.logback" % "logstash-logback-encoder" % logstashVersion % "optional"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logging")
  ).dependsOn(coreJVM % "compile->compile;test->test")

lazy val loggingJS = logging.js.settings(
  libraryDependencies ++= Seq(
    "io.chrisdavenport" %%% "log4cats-core" % log4catsVersion,
    "org.log4s" %%% "log4s" % log4sVersion
  )
).dependsOn(coreJS % "compile->compile;test->test")

lazy val logstash = project.in(file("logstash")).enablePlugins(SbtOsgi).
  settings(commonSettings).
  settings(
    name := "dtrace-logstash",
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.slf4j" % "slf4j-api" % slf4jVersion,
      "net.logstash.logback" % "logstash-logback-encoder" % logstashVersion,
      "ch.qos.logback" % "logback-core" % logbackVersion % "test",
      "ch.qos.logback" % "logback-classic" % logbackVersion % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.logstash")
  ).dependsOn(coreJVM % "compile->compile;test->test")

lazy val xb3 = crossProject(JVMPlatform, JSPlatform).
  in(file("xb3")).settings(commonSettings).settings(
    name := "dtrace-xb3",
    libraryDependencies += ("org.scodec" %% "scodec-bits" % scodecBitsVersion)
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
    parallelExecution in Test := false,
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % http4sVersion,
      "org.http4s" %% "http4s-dsl" % http4sVersion % "test"
    ),
    buildOsgiBundle("com.ccadllc.cedi.dtrace.interop.http4s")
  ).dependsOn(coreJVM % "compile->compile;test->test", moneyJVM % "compile->test", xb3JVM % "compile->test")

lazy val readme = project.in(file("readme")).settings(commonSettings).settings(noPublish).enablePlugins(TutPlugin).settings(
  tutTargetDirectory := baseDirectory.value / ".."
).dependsOn(coreJVM, loggingJVM)
