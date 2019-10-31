# Cedi Distributed Trace

Quick links:

- [About the library](#about)
- [Examples of use](#usage)
- [Configuration](#config)
- [How to get latest version](#getit)
- API Docs [Core](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-core_2.12/3.0.0/dtrace-core_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/index.html) [Logging](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-logging_2.12/3.0.0/dtrace-logging_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/logging/index.html) [Logstash](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-logstash_2.12/3.0.0/dtrace-logstash_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/logstash/index.html) [Money](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-money_2.12/3.0.0/dtrace-money_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/money/index.html) [Http4s](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-http4s_2.12/3.0.0/dtrace-http4s_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/http4s/index.html) [XB3](https://oss.sonatype.org/service/local/repositories/releases/archive/com/ccadllc/cedi/dtrace-xb3_2.12/3.0.0/dtrace-xb3_2.12-3.0.0-javadoc.jar/!/com/ccadllc/cedi/dtrace/xb3/index.html)


### <a id="about"></a>About the library

#### Overview

The Cedi Distributed Trace library provides the capability to instrument effectful programs such that logical traces can be derived and recorded across physical processes and machines.  This instrumentation is expressed in a format that is interoperable with [Comcast Money](https://github.com/Comcast/money) and [X-B3/Zipkin](https://istio.io/docs/tasks/telemetry/distributed-tracing.html), providing modules for generating and parsing compliant HTTP headers from/into `TraceContext`s which in turn are used to record the trace spans in the current program.  Additionally, a module for the use of these headers within [http4s](https://http4s.org)-based clients and servers is also provided.  The library core consists of immutable data structures which represent the instrumentation and an interpreter - `TraceT[F, A]` - which annotates the underlying transaction (represented as an `F[A]` where `F` is the effectful action and `A` is the result type).  `TraceT[F, A]` can be thought of as a function from a `TraceContext` (the cursor into the active trace) to an effectful program whose execution you wish to trace. The effectful program can be any `F` which provides instances of certain `cats.effect` typeclasses in implicit scope (the specific typeclasses required depend on the functionality desired but in general it must at least provide an instance of `cats.effect.Sync` to be useful). Because `cats-effect`'s own `IO` is often used as the prototypical example, this library provides a type alias `TraceIO[A]` for `TraceT[IO, A]` and convenience methods to work with this type alias (the latter included in a `TraceIO` object).

#### Design Constraints

This library is implemented using functional data structures and techniques and is best used by similarly constructed programs.
It is non-blocking with a small footprint and incurs a reasonably low overhead.
No special thread pools or piggybacking on thread locals and the like are employed.
`dtrace` is built on Scala and its core constructs use the [Cats Effect](https://github.com/typelevel/cats-effect) library.
It is interoperable with [Comcast Money](https://github.com/Comcast/money) and [Zipkin/X-B3](https://istio.io/docs/tasks/telemetry/distributed-tracing.html).
Where possible, it targets both the JVM and JavaScript platforms. It does not yet target scala native, though that is on the roadmap.

#### Background

A *Distributed Trace* is a directed graph of *Span*s. A *Span* identifies a branch of the overall *Trace* representing a logical step or action, executing within the local program.  All but the first *Span* in a *Trace* has a Parent *Span* representing the upstream operation which triggered its child.  *Span*s are identified by a unique *Span Identifier* (`SpanId`) along with a parent `SpanId` (and the overall *Distributed Trace* GUID).  A *Trace*'s first *Span* has a parent `SpanId` equal to its own.  Each *Span* also consists of metadata about the action, including whether its action executed successfully or failed (and, if a failure, details on the cause), the duration of the span execution, where the *Span* executed (in which application; on which node; in which process; within what environment, etc), and, optionally, individual `Note`s representing metadata specific to the *Span* (e.g., the `Note` with the *Host Address* of a cable settop box for an action issuing an initialize command to the device).  A logical *Trace* (for example, "issue an initialize to a settop box") might originate from a business system with its transmission *Span* passed in an HTTP header to a microservice running in the cloud which executes *Span*s to query a persistent data store before making a custom RPC call (recorded in yet another *Span*) to a second microservice, passing the current trace information in the RPC context, before that second microservice finally issues the initialize command to the settop, ending the *Trace*.  The *dtrace library* provides several logging `Emitter`s to record the *Span*s, as they are executed, to the configured logging system in JSON and text formats but also provides the means by which custom emitters can be provided.  A custom emitter might persist spans to a graph database, for example.  In addition, the ability to sample traces is provided via the `sampled` property of the `TraceContext`. If the `sampled` property is true, its spans are emitted; if false, the emissions are skipped. We also encode/decode the `X-B3-Sampled` header (or sampled section of compressed header) in order to set or propagate this value (if the header or section is not present, the default is to set `sampled` to true).



### <a id="usage"></a> Examples of Use

```scala
import cats.effect.IO

import java.time.Instant
import java.util.UUID

import org.http4s._
import org.http4s.dsl.io._

import com.ccadllc.cedi.dtrace._
import com.ccadllc.cedi.dtrace.interop.http4s._
import com.ccadllc.cedi.dtrace.logging.LogEmitter

import TraceSystem._

/*
 * Some simple data types for our examples.
 */
final case class Region(name: String)
final case class SalesReport(total: Double, message: String)
final case class SalesFigure(region: String, product: String, units: Int, total: Double)

/*
 * Near the beginning of the universe, create a `TraceSystem` object to
 * hold the top-level information about the program (application and node name,
 * deployment and environment names, etc.). The `TraceSystem.Data` is broken up into
 * two sections: a map of key/value pairs representing system identity and a
 * map of key/value pairs representing general system metadata. This separation is
 * done as a hint to the emitter that the identities be emitted as top-level properties
 * while the metadata be emitted within a nested metadata structure.
 * Emitters are free to ignore this and emit both sets of properties at the top level or
 * both within a metadata group.  The dtrace `logstash` module provides an Elastic Common
 * Schema (ECS)-compliant emitter, `EcsLogstashLogbackEmitter`, which does use this hint,
 * encoding the metadata properties under the `ecs` `labels` field group and the identity
 *  properties at the top-level.
 */
val traceSystem = LogEmitter[IO].map { emitter =>
  TraceSystem(
    data = TraceSystem.Data(
      TraceSystem.Data.Identity(
        Map(
          "application name" -> "sales-management-system",
          "application ID" -> UUID.randomUUID.toString,
          "node name" -> "crm.widgetsforsale.com",
          "node ID" -> UUID.randomUUID.toString
        )
      ),
      TraceSystem.Data.Meta(
        Map(
          "deployment name" -> "us-west-2",
          "environment name" -> "production"
        )
      )
    ),
   /* This emitter will write a text entry for each span to the "distributed-trace.txt"
    * logger and a JSON entry for each span to the "distributed-trace.json" logger; however,
    * it is easy to provide your own emitter by implementing the `TraceSystem.Emitter[F]`
    * trait, which requires providing implementations for two methods:
    *   `def description: String` to provide a description of your emitter and
    *   `def emit(tc: TraceContext[F]): F[Unit]` to actually do the work of
    * emitting the current Span to the destination and in the format of your choosing.
    */
    emitter = emitter,
   /*
    * This time will generate points in time using the `cats.effect.Clock#realTime` function,
    * which is by default the `System.currentTimeMillis`.  It will convert it to MICROSECONDS
    * precision in order to calculate the duration of a `Span` execution.  This is just a
    * convenience function.  There is also a convenience function
    * - `TraceSystem.monotonicTimer[IO]` - for calculating using the `cats.effect.Clock#monotonic`
    *  which by default uses `System.nanoTime` and will use its NANOSECOND precision to calculate
    * the duration of a `Span` execution.  If you'd prefer to use different levels of precision,
    * you can create a timer using `TraceSystem.Timer.realTime[IO](TimeUnit.MILLISECONDS)`, for
    * example.  Note that these levels of precision may be rounded up or down if using
    * `cats.effect.Clock#monotonic`, as it will likely be using `System.currentTimeMillis`
    * which will only provide millisecond level precision (roughly).
    *
    timer = TraceSystem.realTimeTimer[IO]
  )
}

/*
 * Compose the Money and X-B3 HTTP Trace Header encoder/decoder into an aggregate
 * (generating both on encoded and preferring X-B3 on decode).
 */
implicit val headerCodec: HeaderCodec =
   interop.xb3.headerCodec.andThen(interop.money.headerCodec)

val region = Region("Philly")

def retrieveSalesFigures(region: Region): IO[Vector[SalesFigure]] = IO(
  Vector(
    SalesFigure("PA", "widget2000", 200000, 850000.0),
    SalesFigure("NJ", "widget1000", 100000, 550003.50)
  )
)

def calculateSalesReport(figures: Vector[SalesFigure]): IO[SalesReport] =
   IO(SalesReport(figures.map(_.total).sum, "success!"))

def generateSalesReport(region: Region): TraceT[IO, SalesReport] = for {
 /*
  * Calculate the new quarterly sales figure and generate the report.  Note that the import of
  * `com.ccadllc.cedi.dtrace._` enriches the `IO` type by adding a `newSpan`
  * method to it using an implicit class.  The two lines that follow this comment would,
  * without the syntax enrichment, be written as:
  *  figures <- TraceT.toTraceT(retrieveSalesFigures(region).newSpan(
  *    Span.Name("retrieve-sales-figures"),
  *    Note.string("region", region.name)
  *  )
  *  result <- TraceT.toTraceT(calculateSalesReport(figures)).newSpan(
  *    Span.Name("calculate-sales-report"),
  *    Note.string("region", region.name),
  *    Note.long("total-figures", figures.size.toLong)
  *  )
  */
  figures <- retrieveSalesFigures(region).newSpan(
    Span.Name("retrieve-sales-figures"), Note.string("region", region.name)
  )
  report <- calculateSalesReport(figures).newSpan(
    Span.Name("calculate-sales-report"),
    Note.string("region", region.name), Note.long("total-figures", figures.size.toLong)
  )
} yield report


/*
 * We add a Span to the overall `generateSalesReport` action,
 * showing the ability to create Span notes from the traced action result
 * with `newAnnotatedSpan`.
 */
val tracedIO: TraceT[IO, SalesReport] = generateSalesReport(region).newAnnotatedSpan(
  Span.Name("generate-sales-report"), Note.string("region", region.name)
) { case Right(report) =>
  Vector(Note.string("sales-report", report.toString))
}

/*
 * We convert our traced io to an IO for a non-HTTP app (generating a new root trace)
 */
val io: IO[SalesReport] = for {
  ts <- traceSystem
  /*
   * We create a local root Span (we could also extract it from an HTTP header using the
   * `money`, `xb3` and `http4s` modules - see alternate example below)
   */
  rootSpan <- Span.root(ts.timer, Span.Name("locally-initiated-report"))
  /*
   * The `tracedIO` we've derived earlier around `generateSalesReport` (which includes
   * the retrieval and calculate sales figures nested actions, each with their own Spans) is an instance of
   * `TraceT[IO, A]`, which is a data structure associating a Span (like "calculate-sales-figures") with
   * its underlying `IO` (reiterating that we're using `IO` in this example, but again, `IO` can be substituted
   * with any `F`).  When we are done building up these annotated `TraceT` instances, we need to "tie the knot"
   * by converting the top-level instance back into a plain `IO` again before we can actually run it. This is
   * accomplished by applying the root `Span` for this process using the `trace` method on on our top-level
   * `TraceT` instance (represented here by the `tracedIO` value). We set the `sampled` indicator (second
   * argument) to `true`, indicating we wish to emit the spans (the normal case).  This could be set to
   * false if performing sampling and the sampling threshold has not been reached (such a threshold being
   * application specific).
   */
  result <- tracedIO.trace(TraceContext(rootSpan, true, ts))
} yield result

/*
 * Alternate flow: We convert our traced io to an IO for a htt4s app, possibly continuing an existing trace, if
 * we found either X-B3 or Money HTTP headers in the request; otherwise, a new root trace is generated.
 */
val http4sServerAction = HttpService[IO] {
  case request @ GET -> Root / "salesreport" / accountRep =>
    tracedAction(
      request, Span.Name("sales-report"), Note.string("account-rep", accountRep)
    )(tracedIO).flatMap {
      salesReport => Response[IO](status = Status.Ok).withBody(salesReport.asJson)
    }
}

/*
 * Now, at the end of the universe, we run the io program.  This will result, in this example using the supplied
 * logging framework Emitter, in the following items logged via the `distributed-trace.txt` logger:
 *   Span: [ span-id=-4268861818882462019 ] [ trace-id=2a71fb7b-f38d-4f6a-a4d1-229c6c5bc963 ] [ parent-id=-6262761813211462065 ]
 *     [ span-name=retrieve-sales-figures] [ app-name=sales-management-system ] [ start-time=2016-09-26T00:29:14.802Z ]
 *     [ span-duration=2500 microseconds ] [ span-success=true ] [ failure-detail=N/A ][ notes=[name=region,value=Philly] ]
 *     [ node-name=crm.widgetsforsale.com ]
 *
 *   Span: [ span-id=-2264899918882.0.036 ] [ trace-id=2a71fb7b-f38d-4f6a-a4d1-229c6c5bc963 ] [ parent-id=-6262761813211462065 ]
 *     [ span-name=calculate-sales-report] [ app-name=sales-management-system] [ start-time=2016-09-26T00:29:14.799Z ]
 *     [ span-duration=2500 microseconds ] [ span-success=true ] [ failure-detail=N/A ]
 *     [ notes=[name=region,value=Philly], [name=total-figures,value=2] ] [ node-name=crm.widgetsforsale.com ]
 *
 *   Span: [ span-id=-6262761813211462065 ] [ trace-id=2a71fb7b-f38d-4f6a-a4d1-229c6c5bc963 ] [ parent-id=-9466761813211462033 ]
 *     [ span-name=generate-sales-report] [ app-name=sales-management-system ] [ start-time=2016-09-26T00:29:14.797Z ]
 *     [ span-duration=5000 microseconds ] [ span-success=true ] [failure-detail=N/A ]
 *     [ notes=[name=region,value=Philly], [name=report,value=SalesReport(1400000.50, success!)] ]
 *     [ node-name=crm.widgetsforsale.com ]
 *
 *   Span: [ span-id=-9466761813211462033 ] [ trace-id=2a71fb7b-f38d-4f6a-a4d1-229c6c5bc963 ] [ parent-id=2488084092502843745 ]
 *     [ span-name=sales-management-root ] [ app-name=sales-management-system ] [ start-time=2016-09-26T00:29:14.793Z ]
 *     [ span-duration=5110 microseconds ] [ span-success=true ] [ failure-detail=N/A ][ notes=[] ]
 *     [ node-name=crm.widgetsforsale.com ]
 */
io.unsafeRunSync()
```

### <a id="getit"></a>How to get latest Version

Cedi Distributed Trace supports Scala 2.12 and 2.13. This distribution is published to Maven Central and consists of several library components/modules, described below.

#### dtrace-core

This is the core functionality, recording trace and span information over effectful programs, passing these recorded events to registred emitters for disposition. It is published for both the JVM and the JavaScript (JS) platforms.

```scala
libraryDependencies += "com.ccadllc.cedi" %%% "dtrace-core" % "3.0.0"
```

#### dtrace-logging

This component provides emitters for logging the trace spans in text and/or JSON format using the `sf4j` logging framework for the JVM and `log4s` JS logger for the JavaScript (JS) platform.  It uses the `circe` library for formatting the trace span information as JSON.  It uses the `io.chrisdavenport.log4cats` library to abstract the particular platform targeted from the logging API.

```scala
libraryDependencies ++= "com.ccadllc.cedi" %%% "dtrace-logging" % "3.0.0"
```

#### dtrace-logstash

This component provides emitters for logging using the Elastic Search (EC) logstash encoder in both a format that mirrors the standard dtrace format used in the `logging` module described above as well as one which does so in an [Elastic Search Common Schema (ECS)](https://github.com/elastic/ecs)-compliant format.  This module is currently targeted to the JVM only.

```scala
libraryDependencies ++= "com.ccadllc.cedi" %% "dtrace-logstash" % "3.0.0"
```

#### dtrace-money interoperability

This component provides an instance of the core HeaderCodec in order to encode and decode Money-compliant HTTP headers.

```scala
libraryDependencies ++= "com.ccadllc.cedi" %%% "dtrace-money" % "3.0.0"
```

#### dtrace-xb3 interoperability

This component provides an insance of the core HeaderCodec in order to encode and decode X-B3/Zipkin-compliant HTTP headers. The ability to parse and encode Trace ID, Span ID, Parent Span ID and Sampled Flag are provided.

```scala
libraryDependencies ++= "com.ccadllc.cedi" %%% "dtrace-xb3" % "3.0.0"
```

#### dtrace-http4s interoperability

This component provides convenience functions to ingest trace-related HTTP headers (such as Money or X-B3) in an http4s server-side service and to propagate trace-related HTTP headers within an http4s client-side request to a remote entity.  This module is used in combination with either or both the dtrace-xb3 and dtrace-money modules.  If you have another protocol you wish to use instead, it will likewise interoperate with any implementation of the core HeaderCodec trait in implicit scope. This module is currently targeted to the JVM only.

```scala
libraryDependencies ++= "com.ccadllc.cedi" %% "dtrace-http4s" % "3.0.0"
```

## Copyright and License

This project is made available under the [Apache License, Version 2.0](LICENSE). Copyright information can be found in [NOTICE](NOTICE).
