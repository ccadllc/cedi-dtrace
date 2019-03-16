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

import cats.implicits._

import java.util.UUID

import scala.util.matching.Regex

import MoneyHeaderCodec._

/**
 * Implements the `HeaderCodec` trait, providing for the encoding and decoding of
 * [[https://github.com/Comcast/money Comcast Money]]-style tracing HTTP headers into and from a `SpanId` respectively.
 */
class MoneyHeaderCodec extends HeaderCodec {

  /**
   * Encodes a [[https://github.com/Comcast/money Comcast Money]]-compliant header.  The
   * `properties` argument is currently unused for the `Money` protocol.
   */
  override def encode(spanId: SpanId, properties: Map[String, String]): List[Header] = {
    val headerValue = Header.Value(
      s"$TraceIdHeader=${spanId.traceId};$ParentIdHeader=${spanId.parentSpanId};$SpanIdHeader=${spanId.spanId}")
    List(Header(HeaderName, headerValue))
  }

  /**
   * Decodes a [[SpanId]] from a [[https://github.com/Comcast/money Comcast Money]]-compliant header.
   * The `properties` argument is currently unused for the `Money` protocol.
   */
  override def decode(headers: List[Header], properties: Map[String, String]): Either[Header.DecodeFailure, Option[SpanId]] = {
    headers.collectFirst { case Header(HeaderName, Header.Value(value)) => value }.traverse {
      case value @ HeaderRegex(traceId, _, parentId, spanId) =>
        Either.catchNonFatal { SpanId(UUID.fromString(traceId), parentId.toLong, spanId.toLong) }.leftMap { t =>
          Header.DecodeFailure(s"Could not parse $value into a SpanId", Some(t))
        }
      case value => Left(Header.DecodeFailure(s"Could not parse $value into a SpanId", None))
    }
  }
}

object MoneyHeaderCodec {
  /** The `Money` compliant HTTP header name. */
  final val HeaderName: Header.CaseInsensitiveName = Header.CaseInsensitiveName("X-MoneyTrace")

  /** The `Money` compliant HTTP header trace GUID component value identifier. */
  final val TraceIdHeader: String = "trace-id"

  /** The `Money` compliant HTTP header Parent Span ID component value identifier. */
  final val ParentIdHeader: String = "parent-id"

  /** The `Money` compliant HTTP header Span ID component value identifier. */
  final val SpanIdHeader: String = "span-id"

  /* Used to validate / parse `Money` compliant HTTP header into a [[SpanId]] instance. */
  final val HeaderRegex: Regex = s"$TraceIdHeader=([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-fA-F]{12});$ParentIdHeader=([\\-0-9]+);$SpanIdHeader=([\\-0-9]+)".r
}
