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
 * This is the main construct of the library.  It represents a function `TraceContext => F[A]`
 * for an arbitrary `F[_]`, conceptually similiar to a `cats.data.Kleisli`.  The [[TraceContext]]
 * holds the "current" [[Span]] information for the program `F[A]` and this information, along
 * with timing and result data derived when `F[A]` is run, is recorded via the `Emitter`,
 * also included in the [[TraceContext]], when the `F[A]` execution is complete.  This class is never
 * instantiated by API users; rather, instances are created via as needed via the public instance
 * and companion object methods described below.
 */
final class TraceT[F[_], A](private[dtrace] val tie: TraceContext[F] => F[A]) { self =>
  import syntax._

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => TraceT[F, B]`.
   * @param f - function from `A` => `TraceT[F, B]`
   */
  def flatMap[B](f: A => TraceT[F, B])(implicit F: Monad[F]): TraceT[F, B] =
    TraceT { tc => tie(tc).flatMap { f andThen { _.tie(tc) } } }

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => B`.
   * @param f - function from `A` => `TraceT[F, B]`
   */
  def map[B](f: A => B)(implicit F: Functor[F]): TraceT[F, B] =
    TraceT { tc => tie(tc).map(f) }

  /**
   * Creates a new child [[Span]] from the current span represented by this instance, using the
   * default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of span recording.
   * For example:
   *   ```
   *   queryProductsTraceT.newSpan(
   *     Span.Name("query-products-for-sale",
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   )
   *   ```
   * @param spanName - a descriptive name, emitted when the span is recorded.
   * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
    newAnnotatedSpan(spanName, notes: _*)(PartialFunction.empty)

  /**
   * Creates a new child [[Span]] from the current span represented by this instance, providing the capability
   * of annotating the span with notes based on the execution result of the `F[A]`, using the
   * default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of recording.
   * For example:
   *   ```
   *   queryProductsTraceT.newAnnotatedSpan(
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
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newAnnotatedSpan(
    spanName: Span.Name, notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
    newAnnotatedSpan(spanName, Evaluator.default[A], notes: _*)(resultAnnotator)

  /**
   * Creates a new child span from the current span represented by this instance, providing for custom
   * evaluation and rendering of the underlying `F[A]` when recording the [[Span]]
   *   ```
   *   queryProductsTraceT.newSpan(
   *     Span.Name("query-products-for-sale",
   *     Evaluator.resultToFailure[Vector[Product]
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   )
   *   ```
   * @param spanName - a descriptive name, emitted when the span is recorded.
   * @param evaluator - an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]]
   * @param notes - one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   */
  def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] =
    newAnnotatedSpan(spanName, evaluator, notes: _*)(PartialFunction.empty)

  /**
   * Creates a new child [[Span]] from the current span represented by this instance, providing the capability
   * of annotating the span with notes based on the execution result of the `F[A]`, using the
   * a custom [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of recording.
   * For example:
   *   ```
   *   queryProductsTraceT.newAnnotatedSpan(
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
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newAnnotatedSpan(
    spanName: Span.Name,
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, A] = TraceT { tc =>
    for {
      t <- tc.childSpan(spanName)
      r <- annotatedTrace(t, evaluator, notes: _*)(resultAnnotator)
    } yield r
  }

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`.
   * @param tc - the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the VM it is running in).
   * @param notes - a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @return effectfulProgram - the underlying effectful program `F[A]` that, when run, will calculate information for and record the associated [[Span]].
   */
  def trace(tc: TraceContext[F], notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): F[A] =
    annotatedTrace(tc, notes: _*)(PartialFunction.empty)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`. In addition, when the effectful program
   * is run, further annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
   * @param tc - the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the VM it is running in).
   * @param notes - a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @param resultAnnotator - a partial function which is passed an `Either[Throwable, A]` as the result of the underlying program's run and which
   *   returns a `Vector` of zero or more [[Note]]s, possibly derived from the input result.
   * @return effectfulProgram - the underlying effectful program `F[A]` that, when run, will calculate information for and record the associated [[Span]].
   */
  def annotatedTrace(tc: TraceContext[F], notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): F[A] =
    annotatedTrace(tc, Evaluator.default[A], notes: _*)(resultAnnotator)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`, using the passed-in [[Evaluator]]
   * to determine the program's success/failure status.
   * @param tc - the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the VM it is running in).
   * @param evaluator - an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]]
   * @param notes - a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @return effectfulProgram - the underlying effectful program `F[A]` that, when run, will calculate information for and record the associated [[Span]].
   */
  def trace(tc: TraceContext[F], evaluator: Evaluator[A], notes: Note*)(implicit F: Catchable[F] with Suspendable[F]): F[A] =
    annotatedTrace(tc, evaluator, notes: _*)(PartialFunction.empty)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`, using the passed-in [[Evaluator]]
   * to determine the program's success/failure status. In addition, when the effectful program
   * is run, further annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
   * @param tc - the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the VM it is running in).
   * @param evaluator - an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]]
   * @param notes - a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @param resultAnnotator - a partial function which is passed an `Either[Throwable, A]` as the result of the underlying program's run and which
   *   returns a `Vector` of zero or more [[Note]]s, possibly derived from the input result.
   * @return effectfulProgram - the underlying effectful program `F[A]` that, when run, will calculate information for and record the associated [[Span]].
   */
  def annotatedTrace(
    tc: TraceContext[F],
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Catchable[F] with Suspendable[F]): F[A] =
    tc.updateStartTime flatMap { updated =>
      tie(updated).attempt.flatMap { eOrR =>
        val annotatedTc = updated.setNotes(notes.toVector ++ resultAnnotator.applyOrElse(eOrR, (_: Either[Throwable, A]) => Vector.empty))
        eOrR match {
          case Right(r) => evaluator.resultToFailure(r).fold(annotatedTc.emitSuccess)(updated.emitFailure) map { _ => r }
          case Left(e) => evaluator.exceptionToFailure(e).fold(annotatedTc.emitSuccess)(annotatedTc.emitFailure) flatMap { _ => F.fail(e) }
        }
      }
    }

  /**
   * Transforms this `TraceT[F, A]` to a `TraceT[F, Either[Throwable, A]]` where the left-hand side of the
   * `Either` represents a failure of the underlying program `F` as captured by the `fs2.util.Catchable[F]`
   * in implicit scope and the right-hand side represents the successful result.
   */
  def attempt(implicit F: Catchable[F]): TraceT[F, Either[Throwable, A]] = TraceT { tie(_).attempt }

  /**
   * Transforms this `TraceT[F, A]` to an equivalent `TraceT[F, A]` where a best-effort will be made
   * to execute the passed-in function on the finish of the underlying effectful program.  The function can't
   * be guaranteed to run in the face of interrupts, etc.  It depends on the nature of the effectful program
   * itself.
   * @param f - a function which is passed an optional `Throwable` - defined if the program failed and
   *   returns a `TraceT[F, Unit]`, a program run only for its effect.
   * @return effectfulProgram - the underlying effectful program `F[A]` that, when run to completion or with an error, will
   *   in turn execute the passed-in function prior to returning the original program result.
   */
  def bestEffortOnFinish(f: Option[Throwable] => TraceT[F, Unit])(implicit F: Catchable[F]): TraceT[F, A] =
    TraceT { tc => tie(tc).bestEffortOnFinish(f(_).tie(tc)) }

  def handle[B >: A](f: PartialFunction[Throwable, B])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, B] =
    handleWith(f andThen TraceT.pure[F, B])

  def handleWith[B >: A](f: PartialFunction[Throwable, TraceT[F, B]])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, B] =
    attempt flatMap {
      case Right(r) => TraceT.pure[F, B](r)
      case Left(err) => if (f.isDefinedAt(err)) f(err) else TraceT.fail[F, B](err)
    }

  def handleAllWith[B >: A](f: Throwable => TraceT[F, B])(implicit F: Catchable[F] with Suspendable[F]): TraceT[F, B] = attempt.flatMap(_.fold(f, TraceT.pure[F, B]))

  /**
   * When passed a top-level [[TraceContext]], convert this `TraceT` into its underlying effectful
   * program enhanced with the capability of tracing its execution.
   * @param tc - the top-level [[TraceContext]] to apply to the `TraceT` in order to transform it
   *   to an `F[A]`.
   * @return effectfulProgram - the underlying `F[A]` enhanced to trace its execution.
   */
  def apply(tc: TraceContext[F]): F[A] = tie(tc)
}

/**
 * The `TraceT` companion provides smart constructors for desired `TraceT` instances and
 * `fs2.util.Async` and `fs2.util.Effect` typeclasses.
 */
object TraceT extends TraceTPolyFunctions with TraceTInstances {

  private[dtrace] def apply[F[_], A](tie: TraceContext[F] => F[A]): TraceT[F, A] = new TraceT(tie)

  /**
   * Ask for the current `TraceContext[F]` in a `TraceT` given a `fs2.util.Applicative[F]` in implicit
   * scope.
   * @param F - the `fs2.util.Applicative[F]` in implicit scope.
   * @return traceTOfTraceContext - a `TraceContext[F]` wrapped in a `TraceT`.
   */
  def ask[F[_]](implicit F: Applicative[F]): TraceT[F, TraceContext[F]] = apply { F.pure }

  /**
   * Lifts a value `A` into a `TraceT[F, A]` context provided there is an instance of
   * `fs2.util.Applicative[F]` in implicit scope.
   * @param a - the pure value `A` to lift into a `TraceT` context.
   * @param F - the `fs2.util.Applicative[F]` in implicit scope.
   * @return traceTOfA - a pure value `A` wrapped in a `TraceT`.
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): TraceT[F, A] = toTraceT(F.pure(a))

  /**
   * Lifts the non-strict, possibly impure expression computing `A` into a `TraceT[F, A]`
   * context provided there is an instance of `fs2.util.Suspendable[F]` in implicit scope.
   * @param a - the non-strict expression computing `A` to lift into a `TraceT` context.
   * @param F - the `fs2.util.Suspendable[F]` in implicit scope.
   * @return traceTOfA - a non-strict expression which computes `A` lifted into a `TraceT`.
   */
  def delay[F[_], A](a: => A)(implicit F: Suspendable[F]): TraceT[F, A] = toTraceT(F.delay(a))

  /**
   * Lifts the non-strict, possibly impure expression computing a `TraceT[A]` into a `TraceT[F, A]`
   * provided there is an instance of `fs2.util.Suspendable[F]` in implicit scope.  The expression is
   * suspended until the outer [[TraceT]] returned is run.
   * @param t - the non-strict expression computing `TraceT[A]` to lift into a `TraceT` context suspended
   *   until the outer [[TraceT]] is run.
   * @param F - the `fs2.util.Suspendable[F]` in implicit scope.
   * @return traceTOfTA - a non-strict expression which computes `TraceT[A]` lifted into a `TraceT` in
   *   a suspended state until the outer [[TraceT]] is run.
   */
  def suspend[F[_], A](t: => TraceT[F, A])(implicit F: Suspendable[F]): TraceT[F, A] =
    toTraceT(F.delay(t)).flatMap(identity)

  /**
   * Creates a failed `TraceT, using the instance of `fs2.util.Catchable[F]` in implicit scope
   * to create a failed underlying program, lifted to a [[TraceT]].
   * @param t - the `Throwable` with which to fail the underlying program.
   * @param F - the `fs2.util.Catchable[F]` in implicit scope used to create the failed program `F`.
   * @return traceTOfA - the `TraceT[F, A]` in a failed state.
   */
  def fail[F[_], A](t: Throwable)(implicit F: Catchable[F]): TraceT[F, A] = toTraceT(F.fail(t): F[A])

  /**
   * Lifts a program `F` which computes `A` into a `TraceT[F, A]` context.
   * @param fa - a program `F` which computes a value `A`.
   * @return traceTOfA - a `TraceT[F, A]`
   */
  def toTraceT[F[_], A](fa: F[A]): TraceT[F, A] = TraceT { _ => fa }
}

