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

import cats.data.OptionT
import cats.implicits._

import java.util.UUID

import scodec.bits.ByteVector

import XB3HeaderCodec._

/**
 * Implements the `HeaderCodec` trait, providing for the encoding and decoding of
 * `X-B3`-style tracing HTTP headers into and from a `SpanId` respectively.
 */
class XB3HeaderCodec extends HeaderCodec {

  override def encode(spanId: SpanId): List[Header] = {
    val traceIdH = List(Header(TraceIdHeaderName, encodeTraceId(spanId.traceId)))
    val parentIdH = if (spanId.root) List.empty[Header] else List(Header(ParentIdHeaderName, encodeSpanId(spanId.parentSpanId)))
    val spanIdH = List(Header(SpanIdHeaderName, encodeSpanId(spanId.spanId)))
    traceIdH ++ parentIdH ++ spanIdH
  }

  override def decode(headers: List[Header]): Either[Header.DecodeFailure, Option[SpanId]] = (for {
    traceId <- OptionT(headers.collectFirst { case Header(TraceIdHeaderName, value) => value }.traverse { decodeTraceId })
    parentIdMaybe <- OptionT.liftF(headers.collectFirst { case Header(ParentIdHeaderName, value) => value }.traverse { decodeSpanId })
    spanId <- OptionT(headers.collectFirst { case Header(SpanIdHeaderName, value) => value }.traverse { decodeSpanId })
  } yield SpanId(traceId, parentIdMaybe getOrElse spanId, spanId)).value

  private def encodeSpanId(spanId: Long): Header.Value = Header.Value(ByteVector.fromLong(spanId).toHex)
  private def encodeTraceId(traceId: UUID): Header.Value = Header.Value(ByteVector.fromUUID(traceId).toHex)
  private def decodeSpanId(encoded: Header.Value): Either[Header.DecodeFailure, Long] =
    ByteVector.fromHexDescriptive(encoded.value).map { _.toLong() }.leftMap { Header.DecodeFailure(_, None) }

  private def decodeTraceId(encoded: Header.Value): Either[Header.DecodeFailure, UUID] = for {
    bv <- ByteVector.fromHexDescriptive(encoded.value).leftMap { Header.DecodeFailure(_, None) }
    uuid <- decodeUuid(bv)
  } yield uuid

  private def decodeUuid(bv: ByteVector): Either[Header.DecodeFailure, UUID] = bv.size match {
    case TraceIdLongFormByteSize =>
      Either.catchNonFatal(bv.toUUID).leftMap { t => Header.DecodeFailure(s"The $TraceIdHeaderName value ${bv.toHex} cannot be converted to UUID", Some(t)) }
    case TraceIdShortFormByteSize =>
      Either.right(new UUID(bv.toLong(), 0L))
    case other =>
      Either.left(Header.DecodeFailure(s"The $TraceIdHeaderName must be either $TraceIdShortFormByteSize or $TraceIdLongFormByteSize but was ${bv.toHex}", None))
  }
}

object XB3HeaderCodec {
  /** The `X-B3` compliant Trace ID HTTP header name. */
  final val TraceIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-TraceId")

  /** The `X-B3` compliant Parent Span ID HTTP header name. */
  final val ParentIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-ParentSpanId")

  /** The `X-B3` compliant Span ID HTTP header name. */
  final val SpanIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-SpanId")

  /** The number of bytes (long form) of the Trace ID */
  final val TraceIdLongFormByteSize: Long = 16L

  /** The number of bytes (short form) of the Trace ID.  Note that the short form is not optimal if you want to ensure unique trace IDs */
  final val TraceIdShortFormByteSize: Long = 8L
}

