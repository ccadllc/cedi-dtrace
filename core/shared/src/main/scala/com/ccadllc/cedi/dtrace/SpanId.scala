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

import scala.util.Random

import cats.effect.Sync
import cats.implicits._

/**
 * Represents the core identity of a [[Span]].
 * @param traceId - the globally unique identifier for the trace of which this span is a component.
 * @param parentSpanId - the parent identifier of this span.  If this span is the root of the trace,
 *   this identifier will equal the `spanId` parameter.
 * @param spanId - the identifier of this span.  If this span is the root of the trace,
 *   this identifier will equal the `parentSpanId` parameter.
 */
final case class SpanId(traceId: UUID, parentSpanId: Long, spanId: Long) {

  /**
   * Indicates whether or not this [[SpanId]] identifies the root span of the overall trace.
   */
  val root: Boolean = parentSpanId == spanId

  /**
   * Creates a new child span identifier from this instance, with the new instance's `parentSpanId` equal
   * to this instance's `spanId` and a new value generated for the new instance's `spanId`.
   * @return newSpanIdDescription - an effectful description of a new [[SpanId]].
   */
  def newChild[F[_]: Sync]: F[SpanId] = SpanId.nextSpanIdValue map { newSpanId => copy(parentSpanId = spanId, spanId = newSpanId) }

  override def toString: String = s"SpanId~$traceId~$parentSpanId~$spanId"
}

/**
 * Companion object of the `SpanId` datatype.  Provides smart constructors and commonly used constants.
 */
object SpanId {
  /**
   * Creates a root [[SpanId]] from stratch in an effectful program `F[A]`.
   *
   * @return newSpanIdDescription - an effectful description of a new [[SpanId]].
   */
  def root[F[_]](implicit F: Sync[F]): F[SpanId] = for {
    traceId <- F.delay(UUID.randomUUID)
    parentChildId <- nextSpanIdValue
  } yield SpanId(traceId, parentChildId, parentChildId)

  private[dtrace] def nextSpanIdValue[F[_]](implicit F: Sync[F]): F[Long] = F.delay(Random.nextLong)

  private[dtrace] val empty: SpanId = SpanId(UUID.randomUUID, 0L, 0L)
}
