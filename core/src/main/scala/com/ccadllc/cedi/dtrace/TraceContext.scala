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

import fs2.util._
import fs2.util.syntax._

import scala.language.higherKinds

/**
 * Represents a cursor into the "current" [[Span]] and associated system-level metadata and is associated with an
 * effectful program `F[A]` to realize a trace over that program.
 * @param currentSpan - the current [[Span]] associated with a particular effectful program.
 * @param system - system-level metadata which further annotates a [[Span]] when recording it.  The application, node,
 *  and deployment environment of the program being traced constitute the properties of this data type, along with the
 *  implementation of the `Emitter` used to perform the recording.
 */
case class TraceContext[F[_]](currentSpan: Span, system: TraceSystem[F]) {

  private[dtrace] def childSpan(spanName: Span.Name)(implicit F: Suspendable[F]): F[TraceContext[F]] =
    currentSpan.newChild(spanName) map { c => copy(currentSpan = c) }

  private[dtrace] def setNotes(notes: Vector[Note]): TraceContext[F] =
    copy(currentSpan = currentSpan.setNotes(notes))

  private[dtrace] def updateStartTime(implicit F: Suspendable[F]): F[TraceContext[F]] =
    currentSpan.updateStartTime map { updated => copy(currentSpan = updated) }

  private[dtrace] def emitSuccess(implicit F: Suspendable[F]): F[Unit] =
    finishSuccess flatMap system.emitter.emit

  private[dtrace] def emitFailure(detail: FailureDetail)(implicit F: Suspendable[F]): F[Unit] =
    finishFailure(detail) flatMap system.emitter.emit

  private def finishSuccess(implicit F: Suspendable[F]): F[TraceContext[F]] =
    currentSpan.finishSuccess map { ss => copy(currentSpan = ss) }

  private def finishFailure(detail: FailureDetail)(implicit F: Suspendable[F]): F[TraceContext[F]] =
    currentSpan.finishFailure(detail) map { us => copy(currentSpan = us) }

  override def toString: String = s"[currentSpan=$currentSpan] [system=$system]"
}

object TraceContext {
  private[dtrace] def empty[F[_]: Applicative]: TraceContext[F] =
    TraceContext(Span.empty, TraceSystem.empty[F])
}
