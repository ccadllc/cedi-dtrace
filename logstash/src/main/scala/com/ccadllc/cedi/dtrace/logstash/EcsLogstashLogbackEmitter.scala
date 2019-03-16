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

/**
 * This `TraceSystem.Emitter[F]` will log `Span`s in a format that is compliant with
 * the [[https://github.com/elastic/ecs Elastic Common Schema (ECS)]] using the `logstash`
 * encoder.  This emitter utilizes pre-defined ECS fields and field groups where available
 * and appropriate and, where custom fields are used, follows the conventions of ECS
 * (e.g., field names using '_').  Currently only the `dtrace.trace_id` and `dtrace.parent_id`
 * are custom fields with all others mapped to those of ECS (primarily in the `event` field group
 * with the exception of `error_message` used for error detail and `labels` which hold the
 * `Note` and `TraceSystem.Data` identity and metadata properties.
 */
final class EcsLogstashLogbackEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {
  object ecs {
    object field {
      val kind: String = "event.kind"
      val module: String = "event.module"
      val root: String = "dtrace.root"
      val traceId: String = "dtrace.trace_id"
      val parentId: String = "dtrace.parent_id"
      val spanId: String = "event.id"
      val spanName: String = "event.action"
      val spanStart: String = "event.start"
      val spanOutcome: String = "event.outcome"
      val spanDuration: String = "event.duration"
      val spanFailureDetail: String = "error.message"
      val spanMetadata: String = "labels"
    }
  }
  private val logger = LoggerFactory.getLogger("distributed-trace.ecs.logstash")
  final val description: String = "ECS-Compliant Logstash Logback Emitter"
  final def emit(tc: TraceContext[F]): F[Unit] = F.delay {
    if (logger.isDebugEnabled) {
      val s = tc.currentSpan
      val marker: LogstashMarker = {
        val m = append(ecs.field.kind, "event").
          and[LogstashMarker](append(ecs.field.module, "dtrace")).
          and[LogstashMarker](append(ecs.field.root, s.root)).
          and[LogstashMarker](append(ecs.field.traceId, s.spanId.traceId.toString)).
          and[LogstashMarker](append(ecs.field.parentId, s.spanId.parentSpanId)).
          and[LogstashMarker](append(ecs.field.spanId, s.spanId.spanId)).
          and[LogstashMarker](append(ecs.field.spanName, s.spanName.value)).
          and[LogstashMarker](append(ecs.field.spanStart, s.startTime.show)).
          and[LogstashMarker](append(ecs.field.spanOutcome, if (s.failure.isEmpty) "success" else "failure")).
          and[LogstashMarker](append(ecs.field.spanDuration, s.duration.toNanos)).
          and[LogstashMarker](append(ecs.field.spanFailureDetail, s.failure.map(_.render).orNull)).
          and[LogstashMarker](append(
            ecs.field.spanMetadata,
            (tc.system.data.meta.values ++ s.notes.map(
              n => n.name.value -> n.value).collect { case (name, Some(value)) => name -> value.toString }.toMap).asJava))
        tc.system.data.identity.values.foldLeft(m) { case (acc, (k, v)) => acc.and[LogstashMarker](append(k, v)) }
      }
      logger.debug(marker, "Span {} {} after {} microseconds",
        s.spanName.value,
        if (s.failure.isEmpty) "succeeded" else "failed",
        s.duration.toMicros.toString)
    }
  }
}

