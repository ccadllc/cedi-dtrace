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

import cats.effect._

import org.http4s._
import org.http4s.dsl.io._

import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.EitherValues._
import org.scalatest.OptionValues._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scodec.bits.ByteVector

import interop.http4s.client._
import interop.http4s.server._

class DTraceHttp4sTest extends WordSpec with Matchers with GeneratorDrivenPropertyChecks with TraceGenerators with TestData {

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful = 100)

  "the dtrace http4s client-side trace header generation and server-side trace header extraction functions" should {
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using X-B3 header codec" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.xb3._
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using Money header codec" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.money._
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request with Money headers" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.money._
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request with X-B3 headers" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.xb3._
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using composite X-B3/Money header codec (preferring X-B3 when parsing on server-side and encoding both header types on client-side requests)" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        implicit val headerCodec: HeaderCodec = interop.xb3.headerCodec.andThen(interop.money.headerCodec)
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request using composite X-B3/Money header codec (preferring X-B3 when parsing on server-side and encoding both header types on client-side requests)" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        implicit val headerCodec: HeaderCodec = interop.xb3.headerCodec.andThen(interop.money.headerCodec)
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpRoutes.of[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.map { traceId => Response[IO](status = Status.Ok).withEntity(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
  }
}
