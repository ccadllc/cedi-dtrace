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

import java.util.UUID

import scala.language.higherKinds

/**
 * System level configuration for the trace system.
 *
 * @param identity - the system-level environment (node, deployment, program identities)
 *   information rendered as metadata during the recording of a span.
 * @param emitter - responsible for actually recording the span information for a distributed trace
 *   to some external sink (e.g., log file, remote database, JMX, etc.).
 */
case class TraceSystem[F[_]](identity: TraceSystem.Identity, emitter: TraceSystem.Emitter[F]) {
  override def toString: String = s"[emitter=$identity] [emitter=${emitter.description}]"
}

/**
 * Companion object which provides the `Identity` object for system-level metadata and the trait which
 * describes the behavior of a trace `Emitter`.
 */
object TraceSystem {

  /**
   * Provides system level identification information, used as metadata when recording trace spans.
   * @param app - identifies the application being traced.
   * @param node - identifies the node on the network where this instance of the traced application is running.
   * @param process - identifies the process on the node where this instance of the traced application is running.
   * @param deployment - identifies the deployment (e.g., ashburn-east-1) where this instance of the traced application is running.
   * @param environment - identifies the environment (e.g., test, production) where this instance of the traced application is running.
   */
  case class Identity(app: Identity.Application, node: Identity.Node, process: Identity.Process, deployment: Identity.Deployment, environment: Identity.Environment) {
    override def toString: String = s"[app=${app.name}] [node=${node.name}] [process=${process.id}] [deployment=${deployment.name}] [environment=${environment.name}]"
  }

  object Identity {
    final case class Application(name: String, id: UUID)
    final case class Node(name: String, id: UUID)
    final case class Process(id: UUID)
    final case class Deployment(name: String)
    final case class Environment(name: String)
  }

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

  private[dtrace] def empty[F[_]](implicit F: Applicative[F]): TraceSystem[F] = {
    import Identity._
    TraceSystem[F](
      Identity(Application("", UUID.randomUUID), Node("", UUID.randomUUID), Process(UUID.randomUUID), Deployment(""), Environment("")),
      new Emitter[F] {
        override def emit(tc: TraceContext[F]): F[Unit] = F.pure(())
        override val description: String = "Empty Emitter"
      }
    )
  }
}
