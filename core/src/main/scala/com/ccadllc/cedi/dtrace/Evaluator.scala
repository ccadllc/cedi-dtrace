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

/**
 * Provides the hooks by which the result of a effectful program can be converted into a `FailureDetail` for use in
 * determining whether the program has failed for the purposes of the trace and how to render that failure.
 * If the traced program fails, the `exceptionToFailure` function is used to determine if this failure should be
 * recorded as such for the span and if the program itself is successful, the `resultToFailure` conversion is used to
 * determine if there is an application level failure embedded in the result and if so, how it should be rendered.
 *
 * The default evaluator for a span, if a custom one is not specified, is to treat a failures and successes at
 * the effectful program-level as such for the span, converting the `Throwable` to a string with the stacktrace.
 */
class Evaluator[A](
  val exceptionToFailure: Throwable => Option[FailureDetail],
  val resultToFailure: A => Option[FailureDetail]
)

/**
 * This companion for `Evaluator` instances provides smart constructors and a `default` value should no
 * custom `Evaluator` be used when recording a span.
 */
object Evaluator {
  /**
   * The default `Evaluator` instance, used if no custom `Evaluator` is passed to the `TraceT.newSpan` or `TraceT.newAnnotatedSpan`
   * functions. All program exceptions are converted to [[FailureDetail]] instances but otherwise no [[FailureDetail]]s are generated.
   */
  def default[A]: Evaluator[A] = new Evaluator[A](e => Some(FailureDetail(e)), _ => None)

  /**
   * Constructs an `Evaluator` instance with the two passed-in functions.
   * @param exceptionToFailure - a function which takes a `Throwable` returned by the program whose execution is being traced by the
   *   span using this 'Evaluator' and possibly converts it to a [[FailureDetail]] which is used to determine that the span execution has
   *   succeeded or failed and how to render it.  Generally, if the program itself has failed with a `Throwable`, you'll
   *   want to return a [[FailureDetail]] with a human-readable rendering of that error but there may be corner cases where the span itself
   *   should still be reported as having succeeded (if, for instance, a `fs2.Task` is failed with an exception meant to trigger some upstream
   *   program logic rather than indicate an error - not recommended, of course).
   * @param resultToFailure - a function which takes the result of the program `F[A]` whose execution is being traced by the span using this
   *   `Evaluator` and possibly uses it to create a [[FailureDetail]].  Usually, if a program succeeds with a result, you won't want to
   *   generate a [[FailureDetail]] indicating that the span has failed; however, the program may be returning an application-specific data
   *   type which could indicate an error (such as an `Either[Error, Result]` or an enumerated ADT with values indicating failure).  In those
   *   cases, this function can be used to translate the application-specific error to a [[FailureDetail]].
   * @return evaluator - a new instance of the `Evaluator`.
   */
  def apply[A](exceptionToFailure: Throwable => Option[FailureDetail], resultToFailure: A => Option[FailureDetail]): Evaluator[A] =
    new Evaluator[A](exceptionToFailure, resultToFailure)

  /**
   * Constructs an `Evaluator` instance with the passed-in function used to convert a program failure `Throwable` to a possible [[FailureDetail]].  The
   * default value is used for the function which converts a successful program result to a possible [[FailureDetail]] (the default
   * function in this case always returns no [[FailureDetail]] for a successful program result).
   * @param exceptionToFailure - a function which takes a `Throwable` returned by the program whose execution is being traced by the
   *   span using this 'Evaluator' and possibly converts it to a [[FailureDetail]] which is used to determine that the span execution has
   *   succeeded or failed and how to render it.  Generally, if the program itself has failed with a `Throwable`, you'll
   *   want to return a [[FailureDetail]] with a human-readable rendering of that error but there may be corner cases where the span itself
   *   should still be reported as having succeeded (if, for instance, a `fs2.Task` is failed with an exception meant to trigger some upstream
   *   program logic rather than indicate an error - not recommended, of course).
   * @return evaluator - a new instance of the `Evaluator`.
   */
  def exceptionToFailure[A](e2f: Throwable => Option[FailureDetail]): Evaluator[A] =
    apply(e2f, default[A].resultToFailure)

  /**
   * Constructs an `Evaluator` instance with the passed-in function used to convert a program `F[A]`'s result `A` to a possible [[FailureDetail]].  The
   * default value is used for the function which converts a program failure `Throwable` to a possible [[FailureDetail]] (the default
   * function in this case always returns a [[FailureDetail]] using the `Throwable` to construct it.
   * @param resultToFailure - a function which takes the result of the program `F[A]` whose execution is being traced by the span using this
   *   `Evaluator` and possibly uses it to create a [[FailureDetail]].  Usually, if a program succeeds with a result, you won't want to
   *   generate a [[FailureDetail]] indicating that the span has failed; however, the program may be returning an application-specific data
   *   type which could indicate an error (such as an `Either[Error, Result]` or an enumerated ADT with values indicating failure).  In those
   *   cases, this function can be used to translate the application-specific error to a [[FailureDetail]].
   * @return evaluator - a new instance of the `Evaluator`.
   */
  def resultToFailure[A](r2f: A => Option[FailureDetail]): Evaluator[A] =
    apply(default[A].exceptionToFailure, r2f)
}
