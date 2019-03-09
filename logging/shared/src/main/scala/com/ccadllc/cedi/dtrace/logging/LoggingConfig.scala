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

import cats.effect.Sync

import scala.language.higherKinds

import slogging.{ LoggerConfig => SLoggerConfig, LogLevel }

/**
 * Provides the for the initialization of the underlying logging
 * configuration, the target for which is dependent on the implementation
 * of the `LoggingFactory.value` (which differs depending on whether the
 * target is to the JVM or JavaScript platform).
 */
object LoggingConfig {
  def initialize[F[_]](implicit F: Sync[F]): F[Unit] = F.delay {
    SLoggerConfig.factory = LoggingFactory.value
    SLoggerConfig.level = LogLevel.DEBUG
  }
}
