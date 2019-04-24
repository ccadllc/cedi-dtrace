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

import scala.util.matching.Regex

import scodec.bits.ByteVector

import XB3HeaderCodec._

/**
 * Implements the `HeaderCodec` trait, providing for the encoding and decoding of
 * [[https://istio.io/docs/tasks/telemetry/distributed-tracing.html X-B3]]-style
 * tracing HTTP headers into and from a `SpanId` respectively.
 */
class XB3HeaderCodec extends HeaderCodec {
  /**
   * Encodes [[https://istio.io/docs/tasks/telemetry/distributed-tracing.html X-B3]]-compliant
   * HTTP headers from the passed-in `SpanId`.
   * Note: A single `b3` compressed header will be generated combining the `traceId`, `spanId`
   * and `parentSpanId` when this function is called using the form:
   *  `xb3HeaderCodec.encode(spanId, Map(XB3HeaderCodec.Compressed -> "true"))`
   * Note: The `X-B3-Sampled` Header will be set to "0" if called with the below form (where it might be combined
   * with with `Compressed` as shown above, in which case the sampled section of the compressed header will
   * be set to "0").  Otherwise the `X-B3-Sampled` header (or sampled section of compressed header) will be
   * set to "1"
   *  `xb3HeaderCodec.encode(spanId, Map(XB3HeaderCodec.Sampled -> "false"))`
   */
  override def encode(spanId: SpanId, properties: Map[String, String]): List[Header] = {
    val sampled = sampleTrace(properties)
    if (compressHeaders(properties)) encodeCompressed(spanId, sampled) else encodeUncompressed(spanId, sampled)
  }

  /**
   * Decodes a `SpanId` from
   * [[https://istio.io/docs/tasks/telemetry/distributed-tracing.html XB3/Zipkin]]-compliant
   * HTTP headers (or single compressed `b3` header if present).
   * The `properties` argument is not currently used with this function.
   */
  override def decode(headers: List[Header], properties: Map[String, String]): Either[Header.DecodeFailure, Header.Decoded] =
    decodeCompressed(headers).flatMap(d => if (d.spanId.isEmpty) decodeUncompressed(headers) else Right(d))

  private def compressHeaders(properties: Map[String, String]): Boolean =
    properties.get(Compressed).flatMap(s => Either.catchNonFatal(s.toBoolean).toOption).getOrElse(false)

  private def decodeCompressed(headers: List[Header]): Either[Header.DecodeFailure, Header.Decoded] =
    headers.collectFirst { case Header(CompressedHeaderName, value) => value }.traverse(decodeCompressedSpanId).map(
      _.fold(Header.Decoded(None, true)) { case (spanId, sampled) => Header.Decoded(Some(spanId), sampled) })

  private def decodeCompressedSpanId(encoded: Header.Value): Either[Header.DecodeFailure, (SpanId, Boolean)] = encoded.value match {
    case CompressedHeaderRegex(traceId, spanId, sampled, parentId) =>
      (for {
        traceId <- decodeTraceIdValue(traceId)
        spanId <- decodeSpanIdValue(spanId)
        parentId <- Option(parentId).fold(Either.right[Header.DecodeFailure, Long](spanId))(decodeSpanIdValue)
      } yield SpanId(traceId, parentId, spanId) -> Option(sampled).forall(_ =!= "0")).leftMap(
        e => Header.DecodeFailure(s"${e.message} within ${encoded.value}", e.cause))
    case value => Left(Header.DecodeFailure(s"Could not parse $value into a SpanId", None))
  }

  private def decodeSpanId(encoded: Header.Value): Either[Header.DecodeFailure, Long] =
    decodeSpanIdValue(encoded.value)

  private def decodeSpanIdValue(value: String): Either[Header.DecodeFailure, Long] =
    ByteVector.fromHexDescriptive(value).map(_.toLong()).leftMap(e => Header.DecodeFailure(s"$e for $value", None))

  private def decodeTraceId(encoded: Header.Value): Either[Header.DecodeFailure, UUID] =
    decodeTraceIdValue(encoded.value)

  private def decodeTraceIdValue(value: String): Either[Header.DecodeFailure, UUID] = for {
    bv <- ByteVector.fromHexDescriptive(value).leftMap(e => Header.DecodeFailure(s"$e for $value", None))
    uuid <- decodeUuid(bv)
  } yield uuid

  private def decodeUncompressed(headers: List[Header]): Either[Header.DecodeFailure, Header.Decoded] = {
    val spanIdAndSampled = for {
      traceId <- OptionT(headers.collectFirst { case Header(TraceIdHeaderName, value) => value }.traverse(decodeTraceId))
      parentIdMaybe <- OptionT.liftF(headers.collectFirst { case Header(ParentIdHeaderName, value) => value }.traverse(decodeSpanId))
      spanId <- OptionT(headers.collectFirst { case Header(SpanIdHeaderName, value) => value }.traverse(decodeSpanId))
      sampled = headers.collectFirst { case Header(SampledHeaderName, value) => value.value =!= "0" }.getOrElse(true)
    } yield SpanId(traceId, parentIdMaybe getOrElse spanId, spanId) -> sampled
    spanIdAndSampled.value.map(
      _.fold(Header.Decoded(None, true)) { case (spanId, sampled) => Header.Decoded(Some(spanId), sampled) })
  }

