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

import cats._
import cats.effect._
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{ FiniteDuration, TimeUnit }
import scala.language.higherKinds

/**
 * This is the main construct of the library.  It represents a function `TraceContext => F[A]`
 * for an arbitrary `F[_]` effectful program, conceptually similiar to a `cats.data.Kleisli`.
 * The [[TraceContext]] holds the "current" [[Span]] information for the program `F[A]` and
 * this information, along with timing and result data derived when `F[A]` is run, is recorded
 * via the `Emitter`, also included in the [[TraceContext]], when the `F[A]` execution is complete.
 * This class is never instantiated by API users; rather, instances are created via as needed via
 * the public instance and companion object methods described below.
 * @tparam F - a type constructor representing the effect which is traced.
 * @tparam A - the result of the effect which is traced.
 */
final class TraceT[F[_], A](private[dtrace] val toEffect: TraceContext[F] => F[A]) { self =>

  /**
   * Given the (usually) root [[TraceContext]], convert this `TraceT[A]` to its underlying effectful program `F[A]`. In addition, when the effectful program
   * is run, annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
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
   * to determine the program's success/failure status. In addition, when the effectful program
   * is run, annotate the associated [[Span]] with [[Note]]s derived from the result of its run, using the passed-in function.
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
    notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): F[A] =
    tc.updateStartTime flatMap { updated =>
      toEffect(updated).attempt.flatMap { eOrR =>
        val annotatedTc = updated.setNotes(notes.toVector ++ resultAnnotator.applyOrElse(eOrR, (_: Either[Throwable, A]) => Vector.empty))
        eOrR match {
          case Right(r) => evaluator.resultToFailure(r).fold(annotatedTc.emitSuccess)(annotatedTc.emitFailure) map { _ => r }
          case Left(e) => evaluator.exceptionToFailure(e).fold(annotatedTc.emitSuccess)(annotatedTc.emitFailure) flatMap { _ => F.raiseError(e) }
        }
      }
    }

  /**
   * When passed a top-level [[TraceContext]], convert this `TraceT` into its underlying effectful
   * program enhanced with the capability of tracing its execution.
   * @param tc the top-level [[TraceContext]] to apply to the `TraceT` in order to transform it
   *   to an `F[A]`.
   * @return the underlying `F[A]` enhanced to trace its execution.
   */
  def apply(tc: TraceContext[F]): F[A] = toEffect(tc)

  /**
   * Transforms this `TraceT[F, A]` to a `TraceT[F, Either[Throwable, A]]` where the left-hand side of the
   * `Either` represents a failure of the underlying program `F` and the right-hand side represents the
   * successful result.  It is a way of handling non-fatal errors by turning them into `scala.util.Either` values.
   * @return a `TraceT[F, Either[Throwable, A]]` indicating that the underlying effectful program `F`
   *   will not fail at the program level but rather will indicate success/failure at the application level via its
   *   `Either[Throwable, A]` result type.
   */
  def attempt(implicit F: MonadError[F, Throwable]): TraceT[F, Either[Throwable, A]] =
    TraceT.suspendEffect(toEffect(_).attempt)

  /**
   * Transforms this `TraceT[F, A]` to an equivalent `TraceT[F, A]` where a best-effort will be made
   * to execute the passed-in function on the finish of the underlying effectful program.  The function can't
   * be guaranteed to run in the face of interrupts, etc.  It depends on the nature of the effectful program
   * itself.
   * @param f function which is passed an optional `Throwable` - defined if the program failed and
   *   returns a `TraceT[F, Unit]`, a program run only for its effect.
   * @return new `TraceT[F, A]` with the error handling of the aforementioned `f` function
   */
  @deprecated("Use guaranteeCase instead", "1.5.0")
  def bestEffortOnFinish(f: Option[Throwable] => TraceT[F, Unit])(implicit F: MonadError[F, Throwable]): TraceT[F, A] =
    TraceT.suspendEffect(tc => toEffect(tc).bestEffortOnFinish(f(_).toEffect(tc)))

  /**
   * Returns a `TraceT[F, ?]` action that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`.
   *
   * The `bracket` operation is the equivalent of the
   * `try {} catch {} finally {}` statements from mainstream languages.
   *
   * The `bracket` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation, or in case of cancellation.
   *
   * If an exception is raised, then `bracket` will re-raise the
   * exception ''after'' performing the `release`. If the resulting
   * task gets canceled, then `bracket` will still perform the
   * `release`, but the yielded task will be non-terminating
   * (equivalent with `IO.never` when `F` is `IO`, for example]).
   *
   * See `IO.bracket` scaladoc for further detail.
   *
   * @see [[bracketCase]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        the task returned by this `bracket` function
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the resource that needs to
   *        be released
   */
  def bracket[B](use: A => TraceT[F, B])(release: A => TraceT[F, Unit])(implicit F: Bracket[F, Throwable]): TraceT[F, B] =
    bracketCase(use)((a, _) => release(a))

