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

import scala.language.higherKinds
import scala.util.Random
import scala.util.control.NonFatal
import scala.util.matching.Regex

import fs2.util.Suspendable
import fs2.util.syntax._

/**
 * Represents the core identity of a [[Span]].
 * @param traceId - the globally unique identifier for the trace of which this span is a component.
 * @param parentSpanId - the parent identifier of this span.  If this span is the root of the trace,
 *   this identifier will equal the `spanId`.
 * @param spanId - the identifier of this span.  If this span is the root of the trace,
 *   this identifier will equal the `parentSpanId`.
 */
final case class SpanId(traceId: UUID, parentSpanId: Long, spanId: Long) {

  /**
   * This is [[Span]] the root of the trace?
   */
  def root: Boolean = parentSpanId == spanId

  /**
   * Converts the span to a `Money`-compliant HTTP Header.
   * @return header - the `Money`-compliant header consisting of
   *   `X-MoneyTrace=trace-id=<trace id UUID>;parent-id=<long integer>;span-id=<long integer>`
   */
  val toHeader: String = s"${SpanId.TraceIdHeader}=$traceId;${SpanId.ParentIdHeader}=$parentSpanId;${SpanId.SpanIdHeader}=$spanId"

  /**
   * Creates a new Child span identifier from `this`, which the `parentSpanId` equal to `this.spanId` and a new value generated
   * for the `spanId`.
   */
  def newChild[F[_]: Suspendable]: F[SpanId] = SpanId.nextSpanIdValue map { newSpanId => copy(parentSpanId = spanId, spanId = newSpanId) }

  override def toString: String = s"SpanId~$traceId~$parentSpanId~$spanId"
}

/**
 * Companion object of the `SpanId` datatype.  Provides smart constructors and commonly used constants.
 */
object SpanId {
  /** The `Money` compliant HTTP header name. */
  final val HeaderName: String = "X-MoneyTrace"
  /** The `Money` compliant HTTP header trace GUID component value identifier. */
  final val TraceIdHeader: String = "trace-id"
  /** The `Money` compliant HTTP header Parent Span ID component value identifier. */
  final val ParentIdHeader: String = "parent-id"
  /** The `Money` compliant HTTP header Span ID component value identifier. */
  final val SpanIdHeader: String = "span-id"

  /* Used to validate / parse `Money` compliant HTTP header into a [[SpanId]] instance. */
  final val HeaderRegex: Regex = s"$TraceIdHeader=([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-fA-F]{12});$ParentIdHeader=([\\-0-9]+);$SpanIdHeader=([\\-0-9]+)".r

  /**
   * Creates a root [[SpanId]] from stratch in an effectful program `F[A]`.
   *
   * @return newSpanIdDescription - an effectful description of a new [[SpanId]].
   */
  def root[F[_]](implicit F: Suspendable[F]): F[SpanId] = for {
    traceId <- F.delay(UUID.randomUUID)
    parentChildId <- nextSpanIdValue
  } yield SpanId(traceId, parentChildId, parentChildId)

  /**
   * Creates an instance of a [[SpanId]] from the given header name and value, if the
   * header name matches the `Money` compliant header name of `X-MoneyTrace` and the header
   * value is in the proper format; otherwise, an error is returned.
   * @return newSpanIdOrError - a [[SpanId]] if successful; otherwise, an error message is returned.
   */
  def fromHeader(headerName: String, headerValue: String): Either[String, SpanId] =
    if (headerName == HeaderName) fromHeaderValue(headerValue) else Left(s"Header name $headerName is not a Money-compliant trace header")

  def fromHeaderValue(headerValue: String): Either[String, SpanId] = headerValue match {
    case HeaderRegex(traceId, parentId, spanId) =>
      try Right(SpanId(UUID.fromString(traceId), parentId.toLong, spanId.toLong))
      catch {
        case NonFatal(t) => Left(s"Could not parse $headerValue into a SpanId: ${t.getMessage}")
      }
    case _ => Left(s"Could not parse $headerValue into a SpanId")
  }

  private[dtrace] def nextSpanIdValue[F[_]](implicit F: Suspendable[F]): F[Long] = F.delay(Random.nextLong)

  private[dtrace] val empty: SpanId = SpanId(UUID.randomUUID, 0L, 0L)
}
