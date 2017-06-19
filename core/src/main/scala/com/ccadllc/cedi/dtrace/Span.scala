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

import cats.effect.Sync
import cats.implicits._

/**
 * Represents a single span for a distributed trace.  A span is the traced execution of an effectful program `F`.
 * @param spanId - the [[SpanId]] representing this span's identity and its place in a distributed trace's hiearchy
 *   of spans.
 * @param spanName - the human readable name of this span - it is the logical name of the effectful program whose execution
 *   this span is recording.
 * @param startTime - the `java.time.Instant` representing the timestamp at which the program started execution
 * @param failure - the [[FailureDetail]] is present and provides a means to render the detail of a program execution failure
 *   if the execution failed; otherwise is it not present.
 * @param duration - the elapsed time of the execution of the program.
 * @param notes - a `Vector` of [[Note]]s used to annotate the program's execution with metadata, which can be derived both from
 *   the program's inputs as well as its results.
 */
case class Span(
    spanId: SpanId,
    spanName: Span.Name,
    startTime: Instant,
    failure: Option[FailureDetail],
    duration: FiniteDuration,
    notes: Vector[Note]
) {

  /**
   * Indicates whether or not this span is the root span of the overall trace.
   */
  val root: Boolean = spanId.root

  private[dtrace] def newChild[F[_]: Sync](spanName: Span.Name): F[Span] = for {
    startTime <- Span.nowInstant
    child <- spanId.newChild
  } yield Span(child, spanName, startTime, None, Duration.Zero, Vector.empty)

  private[dtrace] def setNotes(notes: Vector[Note]): Span =
    copy(notes = notes)

  private[dtrace] def updateStartTime[F[_]: Sync]: F[Span] =
    Span.nowInstant map { t => copy(startTime = t) }

  private[dtrace] def finishSuccess[F[_]: Sync]: F[Span] =
    finish(None)

  private[dtrace] def finishFailure[F[_]: Sync](detail: FailureDetail): F[Span] =
    finish(Some(detail))

  private def finish[F[_]: Sync](failure: Option[FailureDetail]): F[Span] =
    Span.nowInstant map { endTime =>
      copy(failure = failure, duration = FiniteDuration(ChronoUnit.MICROS.between(startTime, endTime), MICROSECONDS))
    }

  override def toString: String =
    s"[span-id=$spanId] [span-name=$spanName] [start-time=$startTime] [span-success=${failure.isEmpty}] [failure-detail=${failure.fold("N/A")(_.render)}] [span-duration=$duration] [notes=[${notes.mkString("] [")}]"
}

object Span {
  case class Name(value: String) { override def toString: String = value }

  /**
   * Creates a root span by generating a root [[SpanId]].
   * @param spanName - the human readable name of this span.
   * @param notes - a variable argument list of [[Note]]s, metadata to annotate this span.
   * @return newSpan - an effectful program which when run will result in a new root span.
   */
  def root[F[_]: Sync](spanName: Name, notes: Note*): F[Span] = SpanId.root flatMap { newSpan(_, spanName, notes: _*) }

  /**
   * Create a new child of the passed-in [[SpanId]].
   * @param spanId - the [[SpanId]] containing the identity for which a child span will be created.
   * @param spanName - the human readable name of this span.
   * @param notes - a variable argument list of [[Note]]s, metadata to annotate this span.
   * @return newSpan - an effectful program which when run will result in a new child span.
   */
  def newChild[F[_]: Sync](spanId: SpanId, spanName: Name, notes: Note*): F[Span] = spanId.newChild flatMap { newSpan(_, spanName, notes: _*) }

  private[dtrace] val empty: Span = Span(SpanId.empty, Span.Name("empty"), Instant.EPOCH, None, 0.seconds, Vector.empty)

  private def nowInstant[F[_]](implicit F: Sync[F]): F[Instant] = F.delay(Instant.now)

  private def newSpan[F[_]: Sync](spanId: SpanId, spanName: Span.Name, notes: Note*): F[Span] =
    nowInstant map { Span(spanId, spanName, _, None, Duration.Zero, notes.toVector) }
}