  /**
   * Returns a new `TraceT[F, ?]` task that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`, with the possibility of
   * distinguishing between normal termination and cancellation, such
   * that an appropriate release of resources can be executed.
   *
   * The `bracketCase` operation is the equivalent of
   * `try {} catch {} finally {}` statements from mainstream languages
   * when used for the acquisition and release of resources.
   *
   * The `bracketCase` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation, or in case of cancellation.
   *
   * In comparison with the simpler [[bracket]] version, this one
   * allows the caller to differentiate between normal termination,
   * termination in error and cancellation via an `ExitCase`
   * parameter.
   *
   * @see [[bracket]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        this function on evaluation
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the resource that needs that
   *        needs release, along with the result of `use`
   *        (cancellation, error or successful result)
   */
  def bracketCase[B](use: A => TraceT[F, B])(release: (A, ExitCase[Throwable]) => TraceT[F, Unit])(implicit F: Bracket[F, Throwable]): TraceT[F, B] =
    TraceT.suspendEffect(tc => F.bracketCase(toEffect(tc))(use(_).toEffect(tc))(release(_, _).toEffect(tc)))

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => TraceT[F, B]`.
   * @param f function from `A` => `TraceT[F, B]`
   * _Note that because `TraceT[F, ?]` needs to apply its function of `TraceContext[F] => F[?]` in order to
   * map over the underlying value, in order for it to be stack-safe, `F` needs to have a `Monad[F]` in
   * implicit scope in order to create a pure F in the suspension of the effect.  This isn't a compile-time
   * requirement as you can have a non-stack-safe flatMap with just a `FlatMap[F]` in scope  as long
   * as you know you will not have deeply nested maps._
   */
  def flatMap[B](f: A => TraceT[F, B])(implicit F: FlatMap[F]): TraceT[F, B] =
    TraceT.suspendEffect(tc => toEffect(tc).flatMap(f(_).toEffect(tc)))

  /**
   * Returns a `TraceT[F, ?]` action that ensures the `finalizer` is always run.
   *
   * The `guarantee` operation is the equivalent of the
   * `try {} catch {} finally {}` statements from mainstream languages.
   *
   * @see [[guaranteeCase]]
   *
   * @param finalizer is an action that gets executed whoever this trace
   *        terminates, either normally or in error, or if it gets
   *        canceled
   */
  def guarantee(finalizer: TraceT[F, Unit])(implicit F: Bracket[F, Throwable]): TraceT[F, A] =
    guaranteeCase(_ => finalizer)

  /**
   * Returns a new `TraceT[F, ?]` task that ensures the 'finalizer'
   * function is always run, with the possibility of
   * distinguishing between normal termination and cancellation.
   * during the computation, or in case of cancellation.
   *
   * In comparison with the simpler [[guarantee]] version, this one
   * allows the caller to differentiate between normal termination,
   * termination in error and cancellation via an `ExitCase`
   * parameter.
   *
   * @see [[guarantee]]
   *
   * @param finalizer is a function that gets called after this trace
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the result of `use`
   *        (cancellation, error or successful result)
   */
  def guaranteeCase(finalizer: ExitCase[Throwable] => TraceT[F, Unit])(implicit F: Bracket[F, Throwable]): TraceT[F, A] =
    TraceT.suspendEffect(tc => F.guaranteeCase(toEffect(tc))(finalizer(_).toEffect(tc)))

  /**
   * Handles an error by mapping it to a new `TraceT`.
   * @param f handler function which maps an error to a new `TraceT`, possibly by recovering from it
   * @return new `TraceT[F, A]` with error handling of the aforementioned `f` function
   */
  def handleErrorWith(f: Throwable => TraceT[F, A])(implicit F: MonadError[F, Throwable]): TraceT[F, A] =
    TraceT.suspendEffect(tc => toEffect(tc).handleErrorWith(t => f(t).toEffect(tc)))

  /**
   * Generates a new `TraceT[F, B]` from this instance using the supplied function `A => B`.
   * @param f function from `A` => `B`
   * _Note that because `TraceT[F, ?]` needs to apply its function of `TraceContext[F] => F[?]` in order to
   * map over the underlying value, in order for it to be stack-safe, `F` needs to have a `Monad[F]` in
   * implicit scope.  This isn't a compile-time requirement as you can have a non-stack-safe map as long
   * as you know you will not have deeply nested maps._
   */
  def map[B](f: A => B)(implicit F: Functor[F]): TraceT[F, B] =
    TraceT.suspendEffect(toEffect(_).map(f))

  /**
   * Creates a new child [[Span]] from the current span represented by this instance, using the
   * default [[Evaluator]] to determine success/failure of the `F[A]` for the purposes of span recording.
   * For example:
   *   {{{
   *   queryProductsTraceT.newSpan(
   *     Span.Name("query-products-for-sale",
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   )
   *   }}}
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
   *   {{{
   *   queryProductsTraceT.newAnnotatedSpan(
   *     Span.Name("query-products-for-sale",
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   ) {
   *     case Right(saleProducts) => Vector(Note.string("sale-products", saleProducts.mkString(",")))
   *   }
   *   }}}
   * @param spanName a descriptive name, emitted when the span is recorded.
   * @param notes one or more [[Note]]s which annotate the span (often the input parameters to the `F[A]`
   *   execution).
   * @param resultsAnnotator a partial function from an `Either[Throwable, A]` to a `Vector[Note]`, providing
   *   for the ability to add additional annotation of the [[Span]] based on the result of the underlying `F[A]`
   *   execution.
   * @return a new instance of `TraceT` representing a child span of `this`.
   */
  def newAnnotatedSpan(
    spanName: Span.Name, notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] =
    newAnnotatedSpan(spanName, Evaluator.default[A], notes: _*)(resultAnnotator)

  /**
   * Creates a new child span from the current span represented by this instance, providing for custom
   * evaluation and rendering of the underlying `F[A]` when recording the [[Span]].
   *   {{{
   *   queryProductsTraceT.newSpan(
   *     Span.Name("query-products-for-sale",
   *     Evaluator.resultToFailure[Vector[Product]
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   )
   *   }}}
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
   *   {{{
   *   queryProductsTraceT.newAnnotatedSpan(
   *     Span.Name("query-products-for-sale",
   *     Note.string("sale-date", date.toString), Note.double("sale-max-price", 80.50)
   *   ) {
   *     case Right(saleProducts) => Vector(Note.string("sale-products", saleProducts.mkString(",")))
   *   }
   *   }}}
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
    notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]])(implicit F: Sync[F]): TraceT[F, A] = TraceT.suspendEffect { tc =>
    tc.childSpan(spanName).flatMap { annotatedTrace(_, evaluator, notes: _*)(resultAnnotator) }
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
}

/**
 * The `TraceT` companion provides smart constructors for desired `TraceT` instances and
 * type class isntances.
 */
object TraceT extends TraceTPolyFunctions with TraceTInstances {

  private[dtrace] def apply[F[_], A](toEffect: TraceContext[F] => F[A]): TraceT[F, A] = new TraceT(toEffect)

  /**
   * Ask for the current `TraceContext[F]` in a `TraceT`.
   * @return a `TraceContext[F]` wrapped in a `TraceT`.
   */
  def ask[F[_]](implicit F: Applicative[F]): TraceT[F, TraceContext[F]] = apply { F.pure }

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
   * Creates a simple, noncancelable `TraceT[F, A]` instance that
   * executes an asynchronous process on evaluation. Differentiated from `async`
   * in that the callback returns an effect.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def asyncF[F[_], A](cb: (Either[Throwable, A] => Unit) => TraceT[F, Unit])(implicit F: Async[F]): TraceT[F, A] =
    TraceT.suspendEffect { tc => F.asyncF(cb(_).toEffect(tc)) }

  /**
   * Creates a cancelable `TraceT[F, A]` instance that executes an
   * asynchronous process on evaluation.
   *
   * This builder accepts a registration function that is
   * being injected with a side-effectful callback, to be called
   * when the asynchronous process is complete with a final result.
   */
  def cancelable[F[_], A](k: (Either[Throwable, A] => Unit) => CancelToken[TraceT[F, ?]])(implicit F: Concurrent[F]): TraceT[F, A] =
    TraceT.suspendEffect { tc => F.cancelable(k(_).toEffect(tc)) }

  /**
   * Generate a `ContextShift[TraceT[F, ?]]` given a `ContextShift[F] and `Monad[F]` in implicit scope.
   * The context shift data structure provides a means by which a program can be evaluated on a thread pool
   * (really, an `scala.concurrent.ExecutionContext` representing the pool) and then will shift back to the
   * `ExecutionContext` it was created with.
   */
  def contextShift[F[_]: Monad: ContextShift]: ContextShift[TraceT[F, ?]] = implicitly[ContextShift[TraceT[F, ?]]]

