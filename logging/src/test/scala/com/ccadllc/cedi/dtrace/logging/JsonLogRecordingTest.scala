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

import java.util.UUID

import org.scalatest.WordSpec

class JsonLogRecordingTest extends WordSpec with RecordingTestSupport {
  override protected def logEmitterId: String = """"span-id""""
  override protected def spanName(name: Span.Name) = s""""span-name":"$name""""
  override protected def spanId(id: Long) = s""""span-id":$id"""
  override protected def parentSpanId(id: Long) = s""""parent-id":$id"""
  override protected def traceId(id: UUID) = s""""trace-id":"$id""""
  override protected def spanNote(note: Note) = s""""notes":{"${note.name}":"${note.value.fold("")(_.toString)}"}"""

  "the distributed trace recording mechanism for emitting to a log with JSON" should {
    "support recording a successful current tracing span which is the root span containing same parent and current span IDs" in {
      assertRootSpanRecorded()
    }
    "support recording a successful new tracing span with new span ID and name" in {
      assertChildSpanRecorded()
    }
    "support recording a successful new tracing span with new span ID, name, and string note" in {
      assertChildSpanRecordedWithNote(updateEmmsStringNote)
    }
    "support recording a successful new tracing span with new span ID, name, and boolean note" in {
      assertChildSpanRecordedWithNote(updateEmmsBooleanNote)
    }
    "support recording a successful new tracing span with new span ID, name, and long note" in {
      assertChildSpanRecordedWithNote(updateEmmsLongNote)
    }
    "support recording a successful new tracing span with new span ID, name, and double note" in {
      assertChildSpanRecordedWithNote(updateEmmsDoubleNote)
    }
    "support recording nested spans" in {
      assertNestedSpansRecorded()
    }
  }
}
