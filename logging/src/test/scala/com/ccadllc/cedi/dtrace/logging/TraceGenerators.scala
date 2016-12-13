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

import scala.language.higherKinds
import scala.concurrent.duration._

import java.time.Instant
import java.util.UUID

import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary

import fs2.util.Suspendable

trait TraceGenerators {

  def genOption[A](g: Gen[A]): Gen[Option[A]] = Gen.oneOf(Gen.const(Option.empty[A]), g map Option.apply)

  def genUUID: Gen[UUID] = for {
    hi <- arbitrary[Long]
    lo <- arbitrary[Long]
  } yield new UUID(hi, lo)

  def genInstant(minMillis: Long, maxMillis: Long): Gen[Instant] =
    Gen.chooseNum(minMillis, maxMillis) map { ms => Instant.ofEpochMilli(ms) }
  def genValidInstant: Gen[Instant] = genInstant(Long.MinValue >> 1, Long.MaxValue)
  def genFiniteDuration: Gen[FiniteDuration] = genInstant(-256L, 256L) map { i => FiniteDuration(i.toEpochMilli * 1000L, MICROSECONDS) }
  def genVectorOfN[A](size: Int, genElement: Gen[A]): Gen[Vector[A]] = Gen.listOfN(size, genElement) map { _.toVector }
  def genVectorOfN[A](minimum: Int, maximum: Int, genElement: Gen[A]): Gen[Vector[A]] = Gen.chooseNum(minimum, maximum) flatMap { genVectorOfN(_, genElement) }
  def genStr: Gen[String] = Gen.listOf(Gen.alphaNumChar) map { _.mkString }

  def genIdentityApplication: Gen[TraceSystem.Identity.Application] = for {
    name <- genStr
    id <- genUUID
  } yield TraceSystem.Identity.Application(name, id)

  def genIdentityNode: Gen[TraceSystem.Identity.Node] = for {
    name <- genStr
    id <- genUUID
  } yield TraceSystem.Identity.Node(name, id)

  def genIdentityProcess: Gen[TraceSystem.Identity.Process] = genUUID map TraceSystem.Identity.Process.apply

  def genIdentityDeployment: Gen[TraceSystem.Identity.Deployment] = genStr map TraceSystem.Identity.Deployment.apply

  def genIdentityEnvironment: Gen[TraceSystem.Identity.Environment] = genStr map TraceSystem.Identity.Environment.apply

  def genTraceSystemIdentity: Gen[TraceSystem.Identity] = for {
    app <- genIdentityApplication
    node <- genIdentityNode
    process <- genIdentityProcess
    deployment <- genIdentityDeployment
    environment <- genIdentityEnvironment
  } yield TraceSystem.Identity(app, node, process, deployment, environment)

  def genTraceSystem[F[_]: Suspendable]: Gen[TraceSystem[F]] = genTraceSystemIdentity map { TraceSystem(_, new LogEmitter[F]) }

  def genSpanId: Gen[SpanId] = for {
    traceId <- genUUID
    parentId <- arbitrary[Long]
    childId <- arbitrary[Long]
  } yield SpanId(traceId, parentId, childId)

  def genNoteName: Gen[Note.Name] = genStr map Note.Name.apply
  def genNoteLongValue: Gen[Note.LongValue] = arbitrary[Long] map Note.LongValue.apply
  def genNoteBooleanValue: Gen[Note.BooleanValue] = arbitrary[Boolean] map Note.BooleanValue.apply
  def genNoteStringValue: Gen[Note.StringValue] = genStr map Note.StringValue.apply
  def genNoteDoubleValue: Gen[Note.DoubleValue] = arbitrary[Double] map Note.DoubleValue.apply

  def genNote: Gen[Note] = for {
    name <- genNoteName
    value <- genOption(Gen.oneOf(genNoteLongValue, genNoteBooleanValue, genNoteStringValue, genNoteDoubleValue))
  } yield Note(name, value)

  def genNotes: Gen[Vector[Note]] = genVectorOfN(0, 256, genNote)

  def genSpanName: Gen[Span.Name] = genStr map Span.Name.apply

  def genExceptionFailureDetail: Gen[FailureDetail.Exception] = genStr map { s => FailureDetail.Exception(new IllegalStateException(s)) }

  def genMessageFailureDetail: Gen[FailureDetail.Message] = genStr map FailureDetail.Message.apply

  def genFailureDetail: Gen[FailureDetail] = Gen.oneOf(genExceptionFailureDetail, genMessageFailureDetail)

  def genSpan: Gen[Span] = for {
    spanId <- genSpanId
    spanName <- genSpanName
    startTime <- genValidInstant
    failure <- genOption(genFailureDetail)
    duration <- genFiniteDuration
    notes <- genNotes
  } yield Span(spanId, spanName, startTime, failure, duration, notes)

  def genTraceContext[F[_]: Suspendable]: Gen[TraceContext[F]] = for {
    span <- genSpan
    system <- genTraceSystem[F]
  } yield TraceContext(span, system)
}
