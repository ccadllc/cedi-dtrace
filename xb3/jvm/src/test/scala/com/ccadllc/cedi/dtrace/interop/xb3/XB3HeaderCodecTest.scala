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
package xb3

import java.util.UUID

import org.scalatest.Matchers
import org.scalatest.prop.{ Generator, GeneratorDrivenPropertyChecks }
import org.scalatest.wordspec.AnyWordSpec

import scodec.bits.ByteVector

import XB3HeaderCodec._

class XB3HeaderCodecTest extends AnyWordSpec with Matchers with GeneratorDrivenPropertyChecks with TraceGenerators {

  implicit val uuidGenerator: Generator[UUID] = genUUID

  "the X-B3 Header Codec" should {
    "decode correctly given any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val traceIdHeader = Header(TraceIdHeaderName, Header.Value(ByteVector.fromUUID(traceIdValue).toHex))
        val parentIdHeader = Header(ParentIdHeaderName, Header.Value(ByteVector.fromLong(parentSpanIdValue).toHex))
        val spanIdHeader = Header(SpanIdHeaderName, Header.Value(ByteVector.fromLong(spanIdValue).toHex))
        val sampledHeader = Header(SampledHeaderName, Header.Value(encodeSampledValue(sampled)))
        val errorOrSpanId = headerCodec.decode(List(traceIdHeader, parentIdHeader, spanIdHeader, sampledHeader))
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "decode correctly for compressed header given any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val nonRootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(traceIdValue).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}-${ByteVector.fromLong(parentSpanIdValue).toHex}")))
        val rootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(traceIdValue).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}")))
        val errorOrSpanId = headerCodec.decode(if (parentSpanIdValue === spanIdValue) rootHeaders else nonRootHeaders)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "decode correctly given any valid long for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: Long, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(new UUID(traceIdValue, 0L), parentSpanIdValue, spanIdValue)
        val traceIdHeader = Header(TraceIdHeaderName, Header.Value(ByteVector.fromLong(traceIdValue).toHex))
        val parentIdHeader = Header(ParentIdHeaderName, Header.Value(ByteVector.fromLong(parentSpanIdValue).toHex))
        val spanIdHeader = Header(SpanIdHeaderName, Header.Value(ByteVector.fromLong(spanIdValue).toHex))
        val sampledHeader = Header(SampledHeaderName, Header.Value(encodeSampledValue(sampled)))
        val errorOrSpanId = headerCodec.decode(List(traceIdHeader, parentIdHeader, spanIdHeader, sampledHeader))
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "decode correctly for compressed header given any valid long for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: Long, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(new UUID(traceIdValue, 0L), parentSpanIdValue, spanIdValue)
        val nonRootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(expectedSpanId.traceId).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}-${ByteVector.fromLong(parentSpanIdValue).toHex}")))
        val rootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(expectedSpanId.traceId).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}")))
        val errorOrSpanId = headerCodec.decode(if (parentSpanIdValue === spanIdValue) rootHeaders else nonRootHeaders)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "encode correctly for any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean, where if parent == span id, parent will not be emitted" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedNonRootHeaders = List(
          Header(TraceIdHeaderName, Header.Value(ByteVector.fromUUID(traceIdValue).toHex)),
          Header(ParentIdHeaderName, Header.Value(ByteVector.fromLong(parentSpanIdValue).toHex)),
          Header(SpanIdHeaderName, Header.Value(ByteVector.fromLong(spanIdValue).toHex)),
          Header(SampledHeaderName, Header.Value(encodeSampledValue(sampled))))
        val expectedRootHeaders = List(
          Header(TraceIdHeaderName, Header.Value(ByteVector.fromUUID(traceIdValue).toHex)),
          Header(SpanIdHeaderName, Header.Value(ByteVector.fromLong(spanIdValue).toHex)),
          Header(SampledHeaderName, Header.Value(encodeSampledValue(sampled))))
        val spanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(spanId, Map(XB3HeaderCodec.Sampled -> sampled.toString))
        val expectedHeaders = if (spanId.root) expectedRootHeaders else expectedNonRootHeaders
        headers shouldBe expectedHeaders
      }
    }
    "encode correctly for compressed header for any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean, where if parent == span id, parent will not be emitted" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedNonRootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(traceIdValue).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}-${ByteVector.fromLong(parentSpanIdValue).toHex}")))
        val expectedRootHeaders = List(
          Header(
            CompressedHeaderName,
            Header.Value(s"${ByteVector.fromUUID(traceIdValue).toHex}-${ByteVector.fromLong(spanIdValue).toHex}-${encodeSampledValue(sampled)}")))
        val spanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(
          spanId,
          Map(XB3HeaderCodec.Compressed -> "true", XB3HeaderCodec.Sampled -> sampled.toString))
        val expectedHeaders = if (spanId.root) expectedRootHeaders else expectedNonRootHeaders
        headers shouldBe expectedHeaders
      }
    }
    "round-trip correctly given any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(
          expectedSpanId,
          Map(XB3HeaderCodec.Sampled -> sampled.toString, XB3HeaderCodec.Sampled -> sampled.toString))
        val errorOrSpanId = headerCodec.decode(headers)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "round-trip correctly for compressed header given any valid UUID for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: UUID, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(traceIdValue, parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(
          expectedSpanId,
          Map(XB3HeaderCodec.Compressed -> "true", XB3HeaderCodec.Sampled -> sampled.toString))
        val errorOrSpanId = headerCodec.decode(headers)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "round-trip correctly given any valid long for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: Long, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(new UUID(traceIdValue, 0L), parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(expectedSpanId, Map(XB3HeaderCodec.Sampled -> sampled.toString))
        val errorOrSpanId = headerCodec.decode(headers)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
    "round-trip correctly for compressed header given any valid long for trace-Id and any valid long integers for parent and span ID and sampled boolean" in {
      forAll { (traceIdValue: Long, parentSpanIdValue: Long, spanIdValue: Long, sampled: Boolean) =>
        val expectedSpanId = SpanId(new UUID(traceIdValue, 0L), parentSpanIdValue, spanIdValue)
        val headers = headerCodec.encode(
          expectedSpanId,
          Map(XB3HeaderCodec.Compressed -> "true", XB3HeaderCodec.Sampled -> sampled.toString))
        val errorOrSpanId = headerCodec.decode(headers)
        errorOrSpanId shouldBe Right(Header.Decoded(Some(expectedSpanId), sampled))
      }
    }
  }
  private def encodeSampledValue(sampled: Boolean): String = if (sampled) "1" else "0"
}
