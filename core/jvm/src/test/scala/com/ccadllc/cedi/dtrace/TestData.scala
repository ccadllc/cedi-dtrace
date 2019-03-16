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

import cats.effect._

import java.util.UUID

import scala.concurrent.duration._

trait TestData {

  // format: OFF
  protected val testSystemData: TraceSystem.Data = TraceSystem.Data(
    TraceSystem.Data.Identity(
      Map(
        "application name"  -> "widget sales manager",
        "application ID"    -> UUID.randomUUID.toString,
        "process GUID"      -> UUID.randomUUID.toString
      )
    ),
    TraceSystem.Data.Meta(
      Map(
        "deployment name"  -> "us-west-2",
        "environment name" -> "production"
      )
    )
  )
  // format: ON
  protected val quarterlySalesCalculateSpanName: Span.Name = Span.Name("calculate-quarterly-sales")
  protected val quarterlySalesCalculationSpanId: SpanId = SpanId(UUID.randomUUID, 20L, 30L)
  protected val quarterlySalesUnitsNoteValue: Note.LongValue = Note.LongValue(450000L)
  protected val quarterlySalesUnitsNote: Note = Note(Note.Name("quarterly-sales-units"), Some(quarterlySalesUnitsNoteValue))
  protected val quarterlySalesGoalReachedNoteValue: Note.BooleanValue = Note.BooleanValue(true)
  protected val quarterlySalesGoalReachedNote: Note = Note(Note.Name("quarterly-sales-goal-reached"), Some(quarterlySalesGoalReachedNoteValue))
  protected val quarterlyPhillySpecificNoteValue: Note.BooleanValue = Note.BooleanValue(true)
  protected val quarterlyPhillySpecificNote: Note = Note(Note.Name("quarterly-philly-specific-note"), Some(quarterlyPhillySpecificNoteValue))
  protected val salesRegionNoteValue: Note.StringValue = Note.StringValue("philadelphia-county-sales-region")
  protected val salesRegionNote: Note = Note(Note.Name("sales-region"), Some(salesRegionNoteValue))
  protected val quarterlySalesTotalNoteValue: Note.DoubleValue = Note.DoubleValue(95.6)
  protected val quarterlySalesTotalNote: Note = Note(Note.Name("quarterly-sales-total"), Some(quarterlySalesTotalNoteValue))
  protected val quarterlyPhillySpecificSpanNotes: Vector[Note] = Vector[Note](quarterlyPhillySpecificNote)
  protected val quarterlySalesCalculationSpanNotes: Vector[Note] = Vector[Note](
    quarterlySalesUnitsNote, quarterlySalesGoalReachedNote, salesRegionNote, quarterlySalesTotalNote)
  protected val quarterlySalesCalculationTimer: TraceSystem.Timer[IO] = TraceSystem.realTimeTimer[IO]
  protected val quarterlySalesCalculationSpan: Span = Span(
    quarterlySalesCalculationSpanId,
    quarterlySalesCalculateSpanName,
    quarterlySalesCalculationTimer.time.unsafeRunSync,
    None,
    15000.microseconds,
    quarterlySalesCalculationSpanNotes)
  protected val quarterlyPhillySalesCalculateSpanName: Span.Name = Span.Name("calculate-quarterly-sales-for-philly")
  protected val requestUpdatedSalesFiguresSpanName: Span.Name = Span.Name("request-updated-sales-figures")
  protected val generateUpdatedSalesFiguresSpanName: Span.Name = Span.Name("generate-updated-sales-figures")
  protected val quarterlyPhillySales1CalculateSpanName: Span.Name = Span.Name("calculate-quarterly-sales-1-for-philly")
  protected val quarterlyPhillySales2CalculateSpanName: Span.Name = Span.Name("calculate-quarterly-sales-2-for-philly")
}
