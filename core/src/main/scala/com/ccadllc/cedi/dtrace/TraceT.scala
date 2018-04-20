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

import cats.{ Applicative, Functor, Monad, MonadError, ~> }
import cats.effect._
import cats.implicits._

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration
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

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => TraceT[F, B]`.
   * @param f function from `A` => `TraceT[F, B]`
   */
  def flatMap[B](f: A => TraceT[F, B])(implicit F: Monad[F]): TraceT[F, B] =
    TraceT { tc => tie(tc).flatMap { f andThen { _.tie(tc) } } }

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => B`.
   * @param f function from `A` => `TraceT[F, B]`
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
   * @param spanName a descriptive name, emitted when the span is recorded.
   * @param notes one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newSpan(spanName: Span.Name, notes: Note*)(implicit F: Sync[F]): TraceT[F, A] =
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
   * @param spanName a descriptive name, emitted when the span is recorded.
   * @param notes one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @param resultsAnnotator a partial function from an `Either[Throwable, A]` to a `Vector[Note]`, providing
   *   for the ability to add additional annotation of the [[Span]] based on the result of the underlying `F[A]`
   *   execution.
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newAnnotatedSpan(
    spanName: Span.Name, notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] =
    newAnnotatedSpan(spanName, Evaluator.default[A], notes: _*)(resultAnnotator)

  /**
   * Creates a new child span from the current span represented by this instance, providing for custom
   * evaluation and rendering of the underlying `F[A]` when recording the [[Span]].
   *   ```
   *   queryProductsTraceT.newSpan(
   *     Span.Name("query-products-for-sale",
   *     Evaluator.resultToFailure[Vector[Product]
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   )
   *   ```
   * @param spanName a descriptive name, emitted when the span is recorded.
   * @param evaluator an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]].
   * @param notes one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*)(implicit F: Sync[F]): TraceT[F, A] =
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
   * @param spanName a descriptive name, emitted when the span is recorded.
   * @param notes one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @param resultsAnnotator a partial function from an `Either[Throwable, A]` to a `Vector[Note]`, providing
   *   for the ability to add additional annotation of the [[Span]] based on the result of the underlying `F[A]` run.
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newAnnotatedSpan(
    spanName: Span.Name,
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] = TraceT { tc =>
    for {
      t <- tc.childSpan(spanName)
      r <- annotatedTrace(t, evaluator, notes: _*)(resultAnnotator)
    } yield r
  }

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`.
   * @param tc the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the virtual machine it is running in).
   * @param notes a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @return the underlying effectful program `F[A]` that, when run, will calculate and record the trace of its execution.
   */
  def trace(tc: TraceContext[F], notes: Note*)(implicit F: Sync[F]): F[A] =
    annotatedTrace(tc, notes: _*)(PartialFunction.empty)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`. In addition, when the effectful program
   * is run, further annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
   * @param tc the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the virtual machine it is running in).
   * @param notes a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @param resultAnnotator a partial function which is passed an `Either[Throwable, A]` as the result of the underlying program's run and which
   *   returns a `Vector` of zero or more [[Note]]s, possibly derived from the input result.
   * @return the underlying effectful program `F[A]` that, when run, will calculate and record the trace of its execution.
   */
  def annotatedTrace(tc: TraceContext[F], notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): F[A] =
    annotatedTrace(tc, Evaluator.default[A], notes: _*)(resultAnnotator)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`, using the passed-in [[Evaluator]]
   * to determine the program's success/failure status.
   * @param tc the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the virtual machine it is running in).
   * @param evaluator an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]]
   * @param notes a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @return the underlying effectful program `F[A]` that, when run, will calculate and record the trace of its execution.
   */
  def trace(tc: TraceContext[F], evaluator: Evaluator[A], notes: Note*)(implicit F: Sync[F]): F[A] =
    annotatedTrace(tc, evaluator, notes: _*)(PartialFunction.empty)

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`, using the passed-in [[Evaluator]]
   * to determine the program's success/failure status. In addition, when the effectful program
   * is run, further annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
   * @param tc the [[TraceContext]] to use in generating the trace -- usually this contains the root [[Span]] (or at least the root
   *   span of trace for the virtual machine it is running in).
   * @param evaluator an [[Evaluator]] which converts either a `Throwable` or `A` to an optional [[FailureDetail]]
   * @param notes a variable argument list of [[Note]]s used to annotate the current [[Span]].
   * @param resultAnnotator a partial function which is passed an `Either[Throwable, A]` as the result of the underlying program's run and which
   *   returns a `Vector` of zero or more [[Note]]s, possibly derived from the input result.
   * @return the underlying effectful program `F[A]` that, when run, will calculate and record the trace of its execution.
   */
  def annotatedTrace(
    tc: TraceContext[F],
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): F[A] =
    tc.updateStartTime flatMap { updated =>
      tie(updated).attempt.flatMap { eOrR =>
        val annotatedTc = updated.setNotes(notes.toVector ++ resultAnnotator.applyOrElse(eOrR, (_: Either[Throwable, A]) => Vector.empty))
        eOrR match {
          case Right(r) => evaluator.resultToFailure(r).fold(annotatedTc.emitSuccess)(updated.emitFailure) map { _ => r }
          case Left(e) => evaluator.exceptionToFailure(e).fold(annotatedTc.emitSuccess)(annotatedTc.emitFailure) flatMap { _ => F.raiseError(e) }
        }
      }
    }

  /**
   * Transforms this `TraceT[F, A]` to a `TraceT[F, Either[Throwable, A]]` where the left-hand side of the
   * `Either` represents a failure of the underlying program `F` and the right-hand side represents the
   * successful result.  It is a way of handling non-fatal errors by turning them into `scala.util.Either` values.
   * @return a `TraceT[F, Either[Throwable, A]]` indicating that the underlying effectful program `F`
   *   will not fail at the program level but rather will indicate success/failure at the application level via its
   *   `Either[Throwable, A]` result type.
   */
  def attempt(implicit F: MonadError[F, Throwable]): TraceT[F, Either[Throwable, A]] = TraceT { tie(_).attempt }

  /**
   * Transforms this `TraceT[F, A]` to an equivalent `TraceT[F, A]` where a best-effort will be made
   * to execute the passed-in function on the finish of the underlying effectful program.  The function can't
   * be guaranteed to run in the face of interrupts, etc.  It depends on the nature of the effectful program
   * itself.
   * @param f function which is passed an optional `Throwable` - defined if the program failed and
   *   returns a `TraceT[F, Unit]`, a program run only for its effect.
   * @return new `TraceT[F, A]` with the error handling of the aforementioned `f` function
   */
  def bestEffortOnFinish(f: Option[Throwable] => TraceT[F, Unit])(implicit F: MonadError[F, Throwable]): TraceT[F, A] =
    TraceT { tc => tie(tc).bestEffortOnFinish(f(_).tie(tc)) }

  /**
   * Handles an error by mapping it to a new `TraceT`.
   * @param f handler function which maps an error to a new `TraceT`, possibly by recovering from it
   * @return new `TraceT[F, A]` with error handling of the aforementioned `f` function
   */
  def handleErrorWith(f: Throwable => TraceT[F, A])(implicit F: MonadError[F, Throwable]): TraceT[F, A] =
    TraceT { tc => tie(tc).handleErrorWith(t => f(t).tie(tc)) }

  /**
   * When passed a top-level [[TraceContext]], convert this `TraceT` into its underlying effectful
   * program enhanced with the capability of tracing its execution.
   * @param tc the top-level [[TraceContext]] to apply to the `TraceT` in order to transform it
   *   to an `F[A]`.
   * @return the underlying `F[A]` enhanced to trace its execution.
   */
  def apply(tc: TraceContext[F]): F[A] = tie(tc)
}

/**
 * The `TraceT` companion provides smart constructors for desired `TraceT` instances and
 * type class isntances.
 */
object TraceT extends TraceTPolyFunctions with TraceTInstances {

  private[dtrace] def apply[F[_], A](tie: TraceContext[F] => F[A]): TraceT[F, A] = new TraceT(tie)

  /**
   * Ask for the current `TraceContext[F]` in a `TraceT`.
   * @return a `TraceContext[F]` wrapped in a `TraceT`.
   */
  def ask[F[_]](implicit F: Applicative[F]): TraceT[F, TraceContext[F]] = apply { F.pure }

  /**
   * Lifts a value `A` into a `TraceT[F, A]`.
   * @param a the pure value `A` to lift into a `TraceT` context.
   * @return a pure value `A` wrapped in a `TraceT`.
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): TraceT[F, A] = toTraceT(F.pure(a))

  /**
   * Lifts the non-strict, possibly impure expression computing `A` into a `TraceT[F, A]`
   * context.
   * @param a the non-strict expression computing `A` to lift into a `TraceT` context.
   * @return a non-strict expression which computes `A` lifted into a `TraceT`.
   */
  def delay[F[_], A](a: => A)(implicit F: Sync[F]): TraceT[F, A] = toTraceT(F.delay(a))

  /**
   * Lifts the non-strict, possibly impure expression computing a `TraceT[F, A]` into a `TraceT[F, A]`.
   * The expression is suspended until the outer `TraceT` returned is run.
   * @param t the non-strict expression computing `TraceT[F, A]` to lift into a `TraceT` context suspended
   *   until the outer `TraceT` is run.
   * @return a non-strict expression which computes `TraceT[F, A]` lifted into a `TraceT` in
   *   a suspended state until the outer `TraceT` is run.
   */
  def suspend[F[_], A](t: => TraceT[F, A])(implicit F: Sync[F]): TraceT[F, A] =
    toTraceT(F.delay(t)).flatMap(identity)

  /**
   * Creates a failed `TraceT`, to create a failed underlying program, lifted to a `TraceT`.
   * @param t the `Throwable` with which to fail the underlying program.
   * @return the `TraceT[F, A]` in a failed state.
   */
  def raiseError[F[_], A](t: Throwable)(implicit F: MonadError[F, Throwable]): TraceT[F, A] = toTraceT(F.raiseError(t): F[A])

  /**
   * Creates a simple, noncancelable `TraceT[F, A]` instance that
   * executes an asynchronous process on evaluation.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[F[_], A](cb: (Either[Throwable, A] => Unit) => Unit)(implicit F: Async[F]): TraceT[F, A] = toTraceT(F.async(cb))

  /**
   * Creates a cancelable `TraceT[F, A]` instance that executes an
   * asynchronous process on evaluation.
   *
   * This builder accepts a registration function that is
   * being injected with a side-effectful callback, to be called
   * when the asynchronous process is complete with a final result.
   */
  def cancelable[F[_], A](k: (Either[Throwable, A] => Unit) => IO[Unit])(implicit F: Concurrent[F]): TraceT[F, A] = toTraceT(F.cancelable(k))

  /**
   * Defines a conversion from [[IO]] in terms of the `Concurrent` type class.
   */
  def liftIO[F[_], A](ioa: IO[A])(implicit F: Concurrent[F]): TraceT[F, A] = toTraceT(F.liftIO(ioa))

  /**
   * Lifts a program `F` which computes `A` into a `TraceT[F, A]` context.
   * @param fa a program `F` which computes a value `A`.
   * @return a `TraceT[F, A]`
   */
  def toTraceT[F[_], A](fa: F[A]): TraceT[F, A] = TraceT { _ => fa }
}

private[dtrace] sealed trait TraceTPolyFunctions {
  /**
   * An instance of the polymorphic function `F` ~> `TraceT[F, ?]` for any effectful program result type `?`
   * is made available in implicit scope.
   */
  implicit def liftToTraceT[F[_]]: F ~> TraceT[F, ?] =
    new (F ~> TraceT[F, ?]) {
      def apply[A](fa: F[A]): TraceT[F, A] = TraceT.toTraceT(fa)
    }
}

private[dtrace] sealed trait TraceTInstances {

  implicit def concurrentEffectTraceTInstance[F[_]: ConcurrentEffect: TraceContext]: ConcurrentEffect[TraceT[F, ?]] = new ConcurrentEffectTraceT[F]
  implicit def timerTraceTInstance[F[_]: Timer]: Timer[TraceT[F, ?]] = new TraceTTimer[F]

  /** A `ConcurrentEffect[TraceT[F, ?]]` typeclass instance given an instance of `ConcurrentEffect[F] and an instance of `TraceContext[F]`. */
  protected class ConcurrentEffectTraceT[F[_]](implicit F: ConcurrentEffect[F], TC: TraceContext[F]) extends ConcurrentEffect[TraceT[F, ?]] {
    override def pure[A](a: A): TraceT[F, A] = TraceT.pure(a)
    override def flatMap[A, B](a: TraceT[F, A])(f: A => TraceT[F, B]): TraceT[F, B] = a flatMap f
    override def delay[A](a: => A): TraceT[F, A] = TraceT.delay(a)
    override def map[A, B](ta: TraceT[F, A])(f: A => B): TraceT[F, B] = ta.map(f)
    override val unit: TraceT[F, Unit] = pure(())
    override def attempt[A](ta: TraceT[F, A]): TraceT[F, Either[Throwable, A]] = ta.attempt
    override def suspend[A](ta: => TraceT[F, A]): TraceT[F, A] = TraceT.suspend(ta)
    override def raiseError[A](err: Throwable): TraceT[F, A] = TraceT.raiseError(err)
    override def handleErrorWith[A](ta: TraceT[F, A])(f: Throwable => TraceT[F, A]): TraceT[F, A] = ta.handleErrorWith(f)

    override def start[A](ta: TraceT[F, A]): TraceT[F, Fiber[TraceT[F, ?], A]] =
      TraceT.toTraceT(F.start(ta.tie(TC)) map toTraceTFiber)

    override def uncancelable[A](ta: TraceT[F, A]): TraceT[F, A] =
      TraceT.toTraceT(F.uncancelable(ta.tie(TC)))

    override def onCancelRaiseError[A](ta: TraceT[F, A], e: Throwable): TraceT[F, A] =
      TraceT.toTraceT(F.onCancelRaiseError(ta.tie(TC), e))

    override def async[A](cb: (Either[Throwable, A] => Unit) => Unit): TraceT[F, A] = TraceT.async(cb)

    override def race[A, B](ta: TraceT[F, A], tb: TraceT[F, B]): TraceT[F, Either[A, B]] =
      TraceT.toTraceT(F.race(ta.tie(TC), tb.tie(TC)))

    override def racePair[A, B](ta: TraceT[F, A], tb: TraceT[F, B]): TraceT[F, Either[(A, Fiber[TraceT[F, ?], B]), (Fiber[TraceT[F, ?], A], B)]] =
      TraceT.toTraceT(F.racePair(ta.tie(TC), tb.tie(TC)) map {
        case Right(((fiba, b))) => Right(toTraceTFiber(fiba) -> b)
        case Left(((a, fibb))) => Left(a -> toTraceTFiber(fibb))
      })

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): TraceT[F, A] = TraceT.cancelable(k)

    override def runCancelable[A](ta: TraceT[F, A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      F.runCancelable(ta.tie(TC))(cb)

    override def runAsync[A](ta: TraceT[F, A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      F.runAsync(ta.tie(TC))(cb)

    override def liftIO[A](ioa: IO[A]): TraceT[F, A] = TraceT.liftIO(ioa)

    override def tailRecM[A, B](a: A)(f: A => TraceT[F, Either[A, B]]): TraceT[F, B] =
      f(a).flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => pure(b)
      }

    override def toString: String = "ConcurrentEffect[TraceT[F, ?]]"

    private def toTraceTFiber[A](faf: Fiber[F, A]): Fiber[TraceT[F, ?], A] = new Fiber[TraceT[F, ?], A] {
      def cancel: TraceT[F, Unit] = TraceT.toTraceT(faf.cancel)
      def join: TraceT[F, A] = TraceT.toTraceT(faf.join)
    }
  }

  /** A `Timer[TraceT[F, ?]]` typeclass instance given an instance of `Timer[F]. */
  protected class TraceTTimer[F[_]](implicit F: Timer[F]) extends Timer[TraceT[F, ?]] {
    override def clockRealTime(unit: TimeUnit): TraceT[F, Long] = TraceT.toTraceT(F.clockRealTime(unit))
    override def clockMonotonic(unit: TimeUnit): TraceT[F, Long] = TraceT.toTraceT(F.clockMonotonic(unit))
    override def sleep(duration: FiniteDuration): TraceT[F, Unit] = TraceT.toTraceT(F.sleep(duration))
    override def shift: TraceT[F, Unit] = TraceT.toTraceT(F.shift)
  }
}
