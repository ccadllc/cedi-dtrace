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
package logging

import cats.effect.{ IO, Sync }

import io.circe._
import io.circe.syntax._

import org.scalacheck.Arbitrary

import org.scalatest.Suite
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import shapeless.Lazy

trait TestSupport extends AnyWordSpecLike with Matchers with ScalaCheckPropertyChecks with TraceGenerators with TestData {
  self: Suite =>

  override def testEmitter[F[_]: Sync]: F[TraceSystem.Emitter[F]] = Sync[F].pure(LogEmitter.apply)

  val salesManagementSystem = TraceSystem(testSystemData, testEmitter[IO].unsafeRunSync, TraceSystem.realTimeTimer[IO])
  val calculateQuarterlySalesTraceContext = TraceContext(quarterlySalesCalculationSpan, true, salesManagementSystem)

  def encodeGeneratedJson[A: Arbitrary](implicit encoder: Lazy[Encoder[A]]): Unit = {
    implicit val e = encoder.value
    "encode arbitrary instances to JSON" in {
      forAll { (msg: A) => msg.asJson.noSpaces should not be (None) }
    }
  }
  def encodeSpecificJson[A](a: A, json: Json)(implicit encoder: Lazy[Encoder[A]]): Unit = {
    implicit val e = encoder.value
    "encode specific instance to JSON and ensure it matches expected" in { a.asJson shouldBe json }
  }
}
