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

import fs2.util.Applicative

import scala.language.higherKinds

/**
 * System level configuration for the trace system.
 *
 * @param metadata - top-level metadata that should be included in all trace span recordings (examples include:
 *   node, deployment, program identities).
 * @param emitter - responsible for actually recording the span information for a distributed trace
 *   to some external sink (e.g., log file, remote database, JMX, etc.).
 */
case class TraceSystem[F[_]](metadata: Map[String, String], emitter: TraceSystem.Emitter[F]) {
  override def toString: String = s"[metadata=${metadata.mkString(",")}] [emitter=${emitter.description}]"
}

object TraceSystem {
  /**
   * Describes how to emit the current [[Span]] and its metadata (together constituting the [[TraceContext]]) to an external sink
   * (e.g., database, JMX, log file, etc).
   */
  trait Emitter[F[_]] {
    /** Emits the [[Span]] and metadata in the [[TraceContext]] to some external sink using the effectful program `F[A]` */
    def emit(tc: TraceContext[F]): F[Unit]

    /** Provides a description of this emitter */
    def description: String
  }

  private[dtrace] def empty[F[_]](implicit F: Applicative[F]): TraceSystem[F] = TraceSystem[F](
    Map.empty,
    new Emitter[F] {
      override def emit(tc: TraceContext[F]): F[Unit] = F.pure(())
      override val description: String = "Empty Emitter"
    }
  )
}
