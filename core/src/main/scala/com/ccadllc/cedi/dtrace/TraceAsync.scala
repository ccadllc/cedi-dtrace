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

import fs2.{ Strategy, Task }
import fs2.util.{ ~>, Async }
import fs2.util.syntax._

import scala.language.higherKinds

/**
 * Represents a function `TraceContext => F[A]` when there is an instance of `fs2.util.Async[F]` in implicit scope.
 */
final class TraceAsync[F[_], A](private[dtrace] val tie: TraceContext => F[A])(implicit F: Async[F]) { self =>
  import syntax._

  def flatMap[B](f: A => TraceAsync[F, B]): TraceAsync[F, B] =
    TraceAsync { tc => F.suspend { tie(tc).flatMap { f andThen { _.tie(tc) } } } }

  def map[B](f: A => B): TraceAsync[F, B] = flatMap(f andThen TraceAsync.now[F, B])

  def newSpan(spanName: Span.Name, notes: Note*): TraceAsync[F, A] =
    newAnnotatedSpan(spanName, notes: _*)(PartialFunction.empty)

  def newAnnotatedSpan(spanName: Span.Name, notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]]): TraceAsync[F, A] =
    newAnnotatedSpan(spanName, Evaluator.default[A], notes: _*)(resultAnnotator)

  def newSpan(spanName: Span.Name, evaluator: Evaluator[A], notes: Note*): TraceAsync[F, A] =
    newAnnotatedSpan(spanName, evaluator, notes: _*)(PartialFunction.empty)

  def newAnnotatedSpan(
    spanName: Span.Name,
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]]): TraceAsync[F, A] = TraceAsync { tc =>
    for {
      t <- tc.childSpan[F](spanName)
      r <- annotatedTrace(t, evaluator, notes: _*)(resultAnnotator)
    } yield r
  }

  def trace(tc: TraceContext, notes: Note*): F[A] =
    annotatedTrace(tc, notes: _*)(PartialFunction.empty)

  def annotatedTrace(tc: TraceContext, notes: Note*)(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]]): F[A] =
    annotatedTrace(tc, Evaluator.default[A], notes: _*)(resultAnnotator)

  def trace(tc: TraceContext, evaluator: Evaluator[A], notes: Note*): F[A] =
    annotatedTrace(tc, evaluator, notes: _*)(PartialFunction.empty)

  def annotatedTrace(
    tc: TraceContext,
    evaluator: Evaluator[A],
    notes: Note*
  )(resultAnnotator: PartialFunction[Either[Throwable, A], Vector[Note]]): F[A] =
    tc.updateStartTime flatMap { updated =>
      tie(updated).attempt.flatMap { eOrR =>
        val annotatedTc = updated.setNotes(notes.toVector ++ resultAnnotator.applyOrElse(eOrR, (_: Either[Throwable, A]) => Vector.empty))
        eOrR match {
          case Right(r) => evaluator.resultToFailure(r).fold(annotatedTc.emitSuccess[F])(updated.emitFailure[F]) map { _ => r }
          case Left(e) => evaluator.exceptionToFailure(e).fold(annotatedTc.emitSuccess[F])(annotatedTc.emitFailure[F]) flatMap { _ => F.fail(e) }
        }
      }
    }

  def attempt: TraceAsync[F, Either[Throwable, A]] = TraceAsync { tie(_).attempt }

  def bestEffortOnFinish(f: Option[Throwable] => TraceAsync[F, Unit]): TraceAsync[F, A] = TraceAsync { tc =>
    tie(tc).bestEffortOnFinish(f(_).tie(tc))
  }

  def handle[B >: A](f: PartialFunction[Throwable, B]): TraceAsync[F, B] = handleWith(f andThen TraceAsync.now[F, B])

  def handleWith[B >: A](f: PartialFunction[Throwable, TraceAsync[F, B]]): TraceAsync[F, B] = attempt flatMap {
    case Right(r) => TraceAsync.now[F, B](r)
    case Left(err) => if (f.isDefinedAt(err)) f(err) else TraceAsync.fail[F, B](err)
  }

  def handleAllWith[B >: A](f: Throwable => TraceAsync[F, B]): TraceAsync[F, B] = attempt.flatMap(_.fold(f, TraceAsync.now[F, B]))

  def toAsync(tc: TraceContext): F[A] = tie(tc)
}

