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

import cats.Functor
import cats.effect.Sync
import cats.syntax.all._

import scala.concurrent.duration._

/**
 * Represents a single span for a distributed trace.  A span is the traced execution of an effectful program `F`,
 * part of a possibly larger transaction that represents the trace as a whole.
 * @param spanId - the [[SpanId]] representing this span's identity and its place in a distributed trace's hiearchy
 *   of spans.
 * @param spanName - the human readable name of this span - it is the logical name of the effectful program whose execution
 *   this span is recording.
 * @param startTime - the [[TraceSystem#Time]] representing the point in time at which the program started execution
 * @param failure - the [[FailureDetail]] is present and provides a means to render the detail of a program execution failure
 *   if the execution failed; otherwise is it not present.
 * @param duration - the elapsed time of the execution of the program.
 * @param notes - a `Vector` of [[Note]]s used to annotate the program's execution with metadata, which can be derived both from
 *   the program's inputs as well as its results.
 */
case class Span(
  spanId: SpanId,
  spanName: Span.Name,
  startTime: TraceSystem.Time,
  failure: Option[FailureDetail],
  duration: FiniteDuration,
  notes: Vector[Note]) {

  /**
   * Indicates whether or not this span is the root span of the overall trace.
   */
  val root: Boolean = spanId.root

  private[dtrace] def newChild[F[_]: Sync](timer: TraceSystem.Timer[F], spanName: Span.Name): F[Span] = for {
    startTime <- timer.time
    child <- spanId.newChild
  } yield Span(child, spanName, startTime, None, Duration.Zero, Vector.empty)

  private[dtrace] def setNotes(notes: Vector[Note]): Span =
    copy(notes = notes)

  private[dtrace] def updateStartTime[F[_]: Functor](timer: TraceSystem.Timer[F]): F[Span] =
    timer.time map { t => copy(startTime = t) }

  private[dtrace] def finishSuccess[F[_]: Functor](timer: TraceSystem.Timer[F]): F[Span] =
    finish(timer, None)

  private[dtrace] def finishFailure[F[_]: Functor](timer: TraceSystem.Timer[F], detail: FailureDetail): F[Span] =
    finish(timer, Some(detail))

  private def finish[F[_]: Functor](timer: TraceSystem.Timer[F], failure: Option[FailureDetail]): F[Span] =
    timer.time map { endTime =>
      copy(failure = failure, duration = FiniteDuration(endTime.value - startTime.value, endTime.unit))
    }

  override def toString: String =
    s"[span-id=$spanId] [span-name=$spanName] [start-time=$startTime] [span-success=${failure.isEmpty}] [failure-detail=${failure.fold("N/A")(_.render)}] [span-duration=$duration] [notes=[${notes.mkString("] [")}]]"
}

object Span {
  final case class Name(value: String) { override def toString: String = value }

  /**
   * Creates a root span by generating a root [[SpanId]].
   * @param timer - the [[TraceSystem#Timer]] used to measure the span.
   * @param spanName - the human readable name of this span.
   * @param notes - a variable argument list of [[Note]]s, metadata to annotate this span.
   * @return newSpan - an effectful program which when run will result in a new root span.
   */
  def root[F[_]: Sync](timer: TraceSystem.Timer[F], spanName: Name, notes: Note*): F[Span] =
    SpanId.root flatMap { newSpan(timer, _, spanName, notes: _*) }

  /**
   * Create a new child of the passed-in [[SpanId]].
   * @param timer - the [[TraceSystem#Timer]] used to measure the span.
   * @param spanId - the [[SpanId]] containing the identity for which a child span will be created.
   * @param spanName - the human readable name of this span.
   * @param notes - a variable argument list of [[Note]]s, metadata to annotate this span.
   * @return newSpan - an effectful program which when run will result in a new child span.
   */
  def newChild[F[_]: Sync](timer: TraceSystem.Timer[F], spanId: SpanId, spanName: Name, notes: Note*): F[Span] =
    spanId.newChild flatMap { newSpan(timer, _, spanName, notes: _*) }

  private def newSpan[F[_]: Functor](timer: TraceSystem.Timer[F], spanId: SpanId, spanName: Span.Name, notes: Note*): F[Span] =
    timer.time map { Span(spanId, spanName, _, None, Duration.Zero, notes.toVector) }
}
