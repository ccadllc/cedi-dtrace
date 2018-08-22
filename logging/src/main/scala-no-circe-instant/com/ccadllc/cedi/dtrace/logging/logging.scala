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

import io.circe._

import java.time.Instant
import java.time.format.DateTimeParseException

package object logging {
  implicit final val encodeInstant: Encoder[Instant] = Encoder.instance(time =>
    Json.fromString(time.toString))
  implicit final val decodeInstant: Decoder[Instant] = Decoder.instance { c =>
    c.as[String] match {
      case Right(s) => try Right(Instant.parse(s)) catch {
        case _: DateTimeParseException => Left(DecodingFailure("Instant", c.history))
      }
      case l @ Left(_) => l.asInstanceOf[Decoder.Result[Instant]]
    }
  }
}