object TraceAsync extends TraceAsyncPolyFuncs with TraceAsyncAsyncInstance {
  private[dtrace] def apply[F[_]: Async, A](tie: TraceContext => F[A]): TraceAsync[F, A] = new TraceAsync(tie)
  def ask[F[_]](implicit F: Async[F]): TraceAsync[F, TraceContext] = apply { F.pure }
  def now[F[_], A](a: A)(implicit F: Async[F]): TraceAsync[F, A] = toTraceAsync(F.pure(a))
  def delay[F[_], A](a: => A)(implicit F: Async[F]): TraceAsync[F, A] = toTraceAsync(F.delay(a))
  def fail[F[_], A](t: Throwable)(implicit F: Async[F]): TraceAsync[F, A] = toTraceAsync(F.fail(t): F[A])
  def toTraceAsync[F[_]: Async, A](async: F[A]): TraceAsync[F, A] = TraceAsync { _ => async }
}

private[dtrace] sealed trait TraceAsyncPolyFuncs {
  implicit def task2TraceTask(implicit S: Strategy) = new (Task ~> TraceTask) { def apply[A](task: Task[A]): TraceTask[A] = TraceAsync.toTraceAsync(task) }
  implicit object traceTssk2TraceTask extends (TraceTask ~> TraceTask) { def apply[A](traceTask: TraceTask[A]): TraceTask[A] = traceTask }
  implicit def traceAsyncF2TraceAsyncF[F[_]](implicit F: Async[F]) = {
    type TraceAsyncF[A] = TraceAsync[F, A]
    new (TraceAsyncF ~> TraceAsyncF) { def apply[A](traceAsyncF: TraceAsyncF[A]): TraceAsyncF[A] = traceAsyncF }
  }
}

private[dtrace] sealed trait TraceAsyncAsyncInstance {
  /** instance for Monadic `Async` TraceAsync`. */
  implicit def asyncTraceAsyncInstance[F[_]](implicit AF: Async[F]): Async[({ type l[a] = TraceAsync[F, a] })#l] = new Async[({ type l[a] = TraceAsync[F, a] })#l] {
    type TraceAsyncF[A] = TraceAsync[F, A]
    import syntax._
    def ref[A]: TraceAsync[F, Async.Ref[TraceAsyncF, A]] = TraceAsync { _ => Async.ref[F, A](AF) map { new TraceAsyncRef(_, this) } }

    def pure[A](a: A): TraceAsyncF[A] = TraceAsync.now[F, A](a)

    def flatMap[A, B](ta: TraceAsyncF[A])(f: A => TraceAsyncF[B]): TraceAsyncF[B] = ta.flatMap(f)

    override def delay[A](a: => A): TraceAsyncF[A] = TraceAsync.delay[F, A](a)

    def suspend[A](ta: => TraceAsyncF[A]) = TraceAsync { ta.tie }

    def fail[A](err: Throwable): TraceAsyncF[A] = TraceAsync.fail[F, A](err)

    def attempt[A](ta: TraceAsyncF[A]): TraceAsyncF[Either[Throwable, A]] = ta.attempt

    def edit[A, B](ta: TraceAsyncF[A])(f: F[A] => F[B]): TraceAsyncF[B] = TraceAsync { tc => f(ta.tie(tc)) }

    def unsafeRunAsync[A](ta: TraceAsyncF[A])(cb: Either[Throwable, A] => Unit): Unit = ta.tie(TraceContext.empty).unsafeRunAsync(cb)

    override def toString = "Async[TraceAsyncF]"

    class TraceAsyncRef[A](ref: Async.Ref[F, A], protected val F: Async[TraceAsyncF]) extends Async.Ref[TraceAsyncF, A] {
      def access: TraceAsyncF[(A, Either[Throwable, A] => TraceAsyncF[Boolean])] = TraceAsync { _ =>
        ref.access map { case (a, e2r) => (a, e2r andThen { _.toTraceAsync }) }
      }
      def set(a: TraceAsyncF[A]): TraceAsyncF[Unit] = edit(a) { t => ref.set(t) }

      override def get: TraceAsyncF[A] = TraceAsync { _ => ref.get }

      def cancellableGet: TraceAsyncF[(TraceAsyncF[A], TraceAsyncF[Unit])] = TraceAsync { _ =>
        ref.cancellableGet map { case (tta, ttu) => (tta.toTraceAsync, ttu.toTraceAsync) }
      }
    }
  }
}
