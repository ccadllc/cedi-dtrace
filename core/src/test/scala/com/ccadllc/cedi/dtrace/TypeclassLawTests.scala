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

import cats._

import cats.effect._
import cats.effect.laws.discipline._
import cats.effect.laws.discipline.arbitrary._
import cats.effect.laws.util._

import cats.implicits._

import cats.kernel.Eq

import cats.laws.discipline.arbitrary._

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.charset.Charset

import org.scalacheck._
import org.scalatest.prop.Checkers
import org.scalatest.{ FunSuite, Matchers }

import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.Discipline

import scala.util.control.NonFatal

class TypeclassLawTests extends FunSuite with Matchers with Checkers with Discipline with TestInstances with TestData {

  private implicit def eqTraceIO[A](implicit A: Eq[A], testC: TestContext): Eq[TraceIO[A]] =
    new Eq[TraceIO[A]] {
      def eqv(x: TraceIO[A], y: TraceIO[A]): Boolean =
        eqIO[A].eqv(x.tie(tc), y.tie(tc))
    }

  private implicit def catsConcurrentEffectLawsArbitraryForTraceIO[A: Arbitrary: Cogen]: Arbitrary[TraceIO[A]] =
    Arbitrary(catsEffectLawsArbitraryForIO[A].arbitrary map TraceIO.toTraceIO)

  private implicit val tc: TraceContext[IO] = TraceContext(
    Span.root[IO](Span.Name("calculate-quarterly-sales")).unsafeRunSync,
    TraceSystem(testSystemMetadata, new TestEmitter[IO])
  )

  checkAllAsync("TraceIO", implicit testC => ConcurrentEffectTests[TraceIO].concurrentEffect[Int, Int, Int])

  private def checkAllAsync(name: String, f: TestContext => Laws#RuleSet): Unit = {
    val testC = TestContext()
    val ruleSet = f(testC)
    for ((id, prop) <- ruleSet.all.properties)
      test(s"$name.$id") { silenceSystemErr(check(prop)) }
  }

  private def silenceSystemErr[A](thunk: => A): A = synchronized {
    val oldErr = System.err
    val outStream = new ByteArrayOutputStream()
    val silentErr = new PrintStream(outStream)
    System.setErr(silentErr)
    try {
      val r = thunk
      System.setErr(oldErr)
      r
    } catch {
      case NonFatal(e) =>
        System.setErr(oldErr)
        val out = outStream.toString(Charset.defaultCharset.displayName)
        if (out.nonEmpty) oldErr.println(out)
        silentErr.close()
        throw e
    }
  }
}
