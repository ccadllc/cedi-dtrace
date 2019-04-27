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

import cats.Show

/**
 * Represents a general header name/value. Note that though
 *  it usually represents an HTTP header, it can be any key/value pair
 *  (for instance, a header in an RPC payload).
 */
final case class Header(name: Header.CaseInsensitiveName, value: Header.Value)
object Header {
  /**
   * Represents a decoded header, which will possibly contain a `SpanId`
   * if the appropriate headers were found and sets `sampled` to true
   * unless the headers indicate sampling should be set to false.
   */
  final case class Decoded(spanId: Option[SpanId], sampled: Boolean)

  final case class CaseInsensitiveName(value: String) {
    /* Added to suppress equals/hashcode warning from compiler when using the `override val hashCode` idiom */
    private val hc: Int = value.toLowerCase.hashCode
    override def hashCode: Int = hc
    override def equals(other: Any): Boolean = other match {
      case CaseInsensitiveName(ov) => value.equalsIgnoreCase(ov)
      case _ => false
    }
  }
  final case class Value(value: String)
  final case class DecodeFailure(message: String, cause: Option[Throwable]) extends RuntimeException(message, cause.orNull)
  object DecodeFailure {
    implicit val show: Show[DecodeFailure] = Show.show { (df: DecodeFailure) =>
      val causeShown = df.cause.fold("") { c => s" [cause: $c]" }
      s"${df.message}$causeShown."
    }
  }
}

/**
 * Describes functionality for encoding or decoding a
 * `SpanId` to one or more headers. Protocol specific modules
 * implement this and those implementations can be composed in order
 * to support headers from multiple protocols in the priority order
 * desired by the composer.
 */
trait HeaderCodec { self =>
  /**
   * Encode an Header given a [[SpanId]].  Protocol-specific
   * name/value properties can be passed in the given map to configure
   * behavior for that protocol. For example, the `xb3` module allows
   * one to pass `Map("Compressed", "true")` to generate a compressed
   * 'b3' header which combines the trace ID, span ID and parent XB3
   * header values into one and `Map("Sampling", "false")` to set the
   * X-B3-Sampled to "0".
   */
  def encode(spanId: SpanId, properties: Map[String, String]): List[Header]

  /**
   * Encode an Header given a [[SpanId]]. A convenience variant of
   * `encode(spanId, properties)` when no `properties` apply to the encoding.
   */
  def encode(spanId: SpanId): List[Header] = encode(spanId, Map.empty)

  /**
   * Decode a [[SpanId]] and sampled flag from protocol-specific Headers.
   * Protocol-specific name/value properties can be passed in the given map.
   */
  def decode(headers: List[Header], properties: Map[String, String]): Either[Header.DecodeFailure, Header.Decoded]

  /**
   * Decode a [[SpanId]] and sampled flag from protocol-specific Headers.  A convenience variant of
   * `decode(headers, properties)` when no `properties` apply to the decoding.
   */
  def decode(headers: List[Header]): Either[Header.DecodeFailure, Header.Decoded] = decode(headers, Map.empty)

  /**
   * Convenience methods to compose multiple [[HeaderCodec]]s when parsing and encoding
   * headers for multiple protocols (e.g., `money` and `xb3).
   */
  def andThen(other: HeaderCodec): HeaderCodec = new HeaderCodec {
    def encode(spanId: SpanId, props: Map[String, String]): List[Header] = self.encode(spanId, props) ++ other.encode(spanId, props)
    def decode(headers: List[Header], props: Map[String, String]): Either[Header.DecodeFailure, Header.Decoded] = self.decode(headers, props) match {
      case Right(Header.Decoded(None, _)) => other.decode(headers, props)
      case other => other
    }
  }
  def compose(other: HeaderCodec): HeaderCodec = other.andThen(this)
}
