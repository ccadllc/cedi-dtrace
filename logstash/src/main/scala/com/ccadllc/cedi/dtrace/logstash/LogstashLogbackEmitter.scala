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
package logstash

import cats.effect.Sync
import cats.implicits._
import net.logstash.logback.marker.LogstashMarker
import net.logstash.logback.marker.Markers._
import org.slf4j.LoggerFactory
import scala.language.higherKinds
import scala.collection.JavaConverters._

final class LogstashLogbackEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {
  private val logger = LoggerFactory.getLogger("distributed-trace.logstash")

  final val description: String = "Logstash Logback Emitter"
  final def emit(tc: TraceContext[F]): F[Unit] = F.delay {
    if (logger.isDebugEnabled) {
      val s = tc.currentSpan
      val marker: LogstashMarker =
        append("where", tc.system.data.allValues.asJava).
          and[LogstashMarker](append("root", s.root)).
          and[LogstashMarker](append("trace-id", s.spanId.traceId.toString)).
          and[LogstashMarker](append("span-id", s.spanId.spanId)).
          and[LogstashMarker](append("parent-id", s.spanId.parentSpanId)).
          and[LogstashMarker](append("span-name", s.spanName.value)).
          and[LogstashMarker](append("start-time", s.startTime.show)).
          and[LogstashMarker](append("span-success", s.failure.isEmpty)).
          and[LogstashMarker](append("failure-detail", s.failure.map(_.render).orNull)).
          and[LogstashMarker](append("span-duration", s.duration.toMicros)).
          and[LogstashMarker](append("notes", s.notes.map(n => n.name.value -> n.value).collect { case (name, Some(value)) => name -> value.toString }.toMap.asJava))
      logger.debug(marker, "Span {} {} after {} microseconds",
        s.spanName.value,
        if (s.failure.isEmpty) "succeeded" else "failed",
        s.duration.toMicros.toString)
    }
  }
}

