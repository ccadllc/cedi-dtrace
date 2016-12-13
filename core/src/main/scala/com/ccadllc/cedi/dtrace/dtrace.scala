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

package object dtrace {

  type TraceTask[A] = TraceT[Task, A]

  object TraceTask {
    def ask: TraceTask[TraceContext[Task]] = TraceT { Task.now }
    def now[A](a: A): TraceTask[A] = toTraceTask(Task.now(a))
    def delay[A](a: => A): TraceTask[A] = toTraceTask(Task.delay(a))
    def suspend[A](t: => Task[A]): TraceTask[A] = toTraceTask(Task.suspend(t))
    def fail[A](t: Throwable): TraceTask[A] = toTraceTask(Task.fail(t): Task[A])
    def toTraceTask[A](task: Task[A]): TraceTask[A] = TraceT { _ => task }
  }

  object syntax {
    implicit class TraceEnrichedEffect[F[_], A](private val self: F[A]) extends AnyVal {
      def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
        toTraceT.newSpan(spanName, notes: _*)

      def newAnnotatedSpan(spanName: Span.Name, notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
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
