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

import cats.Applicative

import org.http4s.{ Header => H4sHeader, _ }

import scala.language.higherKinds

/**
 * This module provides interoperability with the [[https://github.com/http4s/http4s http4s]] library for
 * HTTP client functionality, generating HTTP distributed trace headers from the active `Span` (supporting
 * one or more configured trace protocols, such as the provided ones for [[ https://github.com/Comcast/money Comcast Money]]
 * and [[https://istio.io/docs/tasks/telemetry/distributed-tracing.html XB3/Zipkin]]).
 */
package object client {
  /**
   * This function will convert the passed in [[SpanId]] into one or more HTTP headers, given an instance of
   * [[HeaderCodec]] in implicit scope, and then add them to the passed-in `Request[F]`.
   * The [[HeaderCodec]] is often imported from either `com.ccadllc.cedi.dtrace.money._`, or
   * `com.ccadllc.cedi.dtrace.xb3._` for Money or X-B3 trace headers, respectively.
   * You can also provide your own [[HeaderCodec]] if, for instance, you wish to compose these into an aggregate
   * [[HeaderCodec]] or there are other types of trace headers you wish to generate.  For example, this will result
   * in both `Money` and `XB3` HTTP Headers being generated:
   *    ```implicit val myHeaderCodec: HeaderCodec = com.ccadllc.cedi.dtrace.interop.xb3.headerCodec.andThen(
   *      com.ccadllc.cedi.dtrace.interop.money.headerCodec
   *    )```
   * @param request a `Request[F]` representing the request that the client shall use to when interacting with a remote
   *   system.
   * @param spanId a [[SpanId]] containing the trace ID, span ID and parent span ID which will be used to generate one
   *   or more HTTP headers.
   * @param codec a [[HeaderCodec]] whose implementation provides encoding and decoding [[SpanId]] elements into one
   *   or more protocol-specific HTTP headers.  Provided in implicit scope.
   * @tparam F - a type constructor representing an effect by which the request is created.
   * @return outputRequest the input `Request[F]` enhanced with one or more tracing releated HTTP headers.
   */
  def withTracedRequest[F[_]](request: Request[F], spanId: SpanId)(implicit codec: HeaderCodec): Request[F] =
    request.withHeaders(request.headers ++ toH4s(codec.encode(spanId)))

  /**
   * This function will convert the [[Span]] in the current [[TraceContext]] of effect `F` into one or more HTTP headers,
   * given an instance of [[HeaderCodec]] and a 'cats.effect.Applicative[F]` in implicit scope, and then add the headers
   * to the passed-in `Request[TraceT[F, ?]]`.
   * The [[HeaderCodec]] is often imported from either `com.ccadllc.cedi.dtrace.money._`, or
   * `com.ccadllc.cedi.dtrace.xb3._` for `Money` or `X-B3` trace headers, respectively.
   * You can also provide your own [[HeaderCodec]] if, for instance, you wish to compose these into an aggregate
   * [[HeaderCodec]] or there are other types of trace headers you wish to generate. For example, this will result
   * in both `Money` and `XB3` HTTP Headers being generated:
   *    ```implicit val myHeaderCodec: HeaderCodec = com.ccadllc.cedi.dtrace.interop.xb3.headerCodec.andThen(
   *      com.ccadllc.cedi.dtrace.interop.money.headerCodec
   *    )```
   * @param request a `Request[TraceT[F, ?]]` representing the request that the client shall use to when interacting with a remote
   *   system, wrapped in a traced effect.
   * @param codec a [[HeaderCodec]] whose implementation provides encoding and decoding [[SpanId]] elements into one
   *   or more protocol-specific HTTP headers.  Provided in implicit scope.
   * @param F a `cats.effect.Applicative[F]` used by `TraceT.ask[F]` to get the current `TraceContext[F]`.  Provided in implicit scope.
   * @return outputRequest the input `Request[TraceT[F, ?]]` enhanced with one or more tracing releated HTTP headers and itself wrapped
   *   in a `TraceT[F, ?]` context.
   */
  def traceRequest[F[_]](request: Request[TraceT[F, ?]])(implicit codec: HeaderCodec, F: Applicative[F]): TraceT[F, Request[TraceT[F, ?]]] =
    TraceT.ask[F].map { tc => withTracedRequest(request, tc.currentSpan.spanId) }

  private def toH4s(headers: List[Header]): List[H4sHeader] = headers.map { h => H4sHeader(h.name.value, h.value.value) }
}
