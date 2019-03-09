2.0.0
=====
 - Added an Elastic Common Search (ECS) compliant emitter within the logstash
   module which encodes the spans in a manner consistent with the ECS
   specification (1.0.0-beta2 revision).  The top-level `metadata` map in the
   `TraceSystem` has been replaced with a `data` structure, which separates out
   identity and metadata components of system-wide common data so that the
   `logstash` module's `EcsLogstashLogbackEmitter` can encode identity fields
   directly rather than combining them all into a metadata group.  This isn't
   specific to ECS but allows for more re-use of common ECS fields at the
   top-level.
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
