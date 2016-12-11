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

import fs2.util.Async
import fs2.util.syntax._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.io.{ PrintWriter, StringWriter }
import java.util.UUID

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.{ Random, Try }
import scala.util.matching.Regex

sealed abstract class FailureDetail extends Product with Serializable { def render: String }
object FailureDetail {
  case class Message(m: String) extends FailureDetail { override def render: String = m }
  case class Exception(e: Throwable) extends FailureDetail {
    override def render: String = {
      val exceptionMessage = new StringWriter
      e.printStackTrace(new PrintWriter(exceptionMessage))
      exceptionMessage.toString
    }
  }
  def apply(m: String): FailureDetail = Message(m)
  def apply(e: Throwable): FailureDetail = Exception(e)
}

case class TraceSystem(identity: TraceSystem.Identity, emitter: TraceSystem.Emitter) {
  override def toString: String = s"[emitter=$identity] [emitter=${emitter.description}]"
}
object TraceSystem {
  case class Identity(app: Identity.Application, node: Identity.Node, process: Identity.Process, deployment: Identity.Deployment, environment: Identity.Environment) {
    override def toString: String = s"[app=${app.name}] [node=${node.name}] [process=${process.id}] [deployment=${deployment.name}] [environment=${environment.name}]"
  }
  object Identity {
    case class Application(name: String, id: UUID)
    case class Node(name: String, id: UUID)
    case class Process(id: UUID)
    case class Deployment(name: String)
    case class Environment(name: String)
  }
  trait Emitter {
    def emit[F[_]: Async](tc: TraceContext): F[Unit]
    def description: String
  }
  import Identity._
  private[dtrace] val empty: TraceSystem = TraceSystem(
    Identity(Application("", UUID.randomUUID), Node("", UUID.randomUUID), Process(UUID.randomUUID), Deployment(""), Environment("")),
    new Emitter {
      override def emit[F[_]](tc: TraceContext)(implicit F: Async[F]): F[Unit] = F.pure(())
      override val description: String = "Empty Emitter"
    }
  )
}

case class TraceContext(currentSpan: Span, system: TraceSystem) {
  override def toString: String = s"[currentSpan=$currentSpan] [system=$system]"
  private[dtrace] def childSpan[F[_]: Async](spanName: Span.Name): F[TraceContext] =
    currentSpan.newChild(spanName) map { c => copy(currentSpan = c) }
  private[dtrace] def setNotes(notes: Vector[Note]): TraceContext = copy(currentSpan = currentSpan.setNotes(notes))
  private[dtrace] def updateStartTime[F[_]: Async]: F[TraceContext] = currentSpan.updateStartTime map { updated => copy(currentSpan = updated) }
  private[dtrace] def emitSuccess[F[_]: Async]: F[Unit] = finishSuccess flatMap system.emitter.emit[F]
  private[dtrace] def emitFailure[F[_]: Async](detail: FailureDetail): F[Unit] = finishFailure(detail) flatMap system.emitter.emit[F]
  private def finishSuccess[F[_]: Async]: F[TraceContext] = currentSpan.finishSuccess map { ss => copy(currentSpan = ss) }
  private def finishFailure[F[_]: Async](detail: FailureDetail): F[TraceContext] = currentSpan.finishFailure(detail) map { us => copy(currentSpan = us) }
}

object TraceContext { private[dtrace] val empty: TraceContext = TraceContext(Span.empty, TraceSystem.empty) }

case class SpanId(traceId: UUID, parentSpanId: Long, spanId: Long) {
  def root: Boolean = parentSpanId == spanId
  val toHeader: String = s"${SpanId.TraceIdHeader}=$traceId;${SpanId.ParentIdHeader}=$parentSpanId;${SpanId.SpanIdHeader}=$spanId"
  def newChild[F[_]: Async]: F[SpanId] = SpanId.nextSpanIdValue map { newSpanId => copy(parentSpanId = spanId, spanId = newSpanId) }
  override def toString: String = s"SpanId~$traceId~$parentSpanId~$spanId"
}

object SpanId {
  final val HeaderName: String = "X-MoneyTrace"
  final val TraceIdHeader: String = "trace-id"
  final val ParentIdHeader: String = "parent-id"
  final val SpanIdHeader: String = "span-id"
  final val HeaderRegex: Regex = s"$TraceIdHeader=([0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-fA-F]{12});$ParentIdHeader=([\\-0-9]+);$SpanIdHeader=([\\-0-9]+)".r

  def root[F[_]](implicit F: Async[F]): F[SpanId] = for {
    traceId <- F.delay(UUID.randomUUID)
    parentChildId <- nextSpanIdValue
  } yield SpanId(traceId, parentChildId, parentChildId)

