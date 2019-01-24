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
  def encode(spanId: SpanId): List[Header]
  def decode(headers: List[Header]): Either[Header.DecodeFailure, Option[SpanId]]
  def andThen(other: HeaderCodec): HeaderCodec = new HeaderCodec {
    def encode(spanId: SpanId): List[Header] = self.encode(spanId) ++ other.encode(spanId)
    def decode(headers: List[Header]): Either[Header.DecodeFailure, Option[SpanId]] = self.decode(headers) match {
      case Right(None) => other.decode(headers)
      case other => other
    }
  }
  def compose(other: HeaderCodec): HeaderCodec = other.andThen(this)
}