  private def decodeUuid(bv: ByteVector): Either[Header.DecodeFailure, UUID] = bv.size match {
    case TraceIdLongFormByteSize =>
      Either.catchNonFatal(bv.toUUID).leftMap { t => Header.DecodeFailure(s"The $TraceIdHeaderName value ${bv.toHex} cannot be converted to UUID", Some(t)) }
    case TraceIdShortFormByteSize =>
      Either.right(new UUID(bv.toLong(), 0L))
    case other =>
      Either.left(Header.DecodeFailure(s"The $TraceIdHeaderName must be either $TraceIdShortFormByteSize or $TraceIdLongFormByteSize but was ${bv.toHex}", None))
  }

  private def encodeCompressed(spanId: SpanId, sampled: Boolean): List[Header] = {
    val hv = if (spanId.root) s"${encodeTraceIdValue(spanId.traceId)}-${encodeSpanIdValue(spanId.spanId)}-${encodeSampledValue(sampled)}" else
      s"${encodeTraceIdValue(spanId.traceId)}-${encodeSpanIdValue(spanId.spanId)}-${encodeSampledValue(sampled)}-${encodeSpanIdValue(spanId.parentSpanId)}"
    List(Header(CompressedHeaderName, Header.Value(hv)))
  }

  private def encodeUncompressed(spanId: SpanId, sampled: Boolean): List[Header] = {
    val traceIdH = List(Header(TraceIdHeaderName, encodeTraceId(spanId.traceId)))
    val parentIdH = if (spanId.root) List.empty[Header] else List(Header(ParentIdHeaderName, encodeSpanId(spanId.parentSpanId)))
    val spanIdH = List(Header(SpanIdHeaderName, encodeSpanId(spanId.spanId)))
    val sampledH = List(Header(SampledHeaderName, encodeSampled(sampled)))
    traceIdH ++ parentIdH ++ spanIdH ++ sampledH
  }

  private def encodeSampled(sampled: Boolean): Header.Value = Header.Value(encodeSampledValue(sampled))
  private def encodeSampledValue(sampled: Boolean): String = if (sampled) "1" else "0"
  private def encodeSpanId(spanId: Long): Header.Value = Header.Value(encodeSpanIdValue(spanId))
  private def encodeSpanIdValue(spanId: Long): String = ByteVector.fromLong(spanId).toHex
  private def encodeTraceId(traceId: UUID): Header.Value = Header.Value(encodeTraceIdValue(traceId))
  private def encodeTraceIdValue(traceId: UUID): String = ByteVector.fromUUID(traceId).toHex

  private def sampleTrace(properties: Map[String, String]): Boolean =
    properties.get(Sampled).flatMap(s => Either.catchNonFatal(s.toBoolean).toOption).getOrElse(true)
}

object XB3HeaderCodec {
  /**
   * Property to pass to the `HeaderCodec.encode` method's `properties` that, when set to "true",
   * indicates a compressed `b3` HTTP header should be generated rather than three separate `X-B3-TraceId`,
   * `X-B3-ParentSpanId` and `X-B3-SpanId` headers.
   */
  final val Compressed: String = "compressed-headers"

  /**
   * Property to pass to the `HeaderCodec.encode` method's `properties` that, when set to "true",
   * indicates the X-B3-Sampled header (or "sampled" section of compressed header) should be set to "1"
   * and when false that it should be set to "0".  If not present, the flag will be set to "1" by
   * default in X-B3-Sampled Header/compressed sampled section as appropriate.
   */
  final val Sampled: String = "sampled"

  /* Used to validate / parse B3 compressed HTTP header into a `SpanId` instance. */
  final val CompressedHeaderRegex: Regex = s"([0-9a-fA-F]+)-([0-9a-fA-F]+)(?:-([0-1]))?(?:-([0-9a-fA-F]+))?".r

  /** The `X-B3` compliant compressed header format where TraceID-SpanId-ParentId are embedded in a single header */
  final val CompressedHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("b3")

  /** The `X-B3` compliant Trace ID HTTP header name. */
  final val TraceIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-TraceId")

  /** The `X-B3` compliant Parent Span ID HTTP header name. */
  final val ParentIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-ParentSpanId")

  /** The `X-B3` compliant Span ID HTTP header name. */
  final val SpanIdHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-SpanId")

  /** The `X-B3` compliant Sampled HTTP header name. */
  final val SampledHeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-B3-Sampled")

  /** The number of bytes (long form) of the Trace ID */
  final val TraceIdLongFormByteSize: Long = 16L

  /** The number of bytes (short form) of the Trace ID.  Note that the short form is not optimal if you want to ensure unique trace IDs */
  final val TraceIdShortFormByteSize: Long = 8L
}

