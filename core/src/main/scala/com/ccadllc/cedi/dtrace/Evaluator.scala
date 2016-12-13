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

object Evaluator {

  def default[A]: Evaluator[A] = new Evaluator[A](e => Some(FailureDetail(e)), _ => None)

  def apply[A](exceptionToFailure: Throwable => Option[FailureDetail], resultToFailure: A => Option[FailureDetail]): Evaluator[A] =
    new Evaluator[A](exceptionToFailure, resultToFailure)

  def exceptionToFailure[A](e2f: Throwable => Option[FailureDetail]): Evaluator[A] =
    apply(e2f, default[A].resultToFailure)

  def resultToFailure[A](r2f: A => Option[FailureDetail]): Evaluator[A] =
    apply(default[A].exceptionToFailure, r2f)
}