  def fromHeader(headerName: String, headerValue: String): Option[SpanId] =
    if (headerName == HeaderName) fromHeaderValue(headerValue) else None

  def fromHeaderValue(headerValue: String): Option[SpanId] = headerValue match {
    case HeaderRegex(traceId, parentId, spanId) => Try(SpanId(UUID.fromString(traceId), parentId.toLong, spanId.toLong)).toOption
    case _ => None
  }

  private[dtrace] def nextSpanIdValue[F[_]](implicit F: Async[F]): F[Long] = F.delay(Random.nextLong)

  private[dtrace] val empty: SpanId = SpanId(UUID.randomUUID, 0L, 0L)
}

case class Note(name: Note.Name, value: Option[Note.Value]) { override def toString: String = s"name=$name,value=${value.fold("")(_.toString)}" }
object Note {
  sealed abstract class Value extends Product with Serializable
  case class LongValue(value: Long) extends Value { override def toString: String = value.toString }
  case class DoubleValue(value: Double) extends Value { override def toString: String = value.toString }
  case class BooleanValue(value: Boolean) extends Value { override def toString: String = value.toString }
  case class StringValue(value: String) extends Value { override def toString: String = value }
  case class Name(value: String) { override def toString: String = value }
  def long(name: String, value: Long): Note = long(name, Some(value))
  def double(name: String, value: Double): Note = double(name, Some(value))
  def boolean(name: String, value: Boolean): Note = boolean(name, Some(value))
  def string(name: String, value: String): Note = string(name, Some(value))
  def long(name: String): Note = long(name, Option.empty[Long])
  def double(name: String): Note = double(name, Option.empty[Double])
  def boolean(name: String): Note = boolean(name, Option.empty[Boolean])
  def string(name: String): Note = string(name, Option.empty[String])
  def long(name: String, value: Option[Long]): Note = Note(Name(name), value map LongValue.apply)
  def double(name: String, value: Option[Double]): Note = Note(Name(name), value map DoubleValue.apply)
  def boolean(name: String, value: Option[Boolean]): Note = Note(Name(name), value map BooleanValue.apply)
  def string(name: String, value: Option[String]): Note = Note(Name(name), value map StringValue.apply)
}

case class Span(
    spanId: SpanId,
    spanName: Span.Name,
    startTime: Instant,
    failure: Option[FailureDetail],
    duration: FiniteDuration,
    notes: Vector[Note]
) {
  def root: Boolean = spanId.root
  private[dtrace] def newChild[F[_]](spanName: Span.Name)(implicit F: Async[F]): F[Span] = for {
    startTime <- Span.nowInstant
    child <- spanId.newChild
  } yield Span(child, spanName, startTime, None, Duration.Zero, Vector.empty)
  private[dtrace] def setNotes(notes: Vector[Note]): Span = copy(notes = notes)
  private[dtrace] def updateStartTime[F[_]](implicit F: Async[F]): F[Span] = Span.nowInstant map { t => copy(startTime = t) }
  private[dtrace] def finishSuccess[F[_]: Async]: F[Span] = finish(None)
  private[dtrace] def finishFailure[F[_]: Async](detail: FailureDetail): F[Span] = finish(Some(detail))
  override def toString: String =
    s"[span-id=$spanId] [span-name=$spanName] [start-time=$startTime] [span-success=${failure.isEmpty}] [failure-detail=${failure.fold("N/A")(_.render)}] [span-duration=$duration] [notes=[${notes.mkString("] [")}]"
  private def finish[F[_]](failure: Option[FailureDetail])(implicit F: Async[F]): F[Span] =
    Span.nowInstant map { endTime => copy(failure = failure, duration = FiniteDuration(ChronoUnit.MICROS.between(startTime, endTime), MICROSECONDS)) }
}

object Span {
  case class Name(value: String) { override def toString: String = value }

  def root[F[_]: Async](spanName: Name, notes: Note*): F[Span] = SpanId.root flatMap { newSpan(_, spanName, notes: _*) }

  def newChild[F[_]: Async](spanId: SpanId, spanName: Name, notes: Note*): F[Span] = spanId.newChild flatMap { newSpan(_, spanName, notes: _*) }

  private[dtrace] val empty: Span = Span(SpanId.empty, Span.Name("empty"), Instant.EPOCH, None, 0.seconds, Vector.empty)

  private def nowInstant[F[_]](implicit F: Async[F]): F[Instant] = F.delay(Instant.now)

  private def newSpan[F[_]](spanId: SpanId, spanName: Span.Name, notes: Note*)(implicit F: Async[F]): F[Span] =
    nowInstant map { Span(spanId, spanName, _, None, Duration.Zero, notes.toVector) }
}
