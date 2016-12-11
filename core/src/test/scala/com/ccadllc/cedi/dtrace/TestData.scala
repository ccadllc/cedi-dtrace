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

import fs2.Strategy

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{ Executors, ExecutorService }

import scala.concurrent.duration._

trait TestData {

  protected implicit val testExecutor: ExecutorService = Executors.newCachedThreadPool

  protected implicit val testStrategy: Strategy = Strategy.fromExecutor(testExecutor)

  protected val testApplication: TraceSystem.Identity.Application = TraceSystem.Identity.Application("terminal manager", UUID.randomUUID)
  protected val testNode: TraceSystem.Identity.Node = TraceSystem.Identity.Node("terminal-manager-xyz001.comcast.com", UUID.randomUUID)
  protected val testProcess: TraceSystem.Identity.Process = TraceSystem.Identity.Process(UUID.randomUUID)
  protected val testDeployment: TraceSystem.Identity.Deployment = TraceSystem.Identity.Deployment("Ashburn VA DC")
  protected val testEnvironment: TraceSystem.Identity.Environment = TraceSystem.Identity.Environment("production")
  protected val testIdentity: TraceSystem.Identity = TraceSystem.Identity(testApplication, testNode, testProcess, testDeployment, testEnvironment)

  protected val updateEmmsSpanId: SpanId = SpanId(UUID.randomUUID, 20L, 30L)
  protected val updateEmmsLongNoteValue: Note.LongValue = Note.LongValue(450L)
  protected val updateEmmsLongNote: Note = Note(Note.Name("sizeOfEmms"), Some(updateEmmsLongNoteValue))
  protected val updateEmmsBooleanNoteValue: Note.BooleanValue = Note.BooleanValue(true)
  protected val updateEmmsBooleanNote: Note = Note(Note.Name("buildByCET"), Some(updateEmmsBooleanNoteValue))
  protected val updateEmmsStringNoteValue: Note.StringValue = Note.StringValue("DAC Philadelphia County")
  protected val updateEmmsStringNote: Note = Note(Note.Name("securityDomain"), Some(updateEmmsStringNoteValue))
  protected val updateEmmsDoubleNoteValue: Note.DoubleValue = Note.DoubleValue(95.6)
  protected val updateEmmsDoubleNote: Note = Note(Note.Name("percentageRebuild"), Some(updateEmmsDoubleNoteValue))
  protected val updateEmmsSpanNotes: Vector[Note] = Vector[Note](updateEmmsLongNote, updateEmmsBooleanNote, updateEmmsStringNote, updateEmmsDoubleNote)
  protected val updateEmmsSpan: Span = Span(updateEmmsSpanId, Span.Name("Update EMMs"), Instant.now, None, 15000.microseconds, updateEmmsSpanNotes)

}
