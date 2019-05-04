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

import cats.NonEmptyParallel
import cats.effect.IO
import cats.implicits._

import scala.concurrent.ExecutionContext

import org.scalatest.{ BeforeAndAfterEach, Matchers, WordSpec }

class TestEmitterRecordingTest extends WordSpec with BeforeAndAfterEach with Matchers with TestData {
  "the distributed trace recording mechanism for emitting to a test string emitter" should {
    "support recording a successful current tracing span which is the root span containing same parent and current span IDs" in {
      val testEmitter = new TestEmitter[IO]
      val testTimer = TraceSystem.realTimeTimer[IO]
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRoot = Span.root(testTimer, quarterlySalesCalculateSpanName).unsafeRunSync
      IO(Thread.sleep(5L)).toTraceT.trace(TraceContext(spanRoot, true, salesManagementSystem)).unsafeRunSync
      testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId), parentSpanId(spanRoot.spanId.spanId), spanName(spanRoot.spanName)) should have size (1)
    }
    "support recording a successful new tracing span with new span ID and name" in {
      val testEmitter = new TestEmitter[IO]
      val testTimer = TraceSystem.realTimeTimer[IO]
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRoot = Span.root(testTimer, quarterlySalesCalculateSpanName).unsafeRunSync
      IO(Thread.sleep(5L)).newSpan(quarterlyPhillySalesCalculateSpanName).trace(TraceContext(spanRoot, true, salesManagementSystem)).unsafeRunSync
      testEmitter.cache.containingAll(parentSpanId(spanRoot.spanId.spanId), spanName(quarterlyPhillySalesCalculateSpanName)) should have size (1)
      testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId)) should have size (1)
    }
    "support recording a successful new tracing span with new span ID, name, and string note" in {
      assertChildSpanRecordedOnTimeWithNote(salesRegionNote)
    }
    "support recording a successful new tracing span with new span ID, name, and boolean note" in {
      assertChildSpanRecordedOnTimeWithNote(quarterlySalesGoalReachedNote)
    }
    "support recording a successful new tracing span with new span ID, name, and long note" in {
      assertChildSpanRecordedOnTimeWithNote(quarterlySalesUnitsNote)
    }
    "support recording a successful new tracing span with new span ID, name, and double note" in {
      assertChildSpanRecordedOnTimeWithNote(quarterlySalesTotalNote)
    }
    "support recording two new successful tracing spans spawned in parallel using `Parallel` typeclass `Applicative` capabilities with notes" in {
      assertParallelApplicativeChildSpansRecordedOnTimeWithNotes(salesRegionNote, quarterlySalesGoalReachedNote)
    }
    "support recording two new successful tracing spans spawned in parallel using `NonEmptyParallel` typeclass `Apply` capabilities with notes" in {
      assertNonEmptyParallelApplyChildSpansRecordedOnTimeWithNotes(salesRegionNote, quarterlySalesGoalReachedNote)
    }
    "support recording two new successful tracing spans spawned in parallel using `Parallel` typeclass `Monad` capabilities with notes" in {
      assertParallelMonadChildSpansRecordedOnTimeWithNotes(salesRegionNote, quarterlySalesGoalReachedNote)
    }
    "support recording two new successful tracing spans spawned in parallel using `NonEmptyParallel` typeclass `FlatMap` capabilities with notes" in {
      assertNonEmptyParallelFlatMapChildSpansRecordedOnTimeWithNotes(salesRegionNote, quarterlySalesGoalReachedNote)
    }
    "support recording nested spans" in {
      val testEmitter = new TestEmitter[IO]
      val testTimer = TraceSystem.realTimeTimer[IO]
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRootName = quarterlySalesCalculateSpanName
      val spanRoot = Span.root(testTimer, spanRootName).unsafeRunSync
      def requestUpdatedSalesFigures: TraceIO[Boolean] = IO(false).newSpan(requestUpdatedSalesFiguresSpanName)
      def generateUpdatedSalesFigures: TraceIO[Unit] = IO(Thread.sleep(5L)).newSpan(generateUpdatedSalesFiguresSpanName)
      def generateSalesFigures: TraceIO[Unit] = for {
        existing <- requestUpdatedSalesFigures
        _ <- if (existing) TraceIO.pure(()) else generateUpdatedSalesFigures
      } yield ()
      generateSalesFigures.trace(TraceContext(spanRoot, true, salesManagementSystem)).unsafeRunSync
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
    "support not emitting spans when sampling is disabled" in {
      val testEmitter = new TestEmitter[IO]
      val testTimer = TraceSystem.realTimeTimer[IO]
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRootName = quarterlySalesCalculateSpanName
      val spanRoot = Span.root(testTimer, spanRootName).unsafeRunSync
      def requestUpdatedSalesFigures: TraceIO[Boolean] = IO(false).newSpan(requestUpdatedSalesFiguresSpanName)
      def generateUpdatedSalesFigures: TraceIO[Unit] = IO(Thread.sleep(5L)).newSpan(generateUpdatedSalesFiguresSpanName)
      def generateSalesFigures: TraceIO[Unit] = for {
        existing <- requestUpdatedSalesFigures
        _ <- if (existing) TraceIO.pure(()) else generateUpdatedSalesFigures
      } yield ()
      generateSalesFigures.trace(TraceContext(spanRoot, false, salesManagementSystem)).unsafeRunSync
      val entries = testEmitter.cache.all
      entries shouldBe 'empty
    }
  }

  private def assertChildSpanRecordedOnTimeWithNote(note: Note): Unit = {
    def assertWithTimer(testTimer: TraceSystem.Timer[IO]): Unit = {
      val testEmitter = new TestEmitter[IO]
      val start = testTimer.time.unsafeRunSync
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRoot = Span.root(testTimer, quarterlySalesCalculateSpanName).unsafeRunSync
      IO {
        Thread.sleep(5L)
        note
      }.newAnnotatedSpan(quarterlyPhillySalesCalculateSpanName) { case Right(n) => Vector(n) }.trace(
        TraceContext(spanRoot, true, salesManagementSystem)).unsafeRunSync
      val end = testTimer.time.unsafeRunSync
      val entriesWithNote = testEmitter.cache.containingAll(
        parentSpanId(spanRoot.spanId.spanId), spanName(quarterlyPhillySalesCalculateSpanName), note.toString)
      val entriesWithSpanId = testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId))
      entriesWithNote should have size (1)
      entriesWithSpanId should have size (1)
      entriesWithNote.foreach(_.tc.currentSpan.startTime.value should (be >= start.value and be <= end.value))
      entriesWithSpanId.foreach(_.tc.currentSpan.startTime.value should (be >= start.value and be <= end.value))
      ()
    }
    assertWithTimer(TraceSystem.realTimeTimer[IO])
    assertWithTimer(TraceSystem.monotonicTimer[IO])
  }

  private def assertParallelApplicativeChildSpansRecordedOnTimeWithNotes(note1: Note, note2: Note): Unit = {
    implicit val co = IO.contextShift(ExecutionContext.global)
    assertParallelChildSpansRecordedOnTimeWithNotes(note1, note2) { (io1: TraceIO[Unit], io2: TraceIO[Unit]) =>
      (io1 -> io2).parTupled.void
    }
  }

  private def assertNonEmptyParallelApplyChildSpansRecordedOnTimeWithNotes(note1: Note, note2: Note): Unit = {
    implicit val co = IO.contextShift(ExecutionContext.global)
    assertParallelChildSpansRecordedOnTimeWithNotes(note1, note2) { (io1: TraceIO[Unit], io2: TraceIO[Unit]) =>
      (io1 -> io2).parTupled(NonEmptyParallel[TraceIO, TraceIO.Par]).void
    }
  }

  private def assertParallelMonadChildSpansRecordedOnTimeWithNotes(note1: Note, note2: Note): Unit = {
    implicit val co = IO.contextShift(ExecutionContext.global)
    assertParallelChildSpansRecordedOnTimeWithNotes(note1, note2) { (io1: TraceIO[Unit], io2: TraceIO[Unit]) =>
      List(io1, io2).parSequence_
    }
  }

  private def assertNonEmptyParallelFlatMapChildSpansRecordedOnTimeWithNotes(note1: Note, note2: Note): Unit = {
    implicit val co = IO.contextShift(ExecutionContext.global)
    assertParallelChildSpansRecordedOnTimeWithNotes(note1, note2) { (io1: TraceIO[Unit], io2: TraceIO[Unit]) =>
      NonEmptyParallel[TraceIO, TraceIO.Par].parProductR(io1)(io2)
    }
  }

  private def assertParallelChildSpansRecordedOnTimeWithNotes(
    note1: Note, note2: Note)(parAction: (TraceIO[Unit], TraceIO[Unit]) => TraceIO[Unit]): Unit = {
    def assertWithTimer(testTimer: TraceSystem.Timer[IO]): Unit = {
      val testEmitter = new TestEmitter[IO]
      val start = testTimer.time.unsafeRunSync
      val salesManagementSystem = TraceSystem(testSystemData, testEmitter, testTimer)
      val spanRoot = Span.root(testTimer, quarterlySalesCalculateSpanName).unsafeRunSync
      parAction(
        IO(Thread.sleep(3)).void.newSpan(quarterlyPhillySales1CalculateSpanName, note1),
        IO(
          Thread.sleep(5)).void.newSpan(quarterlyPhillySales2CalculateSpanName, note2)).trace(TraceContext(spanRoot, true, salesManagementSystem)).unsafeRunSync
      val end = testTimer.time.unsafeRunSync
      val entriesWithNote1 = testEmitter.cache.containingAll(
        parentSpanId(spanRoot.spanId.spanId), spanName(quarterlyPhillySales1CalculateSpanName), note1.toString)
      val entriesWithNote2 = testEmitter.cache.containingAll(
        parentSpanId(spanRoot.spanId.spanId), spanName(quarterlyPhillySales2CalculateSpanName), note2.toString)
      val entriesWithSpanId = testEmitter.cache.containingAll(spanId(spanRoot.spanId.spanId))
      entriesWithNote1 should have size (1)
      entriesWithNote2 should have size (1)
      entriesWithSpanId should have size (1)
      entriesWithNote1.foreach(_.tc.currentSpan.startTime.value should (be >= start.value and be <= end.value))
      entriesWithNote2.foreach(_.tc.currentSpan.startTime.value should (be >= start.value and be <= end.value))
      entriesWithSpanId.foreach(_.tc.currentSpan.startTime.value should (be >= start.value and be <= end.value))
      ()
    }
    assertWithTimer(TraceSystem.realTimeTimer[IO])
    assertWithTimer(TraceSystem.monotonicTimer[IO])
  }
  private def spanName(name: Span.Name) = s"span-name=$name"
  private def spanId(id: Long) = s"span-id=$id"
  private def parentSpanId(id: Long) = s"parent-id=$id"
}
