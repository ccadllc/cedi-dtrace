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
package logstash

import cats.effect.IO
import org.scalatest.WordSpec

class LogstashLogbackEmitterTest extends WordSpec with TestData {
  "LogstashLogbackEmitterTest" should {
    "work" in {
      val system = TraceSystem(testSystemMetadata, new LogstashLogbackEmitter[IO])
      val spanRoot = Span.root[IO](Span.Name("calculate-quarterly-sales")).unsafeRunSync
      IO.unit.toTraceT.trace(TraceContext(spanRoot, system)).unsafeRunSync
    }
  }
}