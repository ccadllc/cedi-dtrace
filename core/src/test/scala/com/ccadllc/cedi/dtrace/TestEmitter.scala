/*
 * Copyright 2018 Combined Conditional Access Development, LLC.
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

import cats.effect.Sync

import scala.language.higherKinds

class TestEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {
  class EmitterTestCache {
    case class EmitterTestEntry(msg: String)
    private var emitterLogCache: Vector[EmitterTestEntry] = Vector.empty
    def put(msg: String): Unit = synchronized { emitterLogCache = emitterLogCache :+ EmitterTestEntry(msg.toLowerCase) }
    def all: Vector[EmitterTestEntry] = emitterLogCache
    def containingAll(substrings: String*): Vector[EmitterTestEntry] = {
      def containing(substrings: Seq[String])(predicate: (EmitterTestEntry, Seq[String]) => Boolean): Vector[EmitterTestEntry] = {
        require(!substrings.isEmpty)
        val lowerSubstrings = substrings map { _.toLowerCase }
        emitterLogCache filter { predicate(_, lowerSubstrings) }
      }
      containing(substrings) { (e, strings) => strings forall { e.msg.contains } }
    }
  }
  val cache = new EmitterTestCache
  override def description: String = "Test Emitter"
  override def emit(tc: TraceContext[F]): F[Unit] = {
    def formatText(context: TraceContext[F]) = {
      s"Span: [ span-id=${context.currentSpan.spanId.spanId} ] [ trace-id=${context.currentSpan.spanId.traceId} ] [ parent-id=${context.currentSpan.spanId.parentSpanId} ] [ span-name=${context.currentSpan.spanName} ] [ system-metadata=${context.system.metadata.mkString(",")} ] [ start-time=${context.currentSpan.startTime} ] [ span-duration=${context.currentSpan.duration} ] [ span-success=${context.currentSpan.failure.isEmpty} ] [ failure-detail=${context.currentSpan.failure.fold("N/A")(_.render)} ][ notes=[${context.currentSpan.notes.mkString("] [")}] ]"
    }
    F.delay(cache.put(formatText(tc)))
  }
}
