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
package com.ccadllc.cedi

import fs2.Task
import fs2.util._
import fs2.util.syntax._

import scala.language.higherKinds

/**
 * The distributed trace (dtrace) library provides the means to derive and record a `Comcast Money` compliant
 * distributed trace across effectful programs given the appropriate typeclasses for that program in
 * implicit scope.  The effectful programs are enhanced vai a `Kleisli`-like data type, [[TraceT]] which encodes
 * the information to calculate and record a trace [[Span]] at the conclusion of the program execution.
 */
package object dtrace {

  /**
   * Type alias provided for convenience when using a `fs2.Task` as the type of effectful
   * program being traced.
   */
  type TraceTask[A] = TraceT[Task, A]

  /**
   * Companion to the `TraceTask[A]` type alias - provides the [[TraceT]] smart constructors with the effectful
   * program `F` fixed as `fs2.Task`.
   */
  object TraceTask {
    def ask: TraceTask[TraceContext[Task]] = TraceT { Task.now }
    def now[A](a: A): TraceTask[A] = toTraceTask(Task.now(a))
    def delay[A](a: => A): TraceTask[A] = toTraceTask(Task.delay(a))
    def suspend[A](t: => Task[A]): TraceTask[A] = toTraceTask(Task.suspend(t))
    def fail[A](t: Throwable): TraceTask[A] = toTraceTask(Task.fail(t): Task[A])
    def toTraceTask[A](task: Task[A]): TraceTask[A] = TraceT { _ => task }
  }

  /**
   * Provides syntax enrichment.
   */
  object syntax {
    /**
     * Enriches an effectful program `F[A]` such that [[TraceT]] instance methods are made available on it, given
     * the appropriate typeclasses in implicit scope.
     */
    implicit class TraceEnrichedEffect[F[_], A](private val self: F[A]) extends AnyVal {

      def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
        toTraceT.newSpan(spanName, notes: _*)

      def newAnnotatedSpan(
        spanName: Span.Name,
        notes: Note*
      )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
        toTraceT.newAnnotatedSpan(spanName, notes: _*)(resultAnnotator)

      def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
        toTraceT.newSpan(spanName, evaluator, notes: _*)

      def newAnnotatedSpan(
        spanName: Span.Name,
        evaluator: Evaluator[A],
        notes: Note*
      )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
        toTraceT.newAnnotatedSpan(spanName, evaluator, notes: _*)(resultAnnotator)

      def toTraceT: TraceT[F, A] = TraceT.toTraceT[F, A](self)

      def bestEffortOnFinish(f: Option[Throwable] => F[Unit])(implicit F: Catchable[F]): F[A] =
        self.attempt flatMap { r =>
          f(r.left.toOption).attempt flatMap { _ => r.fold(F.fail, F.pure) }
        }
    }
  }
}
