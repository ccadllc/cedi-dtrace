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

import cats.syntax.all._

import io.circe._

package object logging {
  implicit final val encodeTime: Encoder[TraceSystem.Time] = Encoder.instance { time =>
    Json.fromString(time.show)
  }
  implicit final val decodeTime: Decoder[TraceSystem.Time] = Decoder.instance { c =>
    c.as[String] match {
      case Right(s) => TraceSystem.Time.parse(s).leftMap {
        fd => DecodingFailure(s"Failed to parse $s to Time (${fd.render})", c.history)
      }
      case l @ Left(_) => l.asInstanceOf[Decoder.Result[TraceSystem.Time]]
    }
  }
}

