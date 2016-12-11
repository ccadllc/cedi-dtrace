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

import fs2.{ Strategy, Task }
import fs2.util.Async
import fs2.util.syntax._

import scala.language.higherKinds

package object dtrace {
  type TraceTask[A] = TraceAsync[Task, A]
  object TraceTask {
    def ask(implicit S: Strategy): TraceTask[TraceContext] = TraceAsync { Task.now }
    def now[A](a: A)(implicit S: Strategy): TraceTask[A] = toTraceTask(Task.now(a))
    def delay[A](a: => A)(implicit S: Strategy): TraceTask[A] = toTraceTask(Task.delay(a))
    def fail[A](t: Throwable)(implicit S: Strategy): TraceTask[A] = toTraceTask(Task.fail(t): Task[A])
    def toTraceTask[A](task: Task[A])(implicit S: Strategy): TraceTask[A] = TraceAsync { _ => task }
  }

  object syntax {
    implicit class TraceEnrichedAsync[F[_], A](val async: F[A]) extends AnyVal {
      def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Async[F]): TraceAsync[F, A] =
        toTraceAsync.newSpan(spanName, notes: _*)

      def newAnnotatedSpan(spanName: Span.Name, notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Async[F]): TraceAsync[F, A] =
        toTraceAsync.newAnnotatedSpan(spanName, notes: _*)(resultAnnotator)

      def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Async[F]): TraceAsync[F, A] =
        toTraceAsync.newSpan(spanName, evaluator, notes: _*)

      def newAnnotatedSpan(
        spanName: Span.Name,
        evaluator: Evaluator[A],
        notes: Note*
      )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Async[F]): TraceAsync[F, A] =
        toTraceAsync.newAnnotatedSpan(spanName, evaluator, notes: _*)(resultAnnotator)

      def toTraceAsync(implicit F: Async[F]): TraceAsync[F, A] = TraceAsync.toTraceAsync[F, A](async)

      def bestEffortOnFinish(f: Option[Throwable] => F[Unit])(implicit F: Async[F]): F[A] = async.attempt flatMap { r =>
        f(r.left.toOption).attempt flatMap { _ => r.fold(F.fail, F.pure) }
      }
    }
  }
}