private[dtrace] sealed trait TraceTPolyFunctions {
  implicit def liftToTraceT[F[_]]: F ~> TraceT[F, ?] =
    new (F ~> TraceT[F, ?]) {
      def apply[A](fa: F[A]): TraceT[F, A] = TraceT.toTraceT(fa)
    }
}

private[dtrace] sealed trait TraceTInstancesLowPriority {
  implicit def effectTraceTInstance[F[_]](implicit F: Effect[F]): Effect[TraceT[F, ?]] = new EffectTraceT[F]

  protected class EffectTraceT[F[_]](implicit F: Effect[F]) extends Effect[TraceT[F, ?]] {
    def pure[A](a: A): TraceT[F, A] = TraceT.pure(a)
    def flatMap[A, B](a: TraceT[F, A])(f: A => TraceT[F, B]): TraceT[F, B] = a flatMap f
    override def delay[A](a: => A): TraceT[F, A] = TraceT.delay(a)
    def suspend[A](fa: => TraceT[F, A]): TraceT[F, A] = TraceT.suspend(fa)
    def fail[A](err: Throwable): TraceT[F, A] = TraceT.fail(err)
    def attempt[A](t: TraceT[F, A]): TraceT[F, Attempt[A]] = t.attempt
    def unsafeRunAsync[A](t: TraceT[F, A])(cb: Attempt[A] => Unit): Unit =
      t.tie(TraceContext.empty) unsafeRunAsync (cb)
    override def toString: String = "Effect[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTInstances extends TraceTInstancesLowPriority {
  implicit def asyncTraceTInstance[F[_]](implicit AF: Async[F]): Async[TraceT[F, ?]] = new EffectTraceT[F] with Async[TraceT[F, ?]] {
    import syntax._

    def ref[A]: TraceT[F, Async.Ref[TraceT[F, ?], A]] =
      TraceT { _ => Async.ref[F, A](AF) map { new TraceTRef(_, this) } }

    def edit[A, B](ta: TraceT[F, A])(f: F[A] => F[B]): TraceT[F, B] = TraceT { tc => f(ta.tie(tc)) }

    override def toString = "Async[TraceT[F, ?]]"

    class TraceTRef[A](ref: Async.Ref[F, A], protected val F: Async[TraceT[F, ?]]) extends Async.Ref[TraceT[F, ?], A] {
      def access: TraceT[F, (A, Either[Throwable, A] => TraceT[F, Boolean])] = TraceT { _ =>
        ref.access map { case (a, e2r) => (a, e2r andThen { _.toTraceT }) }
      }
      def set(a: TraceT[F, A]): TraceT[F, Unit] = edit(a) { t => ref.set(t) }

      override def get: TraceT[F, A] = TraceT { _ => ref.get }

      def cancellableGet: TraceT[F, (TraceT[F, A], TraceT[F, Unit])] = TraceT { _ =>
        ref.cancellableGet map { case (tta, ttu) => (tta.toTraceT, ttu.toTraceT) }
      }
    }
  }
}
