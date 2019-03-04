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

import cats.effect.{ IO, Effect }

import io.circe._
import io.circe.syntax._

import org.scalacheck.Arbitrary

import org.scalatest.WordSpec

import json.encoding._

class JsonLogEncodingTests extends WordSpec with TestSupport {

  // format: OFF
  val calculateQuarterlySalesTraceContextJson = Json.obj(
    "where"           -> calculateQuarterlySalesTraceContext.system.data.combinedStrMap.asJson,
    "root"            -> calculateQuarterlySalesTraceContext.currentSpan.root.asJson,
    "trace-id"        -> calculateQuarterlySalesTraceContext.currentSpan.spanId.traceId.asJson,
    "span-id"         -> calculateQuarterlySalesTraceContext.currentSpan.spanId.spanId.asJson,
    "parent-id"       -> calculateQuarterlySalesTraceContext.currentSpan.spanId.parentSpanId.asJson,
    "span-name"       -> calculateQuarterlySalesTraceContext.currentSpan.spanName.value.asJson,
    "start-time"      -> calculateQuarterlySalesTraceContext.currentSpan.startTime.asJson,
    "span-success"    -> calculateQuarterlySalesTraceContext.currentSpan.failure.isEmpty.asJson,
    "failure-detail"  -> calculateQuarterlySalesTraceContext.currentSpan.failure.map(_.render).asJson,
    "span-duration"   -> calculateQuarterlySalesTraceContext.currentSpan.duration.toMicros.asJson,
    "notes"           -> Map(
      quarterlySalesUnitsNote.name.value        ->  quarterlySalesUnitsNoteValue.value.toString,
      quarterlySalesGoalReachedNote.name.value  ->  quarterlySalesGoalReachedNoteValue.value.toString,
      salesRegionNote.name.value                ->  salesRegionNoteValue.value,
      quarterlySalesTotalNote.name.value        ->  quarterlySalesTotalNoteValue.value.toString
    ).asJson
  )
  // format: ON

  implicit def arbTrace[F[_]: Effect]: Arbitrary[TraceContext[F]] = Arbitrary(genTraceContext[F])

  "Trace" should { encodeArbitraryJson[TraceContext[IO]] }
  "Trace" should { encodeSpecificJson(calculateQuarterlySalesTraceContext, calculateQuarterlySalesTraceContextJson) }
}
