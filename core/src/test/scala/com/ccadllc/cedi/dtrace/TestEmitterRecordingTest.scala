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

import cats.effect.{ IO, Sync }

import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

import scala.language.higherKinds

class TestEmitterRecordingTest extends WordSpec with BeforeAndAfterEach with Matchers with TestData {
  private class TestEmitter[F[_]](implicit F: Sync[F]) extends TraceSystem.Emitter[F] {
    class EmitterTestCache {
      case class EmitterTestEntry(msg: String)
      private var emitterLogCache: Vector[EmitterTestEntry] = Vector.empty
      def put(msg: String): Unit = synchronized { emitterLogCache = emitterLogCache :+ EmitterTestEntry(msg.toLowerCase) }
      def all: Vector[EmitterTestEntry] = emitterLogCache
      def containingAll(substrings: String*): Vector[EmitterTestEntry] = {
        def containing(substrings: Seq[String])(predicate: (EmitterTestEntry, Seq[String]) => Boolean): Vector[EmitterTestEntry] = {
          require(!substrings.isEmpty)
          val lowerSubstrings = substrings map { _.toLowerCase }
          emitterLogCache filter { predicate(_, lowerSubstrings) }
        }
        containing(substrings) { (e, strings) => strings forall { e.msg.contains } }
      }
    }
    val cache = new EmitterTestCache
    override def description: String = "Test Emitter"
    override def emit(tc: TraceContext[F]): F[Unit] = {
      def formatText(context: TraceContext[F]) = {
        s"Span: [ span-id=${context.currentSpan.spanId.spanId} ] [ trace-id=${context.currentSpan.spanId.traceId} ] [ parent-id=${context.currentSpan.spanId.parentSpanId} ] [ span-name=${context.currentSpan.spanName} ] [ system-metadata=${context.system.metadata.mkString(",")} ] [ start-time=${context.currentSpan.startTime} ] [ span-duration=${context.currentSpan.duration} ] [ span-success=${context.currentSpan.failure.isEmpty} ] [ failure-detail=${context.currentSpan.failure.fold("N/A")(_.render)} ][ notes=[${context.currentSpan.notes.mkString("] [")}] ]"
      }
      F.delay(cache.put(formatText(tc)))
    }
  }

  "the distributed trace recording mechanism for emitting to a test string emitter" should {
    "support recording a successful current tracing span which is the root span containing same parent and current span IDs" in {
      val testEmitter = new TestEmitter[IO]
      val salesManagementSystem = TraceSystem(testSystemMetadata, testEmitter)
      val spanRoot = Span.root[IO](Span.Name("calculate-quarterly-sales")).unsafeRunSync
      IO(Thread.sleep(5L)).toTraceT.trace(TraceContext(spanRoot, salesManagementSystem)).unsafeRunSync
      testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId), parentSpanId(spanRoot.spanId.spanId), spanName(spanRoot.spanName)) should have size (1)
    }
    "support recording a successful new tracing span with new span ID and name" in {
      val testEmitter = new TestEmitter[IO]
      val salesManagementSystem = TraceSystem(testSystemMetadata, testEmitter)
      val spanRoot = Span.root[IO](Span.Name("calculate-quarterly-sales")).unsafeRunSync
      val calcPhillySalesSpanName = Span.Name("calculate-sales-for-philadelphia")
      IO(Thread.sleep(5L)).newSpan(calcPhillySalesSpanName).trace(TraceContext(spanRoot, salesManagementSystem)).unsafeRunSync
      testEmitter.cache.containingAll(parentSpanId(spanRoot.spanId.spanId), spanName(calcPhillySalesSpanName)) should have size (1)
      testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId)) should have size (1)
    }
    "support recording a successful new tracing span with new span ID, name, and string note" in {
      assertChildSpanRecordedWithNote(salesRegionNote)
    }
    "support recording a successful new tracing span with new span ID, name, and boolean note" in {
      assertChildSpanRecordedWithNote(quarterlySalesGoalReachedNote)
    }
    "support recording a successful new tracing span with new span ID, name, and long note" in {
      assertChildSpanRecordedWithNote(quarterlySalesUnitsNote)
    }
    "support recording a successful new tracing span with new span ID, name, and double note" in {
      assertChildSpanRecordedWithNote(quarterlySalesTotalNote)
    }
    "support recording nested spans" in {
      val testEmitter = new TestEmitter[IO]
      val salesManagementSystem = TraceSystem(testSystemMetadata, testEmitter)
      val spanRootName = Span.Name("calculate-quarterly-sales-update")
      val spanRoot = Span.root[IO](spanRootName).unsafeRunSync
      val requestUpdatedSalesFiguresSpanName = Span.Name("request-updated-sales-figures")
      val generateUpdatedSalesFiguresSpanName = Span.Name("generate-updated-sales-figures")
      def generateSalesFigures: TraceIO[Unit] = for {
        existing <- requestUpdatedSalesFigures
        _ <- if (existing) TraceIO.pure(()) else generateUpdatedSalesFigures
      } yield ()
      def requestUpdatedSalesFigures: TraceIO[Boolean] = IO(false).newSpan(requestUpdatedSalesFiguresSpanName)
      def generateUpdatedSalesFigures: TraceIO[Unit] = IO(Thread.sleep(5L)).newSpan(generateUpdatedSalesFiguresSpanName)
      generateSalesFigures.trace(TraceContext(spanRoot, salesManagementSystem)).unsafeRunSync
      val entries = testEmitter.cache.all
      entries should have size (3)
      entries(0).msg should include(spanName(requestUpdatedSalesFiguresSpanName))
      entries(0).msg should include(parentSpanId(spanRoot.spanId.spanId))
      entries(0).msg should not include (spanId(spanRoot.spanId.spanId))
      entries(1).msg should include(spanName(generateUpdatedSalesFiguresSpanName))
      entries(1).msg should include(parentSpanId(spanRoot.spanId.spanId))
      entries(1).msg should not include (spanId(spanRoot.spanId.spanId))
      entries(2).msg should include(spanName(spanRootName))
      entries(2).msg should include(parentSpanId(spanRoot.spanId.spanId))
      entries(2).msg should include(spanId(spanRoot.spanId.spanId))
    }
  }

  private def assertChildSpanRecordedWithNote(note: Note): Unit = {
    val testEmitter = new TestEmitter[IO]
    val salesManagementSystem = TraceSystem(testSystemMetadata, testEmitter)
    val spanRoot = Span.root[IO](Span.Name("calculate-quarterly-sales-updates")).unsafeRunSync
    val calcPhillySalesSpanName = Span.Name("calculate-updated-sales-for-philly")
    IO {
      Thread.sleep(5L)
      note
    }.newAnnotatedSpan(calcPhillySalesSpanName) { case Right(n) => Vector(n) }.trace(TraceContext(spanRoot, salesManagementSystem)).unsafeRunSync
    val entriesWithNote = testEmitter.cache.containingAll(parentSpanId(spanRoot.spanId.spanId), spanName(calcPhillySalesSpanName), note.toString)
    val entriesWithSpanId = testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId))
    entriesWithNote should have size (1)
    entriesWithSpanId should have size (1)
    ()
  }
  private def spanName(name: Span.Name) = s"span-name=$name"
  private def spanId(id: Long) = s"span-id=$id"
  private def parentSpanId(id: Long) = s"parent-id=$id"
}
