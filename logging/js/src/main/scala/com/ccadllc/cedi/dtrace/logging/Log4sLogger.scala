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

import cats.effect.Sync

import io.chrisdavenport.log4cats._

object Log4sLogger {
  def create[F[_]: Sync](name: String): SelfAwareLogger[F] = new SelfAwareLogger[F] {
    private val logger = org.log4s.getLogger(name)

    override def isTraceEnabled: F[Boolean] =
      Sync[F].delay(logger.isTraceEnabled)

    override def isDebugEnabled: F[Boolean] =
      Sync[F].delay(logger.isDebugEnabled)

    override def isInfoEnabled: F[Boolean] =
      Sync[F].delay(logger.isInfoEnabled)

    override def isWarnEnabled: F[Boolean] =
      Sync[F].delay(logger.isWarnEnabled)

    override def isErrorEnabled: F[Boolean] =
      Sync[F].delay(logger.isErrorEnabled)

    override def error(message: => String): F[Unit] =
      Sync[F].delay(logger.error(message))

    override def error(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.error(t)(message))

    override def warn(message: => String): F[Unit] =
      Sync[F].delay(logger.warn(message))

    override def warn(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.warn(t)(message))

    override def info(message: => String): F[Unit] =
      Sync[F].delay(logger.info(message))

    override def info(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.info(t)(message))

    override def debug(message: => String): F[Unit] =
      Sync[F].delay(logger.debug(message))

    override def debug(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.debug(t)(message))

    override def trace(message: => String): F[Unit] =
      Sync[F].delay(logger.trace(message))

    override def trace(t: Throwable)(message: => String): F[Unit] =
      Sync[F].delay(logger.trace(t)(message))
  }
}
