/*
 * Copyright 2019 Combined Conditional Access Development, LLC.
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
 *
 * NOTICE: This work was derived from https://github.com/ChristopherDavenport/log4cats-log4s
 * by Christopher Davenport, licensed under Apache License, Version 2.0.
 */

package com.ccadllc.cedi.dtrace
package logging

import cats.Applicative
import cats.effect.Sync
import cats.implicits._

import io.chrisdavenport.log4cats._

import java.io.PrintWriter

import org.scalajs.logging._

object ScalajsLogger {
  def create[F[_]: Sync](name: String, level: Option[LoggingLevel]): SelfAwareLogger[F] = new SelfAwareLogger[F] {
    private val traceEnabled = level.exists(_ === LoggingLevel.Trace)
    private val debugEnabled = level.exists(_ === LoggingLevel.Debug)
    private val infoEnabled = level.exists(_ === LoggingLevel.Info)
    private val warnEnabled = level.exists(_ === LoggingLevel.Warn)
    private val errorEnabled = level.exists(_ === LoggingLevel.Error)

    private val logger = level match {
      case Some(LoggingLevel.Trace | LoggingLevel.Debug) =>
        new ScalaConsoleLogger(Level.Debug)
      case Some(LoggingLevel.Info) =>
        new ScalaConsoleLogger(Level.Info)
      case Some(LoggingLevel.Warn) =>
        new ScalaConsoleLogger(Level.Warn)
      case Some(LoggingLevel.Error) =>
        new ScalaConsoleLogger(Level.Error)
      case _ => NullLogger
    }

    override def isTraceEnabled: F[Boolean] =
      Applicative[F].pure(traceEnabled)

    override def isDebugEnabled: F[Boolean] =
      Applicative[F].pure(debugEnabled)

    override def isInfoEnabled: F[Boolean] =
      Applicative[F].pure(infoEnabled)

    override def isWarnEnabled: F[Boolean] =
      Applicative[F].pure(warnEnabled)

    override def isErrorEnabled: F[Boolean] =
      Applicative[F].pure(errorEnabled)

    override def error(message: => String): F[Unit] =
      Sync[F].delay(logger.error(message))

    override def error(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.error(withStackTrace(message, t)))

    override def warn(message: => String): F[Unit] =
      Sync[F].delay(logger.warn(message))

    override def warn(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.warn(withStackTrace(message, t)))

    override def info(message: => String): F[Unit] =
      Sync[F].delay(logger.info(message))

    override def info(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.info(withStackTrace(message, t)))

    override def debug(message: => String): F[Unit] =
      Sync[F].delay(logger.debug(message))

    override def debug(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.debug(withStackTrace(message, t)))

    override def trace(message: => String): F[Unit] =
      Sync[F].delay(logger.debug(message)).whenA(traceEnabled)

    override def trace(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.trace(withStackTrace(message, t))).whenA(traceEnabled)

    private def withStackTrace(message: => String, t: Throwable) = {
      val errors = new StringWriter
      t.printStackTrace(new PrintWriter(errors))
      show"$message: ${errors.toString}"
    }
  }
}
