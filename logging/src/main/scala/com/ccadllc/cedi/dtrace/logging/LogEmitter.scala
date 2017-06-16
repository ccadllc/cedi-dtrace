/*
 * Copyright 2016 Combined Conditional Access Development, LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ccadllc.cedi.dtrace
package logging

import cats.effect.Sync
import cats.implicits._

import io.circe.syntax._

import org.slf4j.LoggerFactory

import scala.language.higherKinds

/**
 * This instance of the `TraceSystem.Emitter` provides the means to
 * record a `Span` to an `slf4j` log appender in both text and JSON
 * formats, also logging the associated system properties provided in
 * the passed-in `TraceContext[F]`.  The recording is described in the
 * context of an effectful program `F` with an `Sync[F]`
 * instance in implicit scope and actual occurs when that program is
 * executed.  Whether the information is recorded to log files, some
 * other location, or not at all depends on the configuration of the
 * `distributed-trace.txt` and `distributed-trace.json` loggers.  Note that
 * the logging occurs only if `DEBUG` is enabled for the particular logger
 * in the underlying logging configuration.
 */
class LogEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {

  private val textLogger = LoggerFactory.getLogger("distributed-trace.txt")
  private val jsonLogger = LoggerFactory.getLogger("distributed-trace.json")

  /**
   * Emit the `Span` and system properties contained in the passed-in `TraceContext[F]`
   * to an `slf4j` log appender, in JSON and textual format.  The emission/recording is described
   * in the returned effectful program `F` and occurs when that program is run.  The JSON is rendered
   * by the implicit `io.circe.Encoder[TraceContext[F]]` provided via the [[json.encoding]] object.
   * @param context - the `TraceContext[F]` containing the `Span` and `TraceSystem` containing
   *   system properties to include in the emission.
   * @return loggingProgram - an effectful program `F[Unit]` that when run will record the trace
   *   information to the sf4j logging system.
   */
  override def emit(context: TraceContext[F]): F[Unit] = {
    def emitText: F[Unit] = {
      def formatText = s"Span: [ span-id=${context.currentSpan.spanId.spanId} ] [ trace-id=${context.currentSpan.spanId.traceId} ] [ parent-id=${context.currentSpan.spanId.parentSpanId} ] [ root=${context.currentSpan.root} ] [ span-name=${context.currentSpan.spanName} ] [ system-metadata=${context.system.metadata.mkString(",")} ] [ start-time=${context.currentSpan.startTime} ] [ span-duration=${context.currentSpan.duration} ] [ span-success=${context.currentSpan.failure.isEmpty} ] [ failure-detail=${context.currentSpan.failure.fold("N/A")(_.render)} ][ notes=[${context.currentSpan.notes.mkString("] [")}] ]"
      F.delay(if (textLogger.isDebugEnabled) textLogger.debug(formatText))
    }
    def emitJson: F[Unit] = {
      import json.encoding._
      F.delay(if (jsonLogger.isDebugEnabled) jsonLogger.debug(context.asJson.noSpaces))
    }
    for {
      _ <- emitJson
      _ <- emitText
    } yield ()
  }
  override val description: String = "SLF4J Log Emitter"
}

/**
 * Companion object for the `LogEmitter` instance, providing a convenience constructor.
 */
object LogEmitter {
  /**
   * Constructs an instance of `LogEmitter` if an instance of `Sync[F]` is
   * available in implicit scope.
   * @return a new instance of `LogEmitter[F]`.
   */
  def apply[F[_]: Sync]: LogEmitter[F] = new LogEmitter[F]
}
