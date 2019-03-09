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
import cats.implicits._

import scala.language.higherKinds

/**
 * Represents a cursor into the "current" [[Span]] and associated system-level information and is associated with an
 * effectful program `F[A]` to realize a trace of that program.
 * @param currentSpan - the current [[Span]] associated with a particular effectful program.
 * @param system - a [[TraceSystem]] consisting of system-level information
 *  which further annotates a [[Span]] when recording it along with an implementation of an
 *  [[TraceSystem#Emitter]] used to perform the recording of the span.
 * @tparam F - an effectful program type representing the program type of the [[TraceSystem]] (which in turn indicates
 *   the program type of the `TraceSystem`'s `Emitter`).
 */
case class TraceContext[F[_]](currentSpan: Span, system: TraceSystem[F]) {

  private[dtrace] def childSpan(spanName: Span.Name)(implicit F: Sync[F]): F[TraceContext[F]] =
    currentSpan.newChild(system.timer, spanName) map { c => copy(currentSpan = c) }

  private[dtrace] def setNotes(notes: Vector[Note]): TraceContext[F] =
    copy(currentSpan = currentSpan.setNotes(notes))

  private[dtrace] def updateStartTime(implicit F: Functor[F]): F[TraceContext[F]] =
    currentSpan.updateStartTime(system.timer) map { updated => copy(currentSpan = updated) }

  private[dtrace] def emitSuccess(implicit F: Sync[F]): F[Unit] =
    finishSuccess flatMap system.emitter.emit

  private[dtrace] def emitFailure(detail: FailureDetail)(implicit F: Sync[F]): F[Unit] =
    finishFailure(detail) flatMap system.emitter.emit

  private def finishSuccess(implicit F: Functor[F]): F[TraceContext[F]] =
    currentSpan.finishSuccess(system.timer) map { ss => copy(currentSpan = ss) }

  private def finishFailure(detail: FailureDetail)(implicit F: Functor[F]): F[TraceContext[F]] =
    currentSpan.finishFailure(system.timer, detail) map { us => copy(currentSpan = us) }

  override def toString: String = s"[currentSpan=$currentSpan] [system=$system]"
}
