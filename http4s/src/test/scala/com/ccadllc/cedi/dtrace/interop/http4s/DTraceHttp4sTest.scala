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

import org.http4s.{ Header => H4sHeader, _ }
import org.http4s.parser.HttpHeaderParser
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

  "the dtrace http4s convenience function to drop the built-in X-B3 Trace ID HTTP header parser, since it doesn't currently support 128 bit Trace IDs" should {
    "drop the parser so that it no longer shows up in the list of registered parsers" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.xb3._
        import XB3HeaderCodec._
        val traceIdHeader = headerCodec.encode(requestTc.currentSpan.spanId).collectFirst {
          case Header(TraceIdHeaderName, value) => H4sHeader(TraceIdHeaderName.value, value.value)
        }.value
        if (!DTraceHttp4sTest.traceIdBuiltInHeaderDropped) {
          HttpHeaderParser.parseHeader(traceIdHeader) shouldBe 'left
          DTraceHttp4sTest.traceIdBuiltInHeaderDropped = true
          dropBuiltInXb3Headers[IO].unsafeRunSync
        }
        HttpHeaderParser.parseHeader(traceIdHeader) shouldBe 'right
      }
    }
  }
  "the dtrace http4s client-side trace header generation and server-side trace header extraction functions" should {
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using X-B3 header codec" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.xb3._
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using Money header codec" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.money._
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request with Money headers" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.money._
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request with X-B3 headers" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        import interop.xb3._
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
    "extract the expected trace ID from the HTTP headers passed in the client request and propagate it to the server-side action using composite X-B3/Money header codec (preferring X-B3 when parsing on server-side and encoding both header types on client-side requests)" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        implicit val headerCodec: HeaderCodec = interop.xb3.headerCodec.andThen(interop.money.headerCodec)
        val clientRequest = withTracedRequest(Request[IO](uri = uri("/myUser")), requestTc.currentSpan.spanId)
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr shouldBe requestTc.currentSpan.spanId.traceId.toString
      }
    }
    "generate a new root span for the server-side action when no trace headers are present in the client request using composite X-B3/Money header codec (preferring X-B3 when parsing on server-side and encoding both header types on client-side requests)" in {
      forAll(genTraceContext[IO]) { (requestTc: TraceContext[IO]) =>
        implicit val headerCodec: HeaderCodec = interop.xb3.headerCodec.andThen(interop.money.headerCodec)
        val clientRequest = Request[IO](uri = uri("/myUser"))
        val serverAction = HttpService[IO] {
          case request @ GET -> Root / user =>
            implicit val ts = requestTc.system
            tracedAction(request, Span.Name("user-action"), Note.string("user", user)) {
              TraceIO.ask.map { _.currentSpan.spanId.traceId }
            }.flatMap { traceId => Response[IO](status = Status.Ok).withBody(traceId.toString) }
        }
        val traceIdStr = ByteVector(serverAction.run(clientRequest).value.unsafeRunSync.value.body.compile.toVector.unsafeRunSync).decodeUtf8.right.value
        traceIdStr should not be (requestTc.currentSpan.spanId.traceId.toString)
      }
    }
  }
}

object DTraceHttp4sTest {
  /*
   * Unfortunately, the current http4s registry is global, the X-B3 Built-In Header Parsers are private and are added in the HttpHeaderParser object
   * constructor so once we drop the built-in parser, we can't re-add it without restarting the JVM.  But we can test it at least once!
   * Ross Baker has promised this implementation will be changed.  But also that X-B3 will be updated to support 128 bit Trace IDs so we don't
   * have to provide this drop hack in the first place.
   * Once [[https://github.com/http4s/http4s/issues/1859 this issue]] is resolved, this, and the associated test, can be removed.
   */
  private var traceIdBuiltInHeaderDropped: Boolean = false
}
