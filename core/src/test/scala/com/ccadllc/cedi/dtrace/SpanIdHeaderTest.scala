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

import java.util.UUID

import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.{ Arbitrary, Gen }

class SpanIdHeaderTest extends WordSpec with Matchers with GeneratorDrivenPropertyChecks {
  private val genUUID: Gen[UUID] = for {
    hi <- Arbitrary.arbitrary[Long]
    lo <- Arbitrary.arbitrary[Long]
  } yield new UUID(hi, lo)

  private implicit val arbitraryUUID: Arbitrary[UUID] = Arbitrary(genUUID)

  "the Span ID header" should {
    "generate correctly given any valid UUID for trace-Id and any valid long integers for parent and span ID" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val header = s"${SpanId.TraceIdHeader}=$traceIdValue;${SpanId.ParentIdHeader}=$parentSpanIdValue;${SpanId.SpanIdHeader}=$spanIdValue"
        val spanId = SpanId.fromHeaderValue(header)
        spanId shouldBe Right(expectedSpanId)
      }
    }
    "parse correctly for any valid UUID for trace-Id and any valid long integers for parent and span ID" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long) =>
        val expectedHeader = s"${SpanId.TraceIdHeader}=$traceIdValue;${SpanId.ParentIdHeader}=$parentSpanIdValue;${SpanId.SpanIdHeader}=$spanIdValue"
        val spanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val header = spanId.toHeader
        header shouldBe expectedHeader
      }
    }
  }
}