  /**
   * Lifts the non-strict, possibly impure expression computing `A` into a `TraceT[F, A]`
   * context.
   * @param a the non-strict expression computing `A` to lift into a `TraceT` context.
   * @return a non-strict expression which computes `A` lifted into a `TraceT`.
   */
  def delay[F[_], A](a: => A)(implicit F: Sync[F]): TraceT[F, A] = toTraceT(F.delay(a))

  /**
   * Defines a conversion from `IO` in terms of the `Concurrent` type class.
   */
  def liftIO[F[_], A](ioa: IO[A])(implicit F: Concurrent[F]): TraceT[F, A] = toTraceT(F.liftIO(ioa))

  /**
   * Lifts a value `A` into a `TraceT[F, A]`.
   * @param a the pure value `A` to lift into a `TraceT` context.
   * @return a pure value `A` wrapped in a `TraceT`.
   */
  def pure[F[_], A](a: A)(implicit F: Applicative[F]): TraceT[F, A] = toTraceT(F.pure(a))

  /**
   * Creates a failed `TraceT`, to create a failed underlying program, lifted to a `TraceT`.
   * @param t the `Throwable` with which to fail the underlying program.
   * @return the `TraceT[F, A]` in a failed state.
   */
  def raiseError[F[_], A](t: Throwable)(implicit F: MonadError[F, Throwable]): TraceT[F, A] = toTraceT(F.raiseError(t): F[A])

