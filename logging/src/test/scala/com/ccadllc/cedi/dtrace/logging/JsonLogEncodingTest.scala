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

import fs2.Task
import fs2.util.Suspendable

import io.circe._
import io.circe.java8.time._
import io.circe.syntax._

import org.scalacheck.Arbitrary

import org.scalatest.WordSpec

import json.encoding._

class JsonLogEncodingTests extends WordSpec with TestSupport {

  // format: OFF
  val rfreshAuthsTraceContextJson = Json.obj(
    "where" -> Json.obj(
      "appId"           -> refreshAuthsTraceContext.system.identity.app.id.toString.asJson,
      "productKey"      -> refreshAuthsTraceContext.system.identity.app.name.toString.asJson,
      "processId"       -> refreshAuthsTraceContext.system.identity.process.id.toString.asJson,
      "nodeId"          -> refreshAuthsTraceContext.system.identity.node.id.toString.asJson,
      "nodeName"        -> refreshAuthsTraceContext.system.identity.node.name.asJson,
      "deploymentName"  -> refreshAuthsTraceContext.system.identity.deployment.name.asJson,
      "environmentName" -> refreshAuthsTraceContext.system.identity.environment.name.asJson
    ),
    "root"            -> refreshAuthsTraceContext.currentSpan.root.asJson,
    "trace-id"        -> refreshAuthsTraceContext.currentSpan.spanId.traceId.asJson,
    "span-id"         -> refreshAuthsTraceContext.currentSpan.spanId.spanId.asJson,
    "parent-id"       -> refreshAuthsTraceContext.currentSpan.spanId.parentSpanId.asJson,
    "span-name"       -> refreshAuthsTraceContext.currentSpan.spanName.value.asJson,
    "start-time"      -> refreshAuthsTraceContext.currentSpan.startTime.asJson,
    "span-success"    -> refreshAuthsTraceContext.currentSpan.failure.isEmpty.asJson,
    "failure-detail"  -> refreshAuthsTraceContext.currentSpan.failure.map(_.render).asJson,
    "span-duration"   -> refreshAuthsTraceContext.currentSpan.duration.toMicros.asJson,
    "notes"           -> Map(
      updateEmmsLongNote.name.value     ->  updateEmmsLongNoteValue.value.toString,
      updateEmmsBooleanNote.name.value  ->  updateEmmsBooleanNoteValue.value.toString,
      updateEmmsStringNote.name.value   ->  updateEmmsStringNoteValue.value,
      updateEmmsDoubleNote.name.value   ->  updateEmmsDoubleNoteValue.value.toString
    ).asJson
  )
  // format: ON

  implicit def arbTrace[F[_]: Suspendable]: Arbitrary[TraceContext[F]] = Arbitrary(genTraceContext)

  "Trace" should { encodeArbitraryJson[TraceContext[Task]] }
  "Trace" should { encodeSpecificJson(refreshAuthsTraceContext, rfreshAuthsTraceContextJson) }
}
