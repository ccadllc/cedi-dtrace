/*
 * Copyright 2018 Combined Conditional Access Development, LLC.
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
package interop
package http4s

import cats.effect.Sync
import cats.implicits._

import org.http4s.{ Header => H4sHeader, _ }

import scala.language.higherKinds

package object server {
  /**
   * This function can be used to execute a traced action within an `HttpService[F]` with
   * the [[TraceContext]] of `F` possibly derived from HTTP headers, given instances of `cats.effect.Sync[F]`, [[HeaderCodec]],
   * and [[TraceSystem]] of `F` in implicit scope. The [[HeaderCodec]] is usually imported from either
   * `com.ccadllc.cedi.dtrace.money._`, or `com.ccadllc.cedi.dtrace.xb3._` for Money or X-B3 trace headers, respectively.
   * You can also provide your own [[HeaderCodec]] if, for instance, you wish to compose these into an aggregate
   * or there are other types of trace headers you wish to extract.  This function also requires that you
   * provide the initial `Span.Name` along with an `Evaluator[A]` to determine if an application-specific result
   * should be considered a span failure - use [[tracedAction]] if you have no need to pass in an `Evaluator` - along with
   * any [[Note]]s you wish to include in the spans.
   * {{{
   *  import com.ccadllc.cedi.dtrace._
   *  import com.ccadllc.cedi.dtrace.http4s._
   *  import com.ccadllc.cedi.dtrace.xb3._
   *  ...
   *  type Json = ???
   *  def myTracedAction: TraceIO[Json] = ???
   *  ...
   *  val serverAction = HttpService[IO] {
   *    case request @ GET -> Root / "mypersonaljson" / user =>
   *      tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
   *        myTracedAction
   *      }.flatMap { resultJson => Response[IO](status = Status.Ok).withBody(resultJson) }
   *  }
   * }}}
   *
   * @param req the `Request[F]` representing the HTTP request.  Used to examine the headers (and potentially other parts of the
   *   request) in order to derive a trace [[Span]], if present (a new root [[Span]] will be created if not).
   * @param spanName the name of the [[Span]] which will be either the child of any [[Span]] found within the `Request[F]` or the
   *   root if no existing [[Span]] was found.
   *   request) in order to derive a trace [[Span]], if present (a new root [[Span]] will be created if not).
   * @param evaluator - the `Evaluator[A]`, if needed, for determining if an application specific result should be considered an
   *   error.
   * @param notes any additional metadata to include with the [[Span]].
   * @tparam F - a type constructor representing the effect in which to execute the traced action.
   * @tparam A - the result of the traced action to be executed.
   * @return the traced action, unwrapped to its underlying effect, the tracing having been applied (once the effect is run).
   */
  def tracedEvaluatedAction[F[_], A](
    req: Request[F],
    spanName: Span.Name,
    evaluator: Evaluator[A],
    notes: Note*)(action: TraceT[F, A])(implicit codec: HeaderCodec, F: Sync[F], ts: TraceSystem[F]): F[A] = codec.decode(fromHttp4s(req.headers.toList)) match {
    case Right(spanIdMaybe) =>
      spanIdMaybe.fold(Span.root[F](ts.timer, spanName, notes: _*)) { Span.newChild[F](ts.timer, _, spanName, notes: _*) }.flatMap { span =>
        action.trace(TraceContext(span, ts), evaluator, notes: _*)
      }
    case Left(errDetail) => F.raiseError[A](errDetail)
  }

  /*
   * This function can be used to execute a traced action within an `HttpService[F]` with
   * the [[TraceContext]] of `F` possibly derived from HTTP headers, given instances of `cats.effect.Sync[F]`, [[HeaderCodec]],
   * and [[TraceSystem]] of `F` in implicit scope.  See [[tracedEvaluatedAction]] for more details.
   *
   * @param req the `Request[F]` representing the HTTP request.  Used to examine the headers (and potentially other parts of the
   *   request) in order to derive a trace [[Span]], if present (a new root [[Span]] will be created if not).
   * @param spanName the name of the [[Span]] which will be either the child of any [[Span]] found within the `Request[F]` or the
   *   root if no existing [[Span]] was found.
   *   request) in order to derive a trace [[Span]], if present (a new root [[Span]] will be created if not).
   * @param notes any additional metadata to include with the [[Span]].
   * @tparam F - a type constructor representing the effect in which to execute the traced action.
   * @tparam A - the result of the traced action to be executed.
   * @return the traced action, unwrapped to its underlying effect, the tracing having been applied (once the effect is run).
   */
  def tracedAction[F[_], A](
    req: Request[F],
    spanName: Span.Name,
    notes: Note*)(action: TraceT[F, A])(implicit codec: HeaderCodec, F: Sync[F], ts: TraceSystem[F]): F[A] =
    tracedEvaluatedAction(req, spanName, Evaluator.default[A], notes: _*)(action)

  private def fromHttp4s(h4sHeaders: List[H4sHeader]): List[Header] =
    h4sHeaders map { h => Header(Header.CaseInsensitiveName(h.name.value), Header.Value(h.value)) }
}
