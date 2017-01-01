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
 * implicit scope.  The effectful programs are enhanced vai a `Kleisli`-like data type, [[TraceT]], which encodes
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
    /**
     * Ask for the current `TraceContext[Task]` in a `TraceTask`.
     * @return traceTaskOfTraceContext - a `TraceContext[Task]` wrapped in a `TraceTask`.
     */
    def ask: TraceTask[TraceContext[Task]] = TraceT { Task.now }

    /**
     * Lifts a value `A` into a `TraceTask[A]` context.
     * @param a - the pure value `A` to lift into a `TraceTask` context.
     * @return traceTaskOfA - a pure value `A` wrapped in a `TraceTask`.
     */
    def now[A](a: A): TraceTask[A] = toTraceTask(Task.now(a))

    /**
     * Lifts the non-strict, possibly impure expression computing `A` into a `TraceTask[A]`
     * context.
     * @param a - the non-strict expression computing `A` to lift into a `TraceTask` context.
     * @return traceTaskOfA - a non-strict expression which computes `A` lifted into a `TraceTask`.
     */
    def delay[A](a: => A): TraceTask[A] = toTraceTask(Task.delay(a))

    /**
     * Lifts the non-strict, possibly impure expression computing a `TraceTask[A]` into a `TraceTask[A]`
     * The expression is suspended until the outer `TraceTask` returned is run.
     * @param t - the non-strict expression computing `TraceTask[A]` to lift into a `TraceTask` context suspended
     *   until the outer `TraceTask` is run.
     * @return traceTaskOfTA - a non-strict expression which computes `TraceTask[A]` lifted into a `TraceTask` in
     *   a suspended state until the outer `TraceTask` is run.
     */
    def suspend[A](t: => Task[A]): TraceTask[A] = toTraceTask(Task.suspend(t))

    /**
     * Creates a failed `TraceTask`.
     * @param t - the `Throwable` with which to fail the underlying program.
     * @return traceTaskOfA - the `TraceTask[A]` in a failed state.
     */
    def fail[A](t: Throwable): TraceTask[A] = toTraceTask(Task.fail(t): Task[A])

    /*
     * Lifts an `fs2.Task` which computes `A` into a `TraceTask[A]` context.
     * @param task - a `fs2.Task` which computes a value `A`.
     * @return traceTaskOfA - a `TraceTask[A]`
     */
    def toTraceTask[A](task: Task[A]): TraceTask[A] = TraceT { _ => task }
  }

  /**
   * Enriches an effectful program `F[A]` such that [[TraceT]] instance methods are made available on it, given
   * the appropriate typeclasses in implicit scope.
   */
  implicit class TraceEnrichedEffect[F[_], A](private val self: F[A]) extends AnyVal {
    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`, using the
     * default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of span recording.
     * For example:
     *   ```
     *   val task = Task.delay(some computation)
     *   task.newSpan(
     *     Span.Name("query-products-for-sale",
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   )
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @param F - an instance of an `fs2.util.Catchable[F]` and `fs2.util.Suspendable[F]` in implicit scope.
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
      toTraceT.newSpan(spanName, notes: _*)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`,
     * providing the capability of annotating the span with notes based on the execution result of the `F[A]`,
     * using the default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of span recording.
     * For example:
     *   ```
     *   val task = Task.delay(some computation)
     *   task.newAnnotatedSpan(
     *     Span.Name("query-products-for-sale",
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   ) {
     *     case Right(saleProducts) => Vector(Note.string("sale-products", saleProducts.mkString(",")))
     *   }
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @param resultsAnnotator - a partial function from an `Either[Throwable, A]` to a `Vector[Note]`, providing
     *   for the ability to add additional annotation of the [[Span]] based on the result of the underlying `F[A]`
     *   execution.
     * @param F - an instance of an `fs2.util.Catchable[F]` and `fs2.util.Suspendable[F]` in implicit scope.
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newAnnotatedSpan(
      spanName: Span.Name,
      notes: Note*
    )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
      toTraceT.newAnnotatedSpan(spanName, notes: _*)(resultAnnotator)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`, providing for custom
     * evaluation and rendering of the underlying `F[A]` when recording the [[Span]].
     * For example:
     *   ```
     *   val task = Task.delay(some computation)
     *   task.newSpan(
     *     Span.Name("query-products-for-sale",
     *     Evaluator.resultToFailure[Vector[Product]
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   )
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param evaluator - an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]].
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @param F - an instance of an `fs2.util.Catchable[F]` and `fs2.util.Suspendable[F]` in implicit scope.
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
      toTraceT.newSpan(spanName, evaluator, notes: _*)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`, providing the capability
     * of annotating the span with notes based on the execution result of the `F[A]`, using the
     * a custom [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of recording.
     * For example:
     *   ```
     *   val task = Task.delay(some computation)
     *   task.newAnnotatedSpan(
     *     Span.Name("query-products-for-sale",
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   ) {
     *     case Right(saleProducts) => Vector(Note.string("sale-products", saleProducts.mkString(",")))
     *   }
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @param resultsAnnotator - a partial function from an `Either[Throwable, A]` to a `Vector[Note]`, providing
     *   for the ability to add additional annotation of the [[Span]] based on the result of the underlying `F[A]` run.
     * @param F - an instance of an `fs2.util.Catchable[F]` and `fs2.util.Suspendable[F]` in implicit scope.
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newAnnotatedSpan(
      spanName: Span.Name,
      evaluator: Evaluator[A],
      notes: Note*
    )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
      toTraceT.newAnnotatedSpan(spanName, evaluator, notes: _*)(resultAnnotator)

    /**
     * Lifts this program `F` which computes `A` into a `TraceT[F, A]` context.
     * @return traceTOfA - a `TraceT[F, A]`
     */
    def toTraceT: TraceT[F, A] = TraceT.toTraceT[F, A](self)

    /**
     * Lifts this `F[A]` into a `TraceT[F, A] and then transforms that `TraceT` to an equivalent `TraceT[F, A]` where
     * a best-effort will be made to execute the passed-in function on the finish of the underlying effectful program.
     * The function can't be guaranteed to run in the face of interrupts, etc.  It depends on the nature of the effectful program
     * itself.
     * @param f - a function which is passed an optional `Throwable` - defined if the program failed and
     *   returns a `TraceT[F, Unit]`, a program run only for its effect.
     * @param F - an instance of `fs2.util.Catchable[F]` in implicit scope.
     * @return traceTForFA - a new `TraceT[F, A]` with the error handling of the aforementioned `f` function
     *   parameter.
     */
    def bestEffortOnFinish(f: Option[Throwable] => F[Unit])(implicit F: Catchable[F]): F[A] =
      self.attempt flatMap { r =>
        f(r.left.toOption).attempt flatMap { _ => r.fold(F.fail, F.pure) }
      }
  }
}
