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

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply

import fs2.Task

import java.util.UUID

import org.scalatest.{ BeforeAndAfterEach, Suite }

import syntax._

trait RecordingTestSupport extends TestSupport with BeforeAndAfterEach {
  self: Suite =>

  override def beforeEach(): Unit = {
    super.beforeEach()
    LogEmitterTestCache.clear()
  }

  override def afterEach(): Unit = {
    LogEmitterTestCache.clear()
    super.afterEach()
  }

  protected def logEmitterId: String
  protected def spanName(name: Span.Name): String
  protected def spanId(id: Long): String
  protected def parentSpanId(id: Long): String
  protected def traceId(id: UUID): String
  protected def spanNote(note: Note): String

  protected def assertRootSpanRecorded(): Unit = {
    val spanRoot = Span.root[Task](Span.Name("refresh-config")).unsafeRun
    Task.delay(Thread.sleep(5L)).toTraceT.trace(TraceContext(spanRoot, terminalHandlerSystem)).unsafeRun
    LogEmitterTestCache.containingAll(spanId(spanRoot.spanId.spanId), parentSpanId(spanRoot.spanId.spanId), spanName(spanRoot.spanName)) should have size (1)
    ()
  }

  protected def assertChildSpanRecorded(): Unit = {
    val spanRoot = Span.root[Task](Span.Name("refresh-config")).unsafeRun
    val buildEmmsSpanName = Span.Name("build-emms")
    Task.delay(Thread.sleep(5L)).newSpan(buildEmmsSpanName).trace(TraceContext(spanRoot, terminalHandlerSystem)).unsafeRun
    LogEmitterTestCache.containingAll(parentSpanId(spanRoot.spanId.spanId), spanName(buildEmmsSpanName)) should have size (1)
    LogEmitterTestCache.containingAll(spanId(spanRoot.spanId.spanId)) should have size (1)
    ()
  }

  protected def assertChildSpanRecordedWithNote(note: Note): Unit = {
    val spanRoot = Span.root[Task](Span.Name("refresh-config")).unsafeRun
    val buildEmmsSpanName = Span.Name("build-emms")
    Task.delay(Thread.sleep(5L)).newSpan(buildEmmsSpanName, note).trace(TraceContext(spanRoot, terminalHandlerSystem)).unsafeRun
    val entriesWithNote = LogEmitterTestCache.containingAll(parentSpanId(spanRoot.spanId.spanId), spanName(buildEmmsSpanName), spanNote(note))
    val entriesWithSpanId = LogEmitterTestCache.containingAll(spanId(spanRoot.spanId.spanId))
    entriesWithNote should have size (1)
    entriesWithSpanId should have size (1)
    ()
  }

  protected def assertNestedSpansRecorded(): Unit = {
    val spanRootName = Span.Name("refresh-config")
    val spanRoot = Span.root[Task](spanRootName).unsafeRun
    val buildEmmsSpanName = Span.Name("build-emms")
    val requestExistingEmmsSpanName = Span.Name("request-existing-emms")
    val buildNewEmmsSpanName = Span.Name("build-emms")
    def buildEmms: TraceTask[Unit] = for {
      existing <- requestExistingEmms
      _ <- if (existing) TraceTask.now(()) else buildNewEmms
    } yield ()
    def requestExistingEmms: TraceTask[Boolean] = Task.delay(false).newSpan(requestExistingEmmsSpanName)
    def buildNewEmms: TraceTask[Unit] = Task.delay(Thread.sleep(5L)).newSpan(buildNewEmmsSpanName)
    buildEmms.trace(TraceContext(spanRoot, terminalHandlerSystem)).unsafeRun
    val entries = LogEmitterTestCache.containingAll(logEmitterId)
    entries should have size (3)
    entries(0).msg should include(spanName(requestExistingEmmsSpanName))
    entries(0).msg should include(parentSpanId(spanRoot.spanId.spanId))
    entries(0).msg should not include (spanId(spanRoot.spanId.spanId))
    entries(1).msg should include(spanName(buildNewEmmsSpanName))
    entries(1).msg should include(parentSpanId(spanRoot.spanId.spanId))
    entries(1).msg should not include (spanId(spanRoot.spanId.spanId))
    entries(2).msg should include(spanName(spanRootName))
    entries(2).msg should include(parentSpanId(spanRoot.spanId.spanId))
    entries(2).msg should include(spanId(spanRoot.spanId.spanId))
    ()
  }
}

object LogEmitterTestCache {
  case class LogEmitterTestEntry(msg: String)
  private var emitterLogCache: Vector[LogEmitterTestEntry] = Vector.empty

  def clear(): Unit = synchronized { emitterLogCache = Vector.empty }
  def put(msg: String): Unit = synchronized { emitterLogCache = emitterLogCache :+ LogEmitterTestEntry(msg) }

  def containingAll(substrings: String*): Vector[LogEmitterTestEntry] = {
    def containing(substrings: Seq[String])(predicate: (LogEmitterTestEntry, Seq[String]) => Boolean): Vector[LogEmitterTestEntry] = {
      require(!substrings.isEmpty)
      val lowerSubstrings = substrings map { _.toLowerCase }
      emitterLogCache filter { predicate(_, lowerSubstrings) }
    }
    containing(substrings) { (e, strings) => strings forall { e.msg.contains } }
  }
}

class TestLogInterceptor extends Filter[ILoggingEvent] {
  import LogEmitterTestCache._
  override def decide(event: ILoggingEvent): FilterReply = {
    val msgLower = event.getMessage.toLowerCase
    put(msgLower)
    FilterReply.NEUTRAL
  }
}
