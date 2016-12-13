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

import fs2.util.Suspendable
import fs2.util.syntax._

import io.circe.syntax._

import org.slf4j.LoggerFactory

import scala.language.higherKinds

class LogEmitter[F[_]](implicit F: Suspendable[F]) extends TraceSystem.Emitter[F] {

  private val textLogger = LoggerFactory.getLogger("distributed-trace.txt")
  private val jsonLogger = LoggerFactory.getLogger("distributed-trace.json")

  override def emit(context: TraceContext[F]): F[Unit] = {
    def emitText: F[Unit] = {
      def formatText = s"Span: [ span-id=${context.currentSpan.spanId.spanId} ] [ trace-id=${context.currentSpan.spanId.traceId} ] [ parent-id=${context.currentSpan.spanId.parentSpanId} ] [ root=${context.currentSpan.root} ] [ span-name=${context.currentSpan.spanName} ] [ app-name=${context.system.identity.app.name} ] [ start-time=${context.currentSpan.startTime} ] [ span-duration=${context.currentSpan.duration} ] [ span-success=${context.currentSpan.failure.isEmpty} ] [ failure-detail=${context.currentSpan.failure.fold("N/A")(_.render)} ][ notes=[${context.currentSpan.notes.mkString("] [")}] ] [ node-name=${context.system.identity.node.name} ]"
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

object LogEmitter {
  def apply[F[_]: Suspendable]: LogEmitter[F] = new LogEmitter[F]
}
