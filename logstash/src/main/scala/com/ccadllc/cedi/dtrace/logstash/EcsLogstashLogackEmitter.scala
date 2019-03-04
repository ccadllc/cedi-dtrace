/*
 * Copyright 2019 Combined Conditional Access Development, LLC.
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
package logstash

import cats.effect.Sync
import cats.implicits._

import net.logstash.logback.marker.LogstashMarker
import net.logstash.logback.marker.Markers._

import org.slf4j.LoggerFactory

import scala.language.higherKinds
import scala.collection.JavaConverters._

final class EcsLogstashLogbackEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {
  private val logger = LoggerFactory.getLogger("distributed-trace.ecs.logstash")
  final val description: String = "ECS-Compliant Logstash Logback Emitter"
  final def emit(tc: TraceContext[F]): F[Unit] = F.delay {
    if (logger.isDebugEnabled) {
      val s = tc.currentSpan
      val marker: LogstashMarker = {
        val m = append("dtrace.root", s.root).
          and[LogstashMarker](append("dtrace.trace-id", s.spanId.traceId.toString)).
          and[LogstashMarker](append("dtrace.span-id", s.spanId.spanId)).
          and[LogstashMarker](append("dtrace.parent-id", s.spanId.parentSpanId)).
          and[LogstashMarker](append("dtrace.span-name", s.spanName.value)).
          and[LogstashMarker](append("dtrace.start-time", s.startTime.show)).
          and[LogstashMarker](append("dtrace.span-success", s.failure.isEmpty)).
          and[LogstashMarker](append("dtrace.failure-detail", s.failure.map(_.render).orNull)).
          and[LogstashMarker](append("span-duration", s.duration.toNanos)).
          and[LogstashMarker](append("labels", tc.system.data.metaStrMap.asJava))
        tc.system.data.identityStrMap.foldLeft(m) { case (acc, (k, v)) => acc.and[LogstashMarker](append(k, v)) }
      }
      logger.debug(marker, "Span {} {} after {} microseconds",
        s.spanName.value,
        if (s.failure.isEmpty) "succeeded" else "failed",
        s.duration.toMicros.toString)
    }
  }
}

