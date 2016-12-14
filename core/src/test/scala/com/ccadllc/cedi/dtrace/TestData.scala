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

  protected val testApplication: TraceSystem.Identity.Application = TraceSystem.Identity.Application("widget sales manager", UUID.randomUUID)
  protected val testNode: TraceSystem.Identity.Node = TraceSystem.Identity.Node("crm.widgetsales.com", UUID.randomUUID)
  protected val testProcess: TraceSystem.Identity.Process = TraceSystem.Identity.Process(UUID.randomUUID)
  protected val testDeployment: TraceSystem.Identity.Deployment = TraceSystem.Identity.Deployment("Ashburn VA DC")
  protected val testEnvironment: TraceSystem.Identity.Environment = TraceSystem.Identity.Environment("production")
  protected val testIdentity: TraceSystem.Identity = TraceSystem.Identity(testApplication, testNode, testProcess, testDeployment, testEnvironment)

  protected val quarterlySalesCalculationSpanId: SpanId = SpanId(UUID.randomUUID, 20L, 30L)
  protected val quarterlySalesUnitsNoteValue: Note.LongValue = Note.LongValue(450000L)
  protected val quarterlySalesUnitsNote: Note = Note(Note.Name("quarterlySalesUnits"), Some(quarterlySalesUnitsNoteValue))
  protected val quarterlySalesGoalReachedNoteValue: Note.BooleanValue = Note.BooleanValue(true)
  protected val quarterlySalesGoalReachedNote: Note = Note(Note.Name("quarterlySalesGoalReached"), Some(quarterlySalesGoalReachedNoteValue))
  protected val salesRegionNoteValue: Note.StringValue = Note.StringValue("Philadelphia County Sales Region")
  protected val salesRegionNote: Note = Note(Note.Name("salesRegion"), Some(salesRegionNoteValue))
  protected val quarterlySalesTotalNoteValue: Note.DoubleValue = Note.DoubleValue(95.6)
  protected val quarterlySalesTotalNote: Note = Note(Note.Name("quarterlySalesTotal"), Some(quarterlySalesTotalNoteValue))
  protected val quarterlySalesCalculationSpanNotes: Vector[Note] = Vector[Note](
    quarterlySalesUnitsNote, quarterlySalesGoalReachedNote, salesRegionNote, quarterlySalesTotalNote
  )
  protected val quarterlySalesCalculationSpan: Span = Span(
    quarterlySalesCalculationSpanId,
    Span.Name("Calculate Quarterly Sales"),
    Instant.now,
    None,
    15000.microseconds,
    quarterlySalesCalculationSpanNotes
  )
}