  /**
   * Asynchronous boundary described as an effectful `TraceT[F, Unit]` managed
   * by the provided `ContextShift`.
   *
   * This operation can be used in `flatMap` chains to "shift" the
   * continuation of the run-loop to another thread or call stack.
   */
  def shift[F[_]](implicit cs: ContextShift[TraceT[F, ?]]): TraceT[F, Unit] = cs.shift

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
   * Generates a `Timer[TraceT[F, ?]]` given a `Timer[F]` in implicit scope.
   */
  def timer[F[_]: Timer]: Timer[TraceT[F, ?]] = implicitly[Timer[TraceT[F, ?]]]

  /**
   * Lifts a program `F` which computes `A` into a `TraceT[F, A]` context.
   * @param fa a program `F` which computes a value `A`.
   * @return a `TraceT[F, A]` wrapping the passed in effectful program.
   */
  def toTraceT[F[_], A](fa: F[A]): TraceT[F, A] = TraceT { _ => fa }

  /**
   * Internal API — suspends the execution of `toEffect` in the `F` context.
   *
   * Used to build `TraceT[F, ?]` values for `F[_]` data types that implement `Monad`,
   * in which case it is safer to trigger the `F[_]` context earlier.
   *
   * The basic requirement of `F` for callers of this function is `Functor`; however we are
   * doing discrimination based on inheritance and if we detect a
   * `Monad`, then we use it to trigger the `F[_]` context earlier.
   *
   * Triggering the `F[_]` context earlier is important to avoid stack
   * safety issues for `F` monads that have stack safe implementations.
   * For example `Eval` or `IO`. Without this the `Monad`
   * instance is stack unsafe, even if the underlying `F` is stack safe.
   *
   * TODO: Look into a better construct such that we don't have to depend on the
   * underlying `F` having a `Monad[F]` in order to make the overall `TraceT[F, ?]`
   * stacksafe as it is very brittle and non-type-safe.
   *
   * This was taken pretty much whole cloth from [[https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/data/Kleisli.scala#L104 Kleisli]]
   * in order to make `cats-effect` typeclasses which are `Kleisli` stack-safe.
   */
  private[dtrace] def suspendEffect[F[_], A](toEffect: TraceContext[F] => F[A])(implicit F: Functor[F]): TraceT[F, A] = F match {
    case m: Monad[F] @unchecked => TraceT { tc => m.flatMap(m.pure(tc))(toEffect) }
    case _ => TraceT(toEffect)
  }
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

private[dtrace] sealed trait TraceTInstances extends TraceTConcurrentEffectInstance
  with TraceTTimerInstance
  with TraceTContextShiftInstance
  with TraceTParallelInstances

private[dtrace] sealed trait TraceTConcurrentEffectInstance extends TraceTConcurrentInstance with TraceTEffectInstance {

  implicit def concurrentEffectTraceTInstance[F[_]: ConcurrentEffect: TraceContext]: ConcurrentEffect[TraceT[F, ?]] = new ConcurrentEffectTraceT[F]

  /**
   * A `ConcurrentEffect[TraceT[F, ?]]` typeclass instance given an instance of `ConcurrentEffect[F]` and an
   * instance of `TraceContext[F]`.
   * Note that this typeclass and the `Effect[TraceT[F, ?]]` typeclass are the only instances requiring
   * an implicit `TraceContext[F]` in scope.  This is because the tracing must be terminated and the underlying
   * `F` effect exposed in order to ultimately derive a `SyncIO`, which the `runAsync` function of `Effect` and
   * `runCancelable` function of `ConcurrentEffect` return, and in order to do that, a `TraceContext[F]` must be
   * made available.
   */
  protected class ConcurrentEffectTraceT[F[_]](implicit F: ConcurrentEffect[F], TC: TraceContext[F]) extends ConcurrentTraceT[F] with ConcurrentEffect[TraceT[F, ?]] {

    override def runAsync[A](ta: TraceT[F, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(ta.trace(TC))(cb)

    override def runCancelable[A](ta: TraceT[F, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[TraceT[F, ?]]] =
      F.runCancelable(ta.trace(TC))(cb).map(TraceT.toTraceT(_))

    override def toString: String = "ConcurrentEffect[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTConcurrentInstance extends TraceTAsyncInstance {

  implicit def concurrentTraceTInstance[F[_]: Concurrent]: Concurrent[TraceT[F, ?]] = new ConcurrentTraceT[F]

  /** A `Concurrent[TraceT[F, ?]]` typeclass instance given an instance of `Concurrent[F]`. */
  protected class ConcurrentTraceT[F[_]](implicit F: Concurrent[F]) extends AsyncTraceT[F] with Concurrent[TraceT[F, ?]] {
    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[TraceT[F, ?]]): TraceT[F, A] =
      TraceT.suspendEffect { tc => F.cancelable(k(_).toEffect(tc)) }

    override def start[A](ta: TraceT[F, A]): TraceT[F, Fiber[TraceT[F, ?], A]] =
      TraceT.suspendEffect { tc => F.start(ta.toEffect(tc)) map toTraceTFiber }

    override def racePair[A, B](ta: TraceT[F, A], tb: TraceT[F, B]): TraceT[F, Either[(A, Fiber[TraceT[F, ?], B]), (Fiber[TraceT[F, ?], A], B)]] =
      TraceT.suspendEffect { tc =>
        F.racePair(ta.toEffect(tc), tb.toEffect(tc)) map {
          case Right(((fiba, b))) => Right(toTraceTFiber(fiba) -> b)
          case Left(((a, fibb))) => Left(a -> toTraceTFiber(fibb))
        }
      }

    override def race[A, B](ta: TraceT[F, A], tb: TraceT[F, B]): TraceT[F, Either[A, B]] =
      TraceT.suspendEffect { tc => F.race(ta.toEffect(tc), tb.toEffect(tc)) }

    override def liftIO[A](ioa: IO[A]): TraceT[F, A] = Concurrent.liftIO(ioa)(this)

    override def toString: String = "Concurrent[TraceT[F, ?]]"

    private def toTraceTFiber[A](faf: Fiber[F, A]): Fiber[TraceT[F, ?], A] = new Fiber[TraceT[F, ?], A] {
      def cancel: TraceT[F, Unit] = TraceT.toTraceT(faf.cancel)
      def join: TraceT[F, A] = TraceT.toTraceT(faf.join)
    }
  }
}

private[dtrace] sealed trait TraceTEffectInstance extends TraceTAsyncInstance {

  implicit def effectTraceTInstance[F[_]: Effect: TraceContext]: Effect[TraceT[F, ?]] = new EffectTraceT[F]

  /**
   * An `Effect[TraceT[F, ?]]` typeclass instance given an instance of `Effect[F]` and an instance of `TraceContext[F]`.
   * Note that this typeclass and the `ConcurrentEffect[TraceT[F, ?]]` typeclass are the only instances requiring an
   * implicit `TraceContext[F]` in scope.  This is because the tracing must be terminated and the underlying `F` effect
   * exposed in order to ultimately derive a `SyncIO`, which the `runAsync` function of `Effect` and `runCancelable`
   * function of `ConcurrentEffect` return, and in order to do that, a `TraceContext[F]` must be made available.
   */
  protected class EffectTraceT[F[_]](implicit F: Effect[F], TC: TraceContext[F]) extends AsyncTraceT[F] with Effect[TraceT[F, ?]] {

    override def runAsync[A](ta: TraceT[F, A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      F.runAsync(ta.trace(TC))(cb)

    override def toString: String = "Effect[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTAsyncInstance extends TraceTSyncInstance {

  implicit def asyncTraceTInstance[F[_]: Async]: Async[TraceT[F, ?]] = new AsyncTraceT[F]

  /** A `Async[TraceT[F, ?]]` typeclass instance given an instance of `Async[F]`. */
  protected class AsyncTraceT[F[_]](implicit F: Async[F]) extends SyncTraceT[F] with Async[TraceT[F, ?]] {
    override def async[A](cb: (Either[Throwable, A] => Unit) => Unit): TraceT[F, A] = TraceT.async(cb)
    override def asyncF[A](cb: (Either[Throwable, A] => Unit) => TraceT[F, Unit]): TraceT[F, A] = TraceT.asyncF(cb)

    override def liftIO[A](ioa: IO[A]): TraceT[F, A] =
      Async.liftIO(ioa)(this)

    override def toString: String = "Async[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTSyncInstance extends TraceTBracketInstance {

  implicit def syncTraceTInstance[F[_]: Sync]: Sync[TraceT[F, ?]] = new SyncTraceT[F]

  /** A `Sync[TraceT[F, ?]]` typeclass instance given an instance of `Sync[F]`. */
  protected class SyncTraceT[F[_]: Sync] extends BracketTraceT[F] with Sync[TraceT[F, ?]] {

    override def suspend[A](ta: => TraceT[F, A]): TraceT[F, A] = TraceT.suspend(ta)

    override def toString: String = "Sync[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTBracketInstance extends TraceTMonadErrorInstance {

  implicit def bracketTraceTInstance[F[_]](implicit F: Bracket[F, Throwable]): Bracket[TraceT[F, ?], Throwable] =
    new BracketTraceT[F]

  /** A `Bracket[TraceT[F, ?], Throwable]` typeclass instance given an instance of `Bracket[F, Throwable]`. */
  protected class BracketTraceT[F[_]](implicit F: Bracket[F, Throwable]) extends MonadErrorTraceT[F] with Bracket[TraceT[F, ?], Throwable] {

    override def bracketCase[A, B](acquire: TraceT[F, A])(use: A => TraceT[F, B])(release: (A, ExitCase[Throwable]) => TraceT[F, Unit]): TraceT[F, B] =
      acquire.bracketCase(use)(release)

    override def toString: String = "Bracket[TraceT[F, ?], Throwable]"
  }
}

private[dtrace] sealed trait TraceTMonadErrorInstance extends TraceTMonadInstance {

  implicit def monadErrorTraceTInstance[F[_]](implicit F: MonadError[F, Throwable]): MonadError[TraceT[F, ?], Throwable] =
    new MonadErrorTraceT[F]

  /** A `MonadError[TraceT[F, ?], Throwable]` typeclass instance given an instance of `MonadError[F, Throwable]`. */
  protected class MonadErrorTraceT[F[_]](implicit F: MonadError[F, Throwable]) extends MonadTraceT[F] with MonadError[TraceT[F, ?], Throwable] {
    override def attempt[A](ta: TraceT[F, A]): TraceT[F, Either[Throwable, A]] = ta.attempt

    override def raiseError[A](err: Throwable): TraceT[F, A] = TraceT.raiseError(err)

    override def handleErrorWith[A](ta: TraceT[F, A])(f: Throwable => TraceT[F, A]): TraceT[F, A] = ta.handleErrorWith(f)

    override def toString: String = "MonadError[TraceT[F, ?], Throwable]"
  }
}

private[dtrace] sealed trait TraceTMonadInstance {

  implicit def monadTraceTInstance[F[_]: Monad]: Monad[TraceT[F, ?]] = new MonadTraceT[F]

  /** A `Monad[TraceT[F, ?]]` typeclass instance given an instance of `Monad[F, Throwable]` */
  protected class MonadTraceT[F[_]](implicit F: Monad[F]) extends Monad[TraceT[F, ?]] {

    override def pure[A](a: A): TraceT[F, A] = TraceT.pure(a)

    override def map[A, B](ta: TraceT[F, A])(f: A => B): TraceT[F, B] = ta.map(f)

    override def flatMap[A, B](a: TraceT[F, A])(f: A => TraceT[F, B]): TraceT[F, B] = a flatMap f

    override def tailRecM[A, B](a: A)(f: A => TraceT[F, Either[A, B]]): TraceT[F, B] =
      TraceT.suspendEffect { tc => F.tailRecM(a)(f(_).toEffect(tc)) }

    override def toString: String = "Monad[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTTimerInstance {

  implicit def timerTraceTInstance[F[_]: Timer]: Timer[TraceT[F, ?]] = new TraceTTimer[F]

  /** A `Timer[TraceT[F, ?]]` typeclass instance given an instance of `Timer[F]. */
  protected class TraceTTimer[F[_]](implicit F: Timer[F]) extends Timer[TraceT[F, ?]] {

    override val clock: Clock[TraceT[F, ?]] = new Clock[TraceT[F, ?]] {
      def realTime(unit: TimeUnit): TraceT[F, Long] = TraceT.toTraceT(F.clock.realTime(unit))
      def monotonic(unit: TimeUnit): TraceT[F, Long] = TraceT.toTraceT(F.clock.monotonic(unit))
    }
    override def sleep(duration: FiniteDuration): TraceT[F, Unit] = TraceT.toTraceT(F.sleep(duration))

    override def toString: String = "Timer[TraceT[F, ?]]"
  }
}

private[dtrace] sealed trait TraceTContextShiftInstance {
  /**
   * Generates a `ContextShift[TraceT[F, ?]` in implicit scope, given a `Monad[F]` and `ContextShift[F]` in implicit scope.
   */
  implicit def contextShiftInstance[F[_]](implicit cs: ContextShift[F], F: Monad[F]): ContextShift[TraceT[F, ?]] = new ContextShift[TraceT[F, ?]] {
    def shift: TraceT[F, Unit] = TraceT.toTraceT(cs.shift)
    def evalOn[A](ec: ExecutionContext)(f: TraceT[F, A]): TraceT[F, A] =
      TraceT.suspendEffect { tc => cs.evalOn(ec)(f.toEffect(tc)) }
  }
}

private[dtrace] sealed trait TraceTParallelInstances extends TraceTParallelInstance {
  implicit def ioParApplicative(implicit cs: ContextShift[IO], p: Parallel[IO, IO.Par]): Applicative[TraceT[IO.Par, ?]] =
    implicitly[Parallel[TraceT[IO, ?], TraceT[IO.Par, ?]]].applicative
}

private[dtrace] sealed trait TraceTParallelInstance extends TraceTNonEmptyParallelInstance {

  implicit def parallelTraceTInstance[M[_], F[_]](implicit P: Parallel[M, F], M: Monad[M], F: Applicative[F]): Parallel[TraceT[M, ?], TraceT[F, ?]] =
    new ParallelTraceT[M, F]

  /**
   * A `Parallel[TraceT[M, ?], TraceT[F, ?]` typeclass instance given an instance of `Parallel[M, F]`.
   *
   * _Note that to summon an implicit instance of `Parallel[M, F]`, if one is not directly available,
   * you need (at least in the case where `M` == `cats.effect.IO`) an instance of `ContextShift[M]`
   * (*not* `ContextShift[TraceT[M, ?]]`) in implicit scope_.
   */
  protected class ParallelTraceT[M[_], F[_]](implicit P: Parallel[M, F], M: Monad[M], F: Applicative[F]) extends NonEmptyParallelTraceT[M, F] with Parallel[TraceT[M, ?], TraceT[F, ?]] {
    override def applicative: Applicative[TraceT[F, ?]] = new Applicative[TraceT[F, ?]] {
      override def map[A, B](ta: TraceT[F, A])(f: A => B): TraceT[F, B] = TraceT.suspendEffect { tc =>
        P.applicative.map(ta.toEffect(tc))(f)
      }
      override def pure[A](a: A): TraceT[F, A] = TraceT.toTraceT(P.applicative.pure(a))
      override def ap[A, B](tab: TraceT[F, A => B])(ta: TraceT[F, A]): TraceT[F, B] = TraceT.suspendEffect { tc =>
        P.applicative.ap(tab.toEffect(tc))(ta.toEffect(tc))
      }
      override def toString: String = "ParApplicative[TraceT[F, ?]]"
    }
    override def monad: Monad[TraceT[M, ?]] = new Monad[TraceT[M, ?]] {
      override def pure[A](a: A): TraceT[M, A] = TraceT.toTraceT(P.monad.pure(a))

      override def map[A, B](ta: TraceT[M, A])(f: A => B): TraceT[M, B] = TraceT.suspendEffect { tc =>
        P.monad.map(ta.toEffect(tc))(f)
      }

      override def flatMap[A, B](ta: TraceT[M, A])(f: A => TraceT[M, B]): TraceT[M, B] = TraceT.suspendEffect { tc =>
        P.monad.flatMap(ta.toEffect(tc))(f(_).toEffect(tc))
      }

      override def tailRecM[A, B](a: A)(f: A => TraceT[M, Either[A, B]]): TraceT[M, B] =
        TraceT.suspendEffect { tc => P.monad.tailRecM(a)(f(_).toEffect(tc)) }

      override def toString: String = "ParMonad[TraceT[M, ?]]"
    }
  }
}

private[dtrace] sealed trait TraceTNonEmptyParallelInstance {

  implicit def neParallelTraceTInstance[M[_], F[_]](implicit P: NonEmptyParallel[M, F], M: FlatMap[M], F: Apply[F]): NonEmptyParallel[TraceT[M, ?], TraceT[F, ?]] =
    new NonEmptyParallelTraceT[M, F]

  /**
   * A `NonEmptyParallel[TraceT[M, ?], TraceT[F, ?]` typeclass instance given an instance of `NonEmptyParallel[M, F]`.
   *
   * _Note that to summon an implicit instance of `NonEmptyParallel[M, F]`, if one is not directly available,
   * you need (at least in the case where `M` == `cats.effect.IO`) an instance of `ContextShift[M]`
   * (*not* `ContextShift[TraceT[M, ?]]`) in implicit scope_.
   */
  protected class NonEmptyParallelTraceT[M[_], F[_]](implicit P: NonEmptyParallel[M, F], M: FlatMap[M], F: Apply[F]) extends NonEmptyParallel[TraceT[M, ?], TraceT[F, ?]] {
    def apply: Apply[TraceT[F, ?]] = new Apply[TraceT[F, ?]] {
      override def ap[A, B](tab: TraceT[F, A => B])(ta: TraceT[F, A]): TraceT[F, B] = TraceT.suspendEffect { tc =>
        P.apply.ap(tab.toEffect(tc))(ta.toEffect(tc))
      }
      override def map[A, B](ta: TraceT[F, A])(f: A => B): TraceT[F, B] = TraceT.suspendEffect { tc =>
        P.apply.map(ta.toEffect(tc))(f)
      }
      override def toString: String = "ParApply[TraceT[F, ?]]"
    }
    def flatMap: FlatMap[TraceT[M, ?]] = new FlatMap[TraceT[M, ?]] {
      override def flatMap[A, B](ta: TraceT[M, A])(f: A => TraceT[M, B]): TraceT[M, B] = TraceT.suspendEffect { tc =>
        P.flatMap.flatMap(ta.toEffect(tc))(f(_).toEffect(tc))
      }
      override def ap[A, B](tab: TraceT[M, A => B])(ta: TraceT[M, A]): TraceT[M, B] = TraceT.suspendEffect { tc =>
        P.flatMap.ap(tab.toEffect(tc))(ta.toEffect(tc))
      }
      override def map[A, B](ta: TraceT[M, A])(f: A => B): TraceT[M, B] = TraceT.suspendEffect { tc =>
        P.flatMap.map(ta.toEffect(tc))(f)
      }
      override def tailRecM[A, B](a: A)(f: A => TraceT[M, Either[A, B]]): TraceT[M, B] =
        TraceT.suspendEffect { tc => P.flatMap.tailRecM(a)(f(_).toEffect(tc)) }

      override def toString: String = "ParFlatMap[TraceT[M, ?]]"
    }
    def sequential: TraceT[F, ?] ~> TraceT[M, ?] = new (TraceT[F, ?] ~> TraceT[M, ?]) {
      def apply[A](tfa: TraceT[F, A]): TraceT[M, A] = TraceT.suspendEffect[M, A] { tc =>
        P.sequential(tfa.toEffect(translate(tc, P.parallel)))
      }
    }
    def parallel: TraceT[M, ?] ~> TraceT[F, ?] = new (TraceT[M, ?] ~> TraceT[F, ?]) {
      def apply[A](tma: TraceT[M, A]): TraceT[F, A] = TraceT.suspendEffect[F, A] { tc =>
        P.parallel(tma.toEffect(translate(tc, P.sequential)))
      }
    }
  }
}
