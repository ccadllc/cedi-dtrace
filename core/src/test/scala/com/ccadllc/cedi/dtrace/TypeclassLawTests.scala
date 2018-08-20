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

import cats.laws.discipline._
import cats.laws.discipline.arbitrary._

import java.io.{ ByteArrayOutputStream, PrintStream }
import java.nio.charset.Charset

import org.scalactic.source
import org.scalacheck._
import org.scalatest.prop.Checkers
import org.scalatest.{ FunSuite, Matchers, Tag }

import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NonFatal

class TypeclassLawTests extends FunSuite with Matchers with Checkers with Discipline with TestInstances with TestData {

  private implicit def eqTraceIO[A](implicit A: Eq[A], testC: TestContext): Eq[TraceIO[A]] =
    new Eq[TraceIO[A]] {
      def eqv(x: TraceIO[A], y: TraceIO[A]): Boolean =
        eqIO[A].eqv(x.toEffect(tc), y.toEffect(tc))
    }

  private implicit def catsEffectLawsArbitraryForTraceIO[A: Arbitrary: Cogen]: Arbitrary[TraceIO[A]] =
    Arbitrary(Gen.delay(genIO[A] map TraceIO.toTraceIO))

  private val tc: TraceContext[IO] = TraceContext(
    Span.root[IO](Span.Name("calculate-quarterly-sales")).unsafeRunSync,
    TraceSystem(testSystemMetadata, new TestEmitter[IO]))

  checkAllAsync("TraceIO", implicit ec => AsyncTests[TraceIO].async[Int, Int, Int])
  checkAllAsync("TraceIO", implicit ec => BracketTests[TraceIO, Throwable].bracket[Int, Int, Int])
  checkAllAsync("TraceIO", implicit ec => {
    implicit val csIo = ec.contextShift[IO]
    ConcurrentTests[TraceIO].concurrent[Int, Int, Int]
  })
  checkAllAsync("TraceIO", implicit ec => {
    implicit val csIo = ec.contextShift[IO]
    implicit val tcInstance = tc
    ConcurrentEffectTests[TraceIO].concurrentEffect[Int, Int, Int]
  })
  checkAllAsync("TraceIO", implicit ec => {
    implicit val tcInstance = tc
    EffectTests[TraceIO].effect[Int, Int, Int]
  })
  checkAllAsync("TraceIO", implicit ec => MonadTests[TraceIO].monad[Int, Int, Int])
  checkAllAsync("TraceIO", implicit ec => MonadErrorTests[TraceIO, Throwable].monadError[Int, Int, Int])
  checkAllAsync("TraceIO", implicit ec => SyncTests[TraceIO].sync[Int, Int, Int])

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

  testAsync("ContextShift[TraceIO].shift") { ec =>
    implicit val cs = TraceIO.contextShift(ec)
    val f = cs.shift.trace(tc).unsafeToFuture()
    f.value shouldBe 'empty
    ec.tick()
    f.value shouldBe Some(Success(()))
    ()
  }

  testAsync("ContextShift[TraceIO].evalOn") { ec =>
    implicit val cs = TraceIO.contextShift(ec)
    val ec2 = TestContext()
    val f = cs.evalOn(ec2)(TraceIO(1)).trace(tc).unsafeToFuture()
    f.value shouldBe 'empty
    ec.tick()
    f.value shouldBe 'empty
    ec2.tick()
    f.value shouldBe 'empty
    ec.tick()
    f.value shouldBe Some(Success(1))
    ()
  }

  private def checkAllAsync(name: String, f: TestContext => Laws#RuleSet): Unit = {
    val context = TestContext()
    val ruleSet = f(context)
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
