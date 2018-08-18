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

import cats.Applicative

import scala.language.higherKinds

/**
 * System level configuration for the trace system.
 *
 * @param metadata - top-level metadata that should be included in all trace span recordings (examples include:
 *   node, deployment, and environment information).
 * @param emitter - [[TraceSystem#Emitter]] responsible for actually recording the span information for a distributed trace
 *   to some external sink (e.g., log file, remote database, JMX, etc.).
 * @tparam F - an effectful program type used to execute the `Emitter`.
 */
case class TraceSystem[F[_]](metadata: Map[String, String], emitter: TraceSystem.Emitter[F]) {
  override def toString: String = s"[metadata=${metadata.mkString(",")}] [emitter=${emitter.description}]"
}

object TraceSystem {
  /**
   * Describes how to emit the current [[Span]] and its metadata (together constituting the [[TraceContext]]) to an external sink
   * (e.g., database, JMX, log file, etc).
   * @tparam F - an effectful program type used to execute the `Emitter`.
   */
  trait Emitter[F[_]] {
    /**
     * Emits the [[Span]] and metadata in the [[TraceContext]] to some external sink using the effectful program `F[A]`
     * @param tc - the [[TraceContext]] containing a cursor into the current span and the system level properties used to annotate
     *   every span when recording.
     * @return emitterProgram - an effectful program that when run will emit the current span in the [[TraceContext]] to some external
     *   sink.
     */
    def emit(tc: TraceContext[F]): F[Unit]

    /**
     * Provides a human readable description of this emitter.
     */
    def description: String
  }

  /* An empty trace system which includes a no-op emitter - used internally by typeclass implementations which need an empty/pure/initial value. */
  private[dtrace] def empty[F[_]](implicit F: Applicative[F]): TraceSystem[F] = TraceSystem[F](
    Map.empty,
    new Emitter[F] {
      override def emit(tc: TraceContext[F]): F[Unit] = F.pure(())
      override val description: String = "Empty Emitter"
    })
}
