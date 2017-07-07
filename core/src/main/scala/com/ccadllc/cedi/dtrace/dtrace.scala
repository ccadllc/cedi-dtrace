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

import cats.MonadError
import cats.effect.{ IO, Sync }
import cats.implicits._

import scala.language.higherKinds

/**
 * The distributed trace (dtrace) library provides the means to derive and record a `Comcast Money` compliant
 * distributed trace across effectful programs given the appropriate typeclasses for that program in
 * implicit scope.  The effectful programs are enhanced vai a `Kleisli`-like data type, [[TraceT]], which encodes
 * the information to calculate and record a trace [[Span]] at the conclusion of the program execution.
 */
package object dtrace {

  /**
   * Type alias provided for convenience when using an `IO` as the type of effectful
   * program being traced.
   */
  type TraceIO[A] = TraceT[IO, A]

  /**
   * Companion to the `TraceIO[A]` type alias - provides the [[TraceT]] smart constructors with the effectful
   * program `F` fixed as `IO`.
   */
  object TraceIO {
    /**
     * Ask for the current `TraceContext[IO]` in a `TraceIO`.
     * @return a `TraceContext[IO]` wrapped in a `TraceIO`.
     */
    def ask: TraceIO[TraceContext[IO]] = TraceT { IO.pure }

    /**
     * Lifts a value `A` into a `TraceIO[A]` context.
     * @param a - the pure value `A` to lift into a `TraceIO` context.
     * @return a pure value `A` wrapped in a `TraceIO`.
     */
    def pure[A](a: A): TraceIO[A] = toTraceIO(IO.pure(a))

    /**
     * Lifts the non-strict, possibly impure expression computing `A` into a `TraceIO[A]`
     * context.
     * @param a - the non-strict expression computing `A` to lift into a `TraceIO` context.
     * @return a non-strict expression which computes `A` lifted into a `TraceIO`.
     */
    def apply[A](a: => A): TraceIO[A] = toTraceIO(IO(a))

    /**
     * Lifts the non-strict, possibly impure expression computing a `TraceIO[A]` into a `TraceIO[A]`
     * The expression is suspended until the outer `TraceIO` returned is run.
     * @param t - the non-strict expression computing `TraceIO[A]` to lift into a `TraceIO` context suspended
     *   until the outer `TraceIO` is run.
     * @return a non-strict expression which computes `TraceIO[A]` lifted into a `TraceIO` in
     *   a suspended state until the outer `TraceIO` is run.
     */
    def suspend[A](t: => IO[A]): TraceIO[A] = toTraceIO(IO.suspend(t))

    /**
     * Creates a failed `TraceIO`.
     * @param t - the `Throwable` with which to fail the underlying program.
     * @return the `TraceIO[A]` in a failed state.
     */
    def raiseError[A](t: Throwable): TraceIO[A] = toTraceIO(IO.raiseError(t): IO[A])

    /*
     * Lifts an `IO` which computes `A` into a `TraceIO[A]` context.
     * @param io - an `IO` which computes a value `A`.
     * @return a `TraceIO[A]`
     */
    def toTraceIO[A](io: IO[A]): TraceIO[A] = TraceT { _ => io }
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
     *   val io = IO(some computation)
     *   io.newSpan(
     *     Span.Name("query-products-for-sale",
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   )
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @return a new instance of `TraceT` representing a child span.
     */
    def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Sync[F]): TraceT[F, A] =
      toTraceT.newSpan(spanName, notes: _*)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`,
     * providing the capability of annotating the span with notes based on the execution result of the `F[A]`,
     * using the default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of span recording.
     * For example:
     *   ```
     *   val io = IO(some computation)
     *   io.newAnnotatedSpan(
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
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newAnnotatedSpan(
      spanName: Span.Name,
      notes: Note*
    )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] =
      toTraceT.newAnnotatedSpan(spanName, notes: _*)(resultAnnotator)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`, providing for custom
     * evaluation and rendering of the underlying `F[A]` when recording the [[Span]].
     * For example:
     *   ```
     *   val io = IO(some computation)
     *   io.newSpan(
     *     Span.Name("query-products-for-sale",
     *     Evaluator.resultToFailure[Vector[Product]
     *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
     *   )
     *   ```
     * @param spanName - a descriptive name, emitted when the span is recorded.
     * @param evaluator - an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]].
     * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
     *   execution).
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Sync[F]): TraceT[F, A] =
      toTraceT.newSpan(spanName, evaluator, notes: _*)

    /**
     * Creates a new child [[Span]] in the `TraceT[F, A]` created by lifting this `F[A]`, providing the capability
     * of annotating the span with notes based on the execution result of the `F[A]`, using the
     * a custom [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of recording.
     * For example:
     *   ```
     *   val io = IO(some computation)
     *   io.newAnnotatedSpan(
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
     * @return newTraceT - a new instance of `TraceT` representing a child span.
     */
    def newAnnotatedSpan(
      spanName: Span.Name,
      evaluator: Evaluator[A],
      notes: Note*
    )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] =
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
     * @return a new `TraceT[F, A]` with the error handling of the aforementioned `f` function
     *   parameter.
     */
    def bestEffortOnFinish(f: Option[Throwable] => F[Unit])(implicit F: MonadError[F, Throwable]): F[A] =
      self.attempt flatMap { r =>
        f(r.left.toOption).attempt flatMap { _ => r.fold(F.raiseError, F.pure) }
      }
  }
}
