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

import scala.language.higherKinds

import io.circe._
import io.circe.syntax._

/**
 * Provides the encoding of a `Span` - and associated `TraceSystem[F]` data - to JSON
 * using the `io.circe` framework.
 */
object json {
  object encoding {

    /**
     * Provides an implicit `io.circe.Encoder[TraceContext[F]]`, used by the [[LogEmitter]] to render
     * the `TraceCOntext[F]` as JSON.
     */
    // format: OFF
    implicit def traceContextEncoderJson[F[_]]: Encoder[TraceContext[F]] = Encoder.instance { tc =>
      Json.obj(
        "where"           -> tc.system.data.combinedStrMap.asJson,
        "root"            -> tc.currentSpan.root.asJson,
        "trace-id"        -> tc.currentSpan.spanId.traceId.asJson,
        "span-id"         -> tc.currentSpan.spanId.spanId.asJson,
        "parent-id"       -> tc.currentSpan.spanId.parentSpanId.asJson,
        "span-name"       -> tc.currentSpan.spanName.value.asJson,
        "start-time"      -> tc.currentSpan.startTime.asJson,
        "span-success"    -> tc.currentSpan.failure.isEmpty.asJson,
        "failure-detail"  -> tc.currentSpan.failure.map(_.render).asJson,
        "span-duration"   -> tc.currentSpan.duration.toMicros.asJson,
        "notes"           -> tc.currentSpan.notes.map { n => n.name.value -> n.value }.collect { case (name, Some(value)) => name -> value.toString }.toMap.asJson
      )
    }
    // format: ON
  }
}
