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
package interop
package money

import java.util.UUID

import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalacheck.Arbitrary

import MoneyHeaderCodec._

class MoneyHeaderCodecTest extends WordSpec with Matchers with GeneratorDrivenPropertyChecks with TraceGenerators {

  implicit val arbitraryUUID: Arbitrary[UUID] = Arbitrary(genUUID)

  "the Money Header Codec" should {
    "decode correctly given any valid UUID for trace-Id and any valid long integers for parent and span ID" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val header = Header(
          HeaderName,
          Header.Value(s"$TraceIdHeader=$traceIdValue;$ParentIdHeader=$parentSpanIdValue;$SpanIdHeader=$spanIdValue"))
        val errorOrSpanId = headerCodec.decode(List(header))
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), true))
      }
    }
    "encode correctly for any valid UUID for trace-Id and any valid long integers for parent and span ID" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long) =>
        val expectedHeader = Header(
          HeaderName,
          Header.Value(s"$TraceIdHeader=$traceIdValue;$ParentIdHeader=$parentSpanIdValue;$SpanIdHeader=$spanIdValue"))
        val spanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(spanId)
        headers shouldBe List(expectedHeader)
      }
    }
    "round-trip correctly given any valid UUID for trace-Id and any valid long integers for parent and span ID" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(expectedSpanId)
        val errorOrSpanId = headerCodec.decode(headers)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), true))
      }
    }
  }
}
