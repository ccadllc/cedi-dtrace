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

import java.time.Instant
import java.time.temporal.ChronoUnit

import scala.concurrent.duration._
import scala.language.higherKinds

import fs2.util._
import fs2.util.syntax._

case class Span(
    spanId: SpanId,
    spanName: Span.Name,
    startTime: Instant,
    failure: Option[FailureDetail],
    duration: FiniteDuration,
    notes: Vector[Note]
) {

  def root: Boolean = spanId.root

  private[dtrace] def newChild[F[_]: Suspendable](spanName: Span.Name): F[Span] = for {
    startTime <- Span.nowInstant
    child <- spanId.newChild
  } yield Span(child, spanName, startTime, None, Duration.Zero, Vector.empty)

  private[dtrace] def setNotes(notes: Vector[Note]): Span =
    copy(notes = notes)

  private[dtrace] def updateStartTime[F[_]: Suspendable]: F[Span] =
    Span.nowInstant map { t => copy(startTime = t) }

  private[dtrace] def finishSuccess[F[_]: Suspendable]: F[Span] =
    finish(None)

  private[dtrace] def finishFailure[F[_]: Suspendable](detail: FailureDetail): F[Span] =
    finish(Some(detail))

  private def finish[F[_]: Suspendable](failure: Option[FailureDetail]): F[Span] =
    Span.nowInstant map { endTime =>
      copy(failure = failure, duration = FiniteDuration(ChronoUnit.MICROS.between(startTime, endTime), MICROSECONDS))
    }

  override def toString: String =
    s"[span-id=$spanId] [span-name=$spanName] [start-time=$startTime] [span-success=${failure.isEmpty}] [failure-detail=${failure.fold("N/A")(_.render)}] [span-duration=$duration] [notes=[${notes.mkString("] [")}]"
}

object Span {
  case class Name(value: String) { override def toString: String = value }

  def root[F[_]: Suspendable](spanName: Name, notes: Note*): F[Span] = SpanId.root flatMap { newSpan(_, spanName, notes: _*) }

  def newChild[F[_]: Suspendable](spanId: SpanId, spanName: Name, notes: Note*): F[Span] = spanId.newChild flatMap { newSpan(_, spanName, notes: _*) }

  private[dtrace] val empty: Span = Span(SpanId.empty, Span.Name("empty"), Instant.EPOCH, None, 0.seconds, Vector.empty)

  private def nowInstant[F[_]](implicit F: Suspendable[F]): F[Instant] = F.delay(Instant.now)

  private def newSpan[F[_]: Suspendable](spanId: SpanId, spanName: Span.Name, notes: Note*): F[Span] =
    nowInstant map { Span(spanId, spanName, _, None, Duration.Zero, notes.toVector) }
}
