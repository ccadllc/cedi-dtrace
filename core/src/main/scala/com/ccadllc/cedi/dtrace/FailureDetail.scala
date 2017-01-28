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

import java.io.{ PrintWriter, StringWriter }

/**
 * This Algebraic Data Type (ADT) represents a failure of the underlying traced program, providing the
 * means to render the failure when the span is recorded.
 */
sealed abstract class FailureDetail extends Product with Serializable { def render: String }

/**
 * The `FailureDetail` companion consists of the enumerated concrete `FailureDetail` data types.
 */
object FailureDetail {

  /**
   * A Failure Detail constructed from a human readable message.
   * @param message - a string which constitute the message to be used for the failure detail.
   */
  final case class Message(message: String) extends FailureDetail {
    override def render: String = message
  }

  /**
   * A Failure Detail constructed from a `java.lang.Throwable`.
   * @param cause - the `Throwable` whose stack trace is converted into a string and used as the failure detail.
   */
  final case class Exception(cause: Throwable) extends FailureDetail {
    override def render: String = {
      val exceptionMessage = new StringWriter
      cause.printStackTrace(new PrintWriter(exceptionMessage))
      exceptionMessage.toString
    }
  }

  /**
   * Constructs a [[Message]]-based [[FailureDetail]] failure detail from the passed-in `String`.
   * @param m - the string used to generate the failure detail.
   * @return messageFailureDetail - a [[FailureDetail]] generated from the passed-in `String`.
   */
  def apply(m: String): FailureDetail = Message(m)

  /**
   * Constructs an `Exception` failure detail from the passed-in `Throwable`.
   * @param cause - the `Throwable` used to generate the failure detail.
   * @return exceptionFailureDetail - a [[FailureDetail]] generated from the passed-in `Throwable`.
   */
  def apply(e: Throwable): FailureDetail = Exception(e)
}
