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
import cats.laws.discipline._

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.charset.Charset

import org.scalactic.source
import org.scalacheck._
import org.scalatest.OptionValues._
import org.scalatest.TryValues._
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.Checkers

import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.FunSuiteDiscipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

class TypeclassLawTests extends AnyFunSuite with Matchers with Checkers with FunSuiteDiscipline with TestInstances with TestData {

  private implicit def eqTraceIO[A](implicit A: Eq[A], testC: TestContext): Eq[TraceIO[A]] =
    new Eq[TraceIO[A]] {
      def eqv(x: TraceIO[A], y: TraceIO[A]): Boolean =
        eqIO[A].eqv(x.toEffect(tc), y.toEffect(tc))
    }

  private implicit def eqTraceIOPar[A](implicit A: Eq[A], testC: TestContext): Eq[TraceIO.Par[A]] = {
    implicit val cs = IO.contextShift(ExecutionContext.global)
    new Eq[TraceIO.Par[A]] {
      def eqv(x: TraceIO.Par[A], y: TraceIO.Par[A]): Boolean =
        eqTraceIO[A].eqv(TraceIO.Par.unwrap(x), TraceIO.Par.unwrap(y))
    }
  }

  private implicit def catsConcurrentEffectLawsArbitraryForTraceIO[A: Arbitrary: Cogen]: Arbitrary[TraceIO[A]] =
    Arbitrary(
      catsEffectLawsArbitraryForIO[A].arbitrary.map(
        _.newSpan(quarterlyPhillySalesCalculateSpanName, quarterlyPhillySpecificSpanNotes: _*)))

  private implicit def catsEffectLawsArbitraryForTraceIOParallel[A: Arbitrary: Cogen]: Arbitrary[TraceIO.Par[A]] =
    Arbitrary(catsEffectLawsArbitraryForIOParallel[A].arbitrary map TraceIO.Par.apply)

  private def tc: TraceContext[IO] = {
    val timer = TraceSystem.realTimeTimer[IO]
    TraceContext(
      Span.root(timer, quarterlySalesCalculateSpanName, quarterlySalesCalculationSpanNotes: _*).unsafeRunSync,
      true,
      TraceSystem(testSystemData, new TestEmitter[IO], timer))
  }

  checkAllAsync("TraceIO", implicit testC => {
    implicit val csIo = testC.contextShift[IO]
    implicit val tcInstance = tc
    ConcurrentEffectTests[TraceIO].concurrentEffect[Int, Int, Int]
  })

  checkAllAsync("TraceIO.Par", implicit testC => {
    implicit val cs = testC.contextShift[IO]
    ApplicativeTests[TraceIO.Par].applicative[Int, Int, Int]
  })

  checkAllAsync("TraceIO.parallel", implicit testC => {
    implicit val cs = testC.contextShift[IO]
    val module = ParallelTests[TraceIO]
    module.parallel[Int, Int]
  })

  checkAllAsync("TraceIO.nonEmptyParallel", implicit testC => {
    implicit val cs = testC.contextShift[IO]
    val module = NonEmptyParallelTests[TraceIO]
    module.nonEmptyParallel[Int, Int]
  })

  testAsync("TraceIO.Par's applicative instance is different") { implicit testC =>
    implicit val cs = testC.contextShift[IO]
    implicitly[Applicative[TraceIO]] shouldNot be(implicitly[Applicative[TraceIO.Par]])
    ()
  }

  test("Timer[TraceIO].clock.realTime") {
    implicit val ec = ExecutionContext.global
    implicit val iot = IO.timer(ec)
    val time = System.currentTimeMillis
    val tio = implicitly[Timer[TraceIO]].clock.realTime(MILLISECONDS)
    for (t2 <- tio.trace(tc).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[TraceIO].clock.monotonic") {
    implicit val ec = ExecutionContext.global
    implicit val iot = IO.timer(ec)
    val time = System.nanoTime
    val tio = implicitly[Timer[TraceIO]].clock.monotonic(NANOSECONDS)

    for (t2 <- tio.trace(tc).unsafeToFuture()) yield {
      time should be > 0L
      time should be <= t2
    }
  }

  test("Timer[TraceIO].sleep(10.ms)") {
    implicit val ec = ExecutionContext.global
    implicit val iot = IO.timer(ec)
    val t = implicitly[Timer[TraceIO]]
    val tio = for {
      start <- t.clock.monotonic(MILLISECONDS)
      _ <- t.sleep(10.millis)
      end <- t.clock.monotonic(MILLISECONDS)
    } yield {
      end - start
    }

    for (r <- tio.trace(tc).unsafeToFuture()) yield {
      r should be > 0L
    }
  }

  test("Timer[TraceIO].sleep(negative)") {
    implicit val ec = ExecutionContext.global
    implicit val iot = IO.timer(ec)
    val tio = implicitly[Timer[TraceIO]].sleep(-10.seconds).map(_ => 10)

    for (r <- tio.trace(tc).unsafeToFuture()) yield {
      r shouldBe 10L
    }
  }

  testAsync("ContextShift[TraceIO].shift") { testC =>
    implicit val cs = TraceIO.contextShift(testC)
    val f = cs.shift.trace(tc).unsafeToFuture()
    f.value shouldBe None
    testC.tick()
    f.value shouldBe Some(Success(()))
    ()
  }

  testAsync("ContextShift[TraceIO].evalOn") { testC =>
    implicit val cs = TraceIO.contextShift(testC)
    val testC2 = TestContext()
    val f = cs.evalOn(testC2)(TraceIO(1)).trace(tc).unsafeToFuture()
    f.value shouldBe None
    testC.tick()
    f.value shouldBe None
    testC2.tick()
    f.value shouldBe None
    testC.tick()
    f.value shouldBe Some(Success(1))
    ()
  }

  testAsync("parMap2 should be stack safe") { testC =>
    implicit val cs = testC.contextShift[IO]
    val count = 500000
    val tasks = (0 until count).map(_ => TraceIO(1))
    val sum = tasks.foldLeft(TraceIO(0))((acc, t) => (acc, t).parMapN(_ + _))
    val f = sum.trace(tc).unsafeToFuture()
    testC.tick()
    f.value shouldBe Some(Success(count))
    ()
  }

  testAsync("parTraverse should be stack safe") { testC =>
    implicit val cs = testC.contextShift[IO]
    val count = 500000
    val numbers = (0 until count).toVector
    val f = numbers.parTraverse(i => TraceIO.pure(i + 1)).trace(tc).unsafeToFuture()
    testC.tick()
    f.value.value.success.value.sum shouldBe numbers.map(_ + 1).sum
    ()
  }

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
  private def testAsync[A](name: String, tags: Tag*)(f: TestContext => Unit)(implicit pos: source.Position): Unit = {
    test(name, tags: _*)(silenceSystemErr(f(TestContext())))(pos)
  }
}
