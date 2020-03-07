4.0.0
=====
 - Support for scalajs 1.0.0 and swapping out log4s with scalajs-logging

3.0.0
=====
 - Support for scala 2.13 and updated libraries (notably using scalatest for
   property testing, and rev cats/cats-effect from 1.x to 2.x).
   Note that we can now move (most) tests into shared and use AsyncFreeSpec, with
   the exception of that which we inherit from
   cats-effect, which is still using scalacheck.  This will be done in
   a subsequent point release as its an implementation detail which does not
   affect the library API.  This version requires and depends on
   cedi-build 1.2.0.
2.0.2
=====
 - Fix Stack Overflow for parTraverse.  Issue #79.
2.0.1
=====
 - Fix issue where TraceSystem timer type gets misconfigured in `F ~> G`.
   Results in nonsensical Span times when using `Parallel` typeclasses.  Issue #75.
2.0.0
=====
 - Added the ability to set a `sampled` indicator in the `TraceContext[F]` and
   to encode/decode the sampled indicator to/from XB3 headers.  If `sampled`
   is set to `false` in the `TraceContext`, the actual emission of `Span`s
   for that trace are skipped.
 - Added an Elastic Common Search (ECS) compliant emitter within the logstash
   module which encodes the spans in a manner consistent with the ECS
   specification (1.0.0-beta2 revision).  The top-level `metadata` map in the
   `TraceSystem` has been replaced with a `data` structure, which separates out
   identity and metadata components of system-wide common data so that the
   `logstash` module's `EcsLogstashLogbackEmitter` can encode identity fields
   directly rather than combining them all into a metadata group.  This isn't
   specific to ECS but allows for more re-use of common ECS fields at the
   top-level.
 - The `core`, `logging`, `xb3`, and `money` modules are now built for
   both the JVM and JavaScript environments. The `logging` module uses
   `io.chrisdavenport.log4cats` to abstract over JVM and JavaScript-specific
   logging frameworks, with the JVM environment continuing to use
   `org.slf4j` over `ch.qos.logback` while the JavaScript environment
   uses `org.log4s.log4s`.
 - Updated typeclasses to use cats-effect 1.0, which includes a number
   of changes, including the `Bracket` typeclass for handling finalization
   and resource cleanup. This typeclass and the `TraceT.bracket` and
   `TraceT.bracketCase` convenience methods take the place of the
   `cedi-dtrace` 1.x `bestEffortOnFinish` function. Also added `ContextShift`
   typeclass to handle shifting to a new thread pool with `shift` and to
   temporarily evaluate an effect on a thread pool and then shift back to the
   current one via `evalOn` (the use case for the latter is primarily to evalute
   a blocking effect on a dedicated thread pool to avoid possible deadlocks).
 - Removed requirement for a `TraceContext[F]` in implicit scope for
   `Concurrent` typeclass.
 - Fixed stack safety issues with TraceT instances. Previously, it was possible
   to get stack overflow exceptions with deeply nested `flatMap`, `map` and
   `attempt` invocations.
 - Added law testing of typeclass instances.
1.4.0
=====
 - Guard marker creation if debug is disabled in LogstashLogbackEmitter.
1.3.0
=====
 - Implement `ConcurrentEffect` and `Timer` typeclass
 - Fixed issue with `Effect` typeclass `runAsync` function implementation
   causing loss of trace context.
 - Added money, xb3, and http4s modules, with support for Money,
 - and X-B3 headers and generation and extraction of trace information to/from
 - headers for the http4s library.

1.1.0
=====
 - Upgraded to Circe 0.7.0

1.0.0
=====
 - Initial revision
