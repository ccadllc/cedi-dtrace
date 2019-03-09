/*
 * Copyright 2018 Combined Conditional Access Development, LLC.
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

import cats.effect.{ Effect, Sync }
import cats.effect.implicits._

import java.util.UUID
import java.util.concurrent.TimeUnit

import org.scalacheck.{ Arbitrary, Gen }

import scala.concurrent.duration._
import scala.language.higherKinds

import Arbitrary.arbitrary

trait TraceGenerators {

  def testEmitter[F[_]](implicit F: Sync[F]): F[TraceSystem.Emitter[F]] = F.delay(new TestEmitter[F])

  def genTsTimer[F[_]: Sync]: Gen[TraceSystem.Timer[F]] =
    Gen.oneOf(Gen.const(TraceSystem.monotonicTimer[F]), Gen.const(TraceSystem.realTimeTimer[F]))

  def genOption[A](g: Gen[A]): Gen[Option[A]] = Gen.oneOf(Gen.const(Option.empty[A]), g map Option.apply)

  def genUUID: Gen[UUID] = for {
    hi <- arbitrary[Long]
    lo <- arbitrary[Long]
  } yield new UUID(hi, lo)

  def genTimeUnit: Gen[TimeUnit] = Gen.oneOf(Gen.const(TimeUnit.MILLISECONDS), Gen.const(TimeUnit.NANOSECONDS))

  def genTimeSource: Gen[TraceSystem.Time.Source] = Gen.oneOf(
    Gen.const(TraceSystem.Time.Source.Monotonic),
    Gen.const(TraceSystem.Time.Source.RealTime))

  def genTsTime(min: Long, max: Long): Gen[TraceSystem.Time] = for {
    v <- Gen.chooseNum(min, max)
    u <- genTimeUnit
    s <- genTimeSource
  } yield TraceSystem.Time(v, u, s)

  def genValidTsTime: Gen[TraceSystem.Time] = genTsTime(Long.MinValue >> 1, Long.MaxValue)
  def genFiniteDuration: Gen[FiniteDuration] = genTsTime(-256L, 256L) map { t => FiniteDuration(t.value, t.unit) }
  def genVectorOfN[A](size: Int, genElement: Gen[A]): Gen[Vector[A]] = Gen.listOfN(size, genElement) map { _.toVector }
  def genVectorOfN[A](minimum: Int, maximum: Int, genElement: Gen[A]): Gen[Vector[A]] = Gen.chooseNum(minimum, maximum) flatMap { genVectorOfN(_, genElement) }
  def genStr: Gen[String] = Gen.listOf(Gen.alphaNumChar) map { _.mkString }

  def genTraceSystemDataPair: Gen[(String, String)] = for {
    name <- genStr
    value <- genStr
  } yield name -> value

  def genTraceSystemData: Gen[TraceSystem.Data] = for {
    id <- Gen.nonEmptyMap(genTraceSystemDataPair)
    meta <- Gen.nonEmptyMap(genTraceSystemDataPair)
  } yield TraceSystem.Data(TraceSystem.Data.Identity(id), TraceSystem.Data.Meta(meta))

  def genTraceSystem[F[_]: Effect]: Gen[TraceSystem[F]] = for {
    data <- genTraceSystemData
    timer <- genTsTimer[F]
  } yield TraceSystem(data, testEmitter[F].toIO.unsafeRunSync, timer)

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
    startTime <- genValidTsTime
    failure <- genOption(genFailureDetail)
    duration <- genFiniteDuration
    notes <- genNotes
  } yield Span(spanId, spanName, startTime, failure, duration, notes)

  def genTraceContext[F[_]: Effect]: Gen[TraceContext[F]] = for {
    span <- genSpan
    system <- genTraceSystem[F]
  } yield TraceContext(span, system)
}
