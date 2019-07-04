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

import org.scalactic.anyvals.PosZInt
import org.scalatest.prop._

import scala.concurrent.duration._
import scala.language.higherKinds

import CommonGenerators._

trait TraceGenerators {

  def testEmitter[F[_]](implicit F: Sync[F]): F[TraceSystem.Emitter[F]] = F.delay(new TestEmitter[F])

  def genBoolean: Generator[Boolean] = specificValues(true, false)

  def genTsTimer[F[_]: Sync]: Generator[TraceSystem.Timer[F]] =
    specificValues(TraceSystem.monotonicTimer[F], TraceSystem.realTimeTimer[F])

  implicit def genUUID: Generator[UUID] = for {
    hi <- longs
    lo <- longs
  } yield new UUID(hi, lo)

  def genTimeUnit: Generator[TimeUnit] = specificValues(TimeUnit.MILLISECONDS, TimeUnit.NANOSECONDS)

  def genTimeSource: Generator[TraceSystem.Time.Source] = specificValues(
    TraceSystem.Time.Source.Monotonic,
    TraceSystem.Time.Source.RealTime)

  def genTsTime(min: Long, max: Long): Generator[TraceSystem.Time] = for {
    v <- longsBetween(min, max)
    u <- genTimeUnit
    s <- genTimeSource
  } yield TraceSystem.Time(v, u, s)

  def genValidTsTime: Generator[TraceSystem.Time] = genTsTime(Long.MinValue >> 1, Long.MaxValue)
  def genFiniteDuration: Generator[FiniteDuration] = genTsTime(-256L, 256L) map { t => FiniteDuration(t.value, t.unit) }
  def genVectorOfN[A](size: PosZInt, genElement: Generator[A]): Generator[Vector[A]] =
    vectors(genElement).havingSize(size)
  def genVectorOfN[A](minimum: PosZInt, maximum: PosZInt, genElement: Generator[A]): Generator[Vector[A]] =
    vectors(genElement).havingSizesBetween(minimum, maximum)

  def genTraceSystemDataPair: Generator[(String, String)] = for {
    name <- strings
    value <- strings
  } yield name -> value

  def genTraceSystemData: Generator[TraceSystem.Data] = for {
    id <- maps(genTraceSystemDataPair).havingSizesBetween(1, 50)
    meta <- maps(genTraceSystemDataPair).havingSizesBetween(1, 50)
  } yield TraceSystem.Data(TraceSystem.Data.Identity(id), TraceSystem.Data.Meta(meta))

  def genTraceSystem[F[_]: Effect]: Generator[TraceSystem[F]] = for {
    data <- genTraceSystemData
    timer <- genTsTimer[F]
  } yield TraceSystem(data, testEmitter[F].toIO.unsafeRunSync, timer)

  def genSpanId: Generator[SpanId] = for {
    traceId <- genUUID
    parentId <- longs
    childId <- longs
  } yield SpanId(traceId, parentId, childId)

  def genNoteName: Generator[Note.Name] = strings map Note.Name.apply
  def genNoteLongValue: Generator[Note.LongValue] = longs map Note.LongValue.apply
  def genNoteBooleanValue: Generator[Note.BooleanValue] = genBoolean map Note.BooleanValue.apply
  def genNoteStringValue: Generator[Note.StringValue] = strings map Note.StringValue.apply
  def genNoteDoubleValue: Generator[Note.DoubleValue] = doubles map Note.DoubleValue.apply

  def genNote: Generator[Note] = for {
    name <- genNoteName
    value <- Generator.optionGenerator(genNoteValue)
  } yield Note(name, value)

  def genNotes: Generator[Vector[Note]] = genVectorOfN(PosZInt(0), PosZInt(256), genNote)

  def genNoteValue[A]: Generator[Note.Value] = for {
    ln <- genNoteLongValue
    bn <- genNoteBooleanValue
    sn <- genNoteStringValue
    dn <- genNoteDoubleValue
    value <- specificValues(ln, bn, sn, dn)
  } yield value

  def genSpanName: Generator[Span.Name] = strings map Span.Name.apply

  def genExceptionFailureDetail: Generator[FailureDetail.Exception] = strings map { s => FailureDetail.Exception(new IllegalStateException(s)) }

  def genMessageFailureDetail: Generator[FailureDetail.Message] = strings map FailureDetail.Message.apply

  def genFailureDetail: Generator[FailureDetail] = for {
    gefd <- genExceptionFailureDetail
    gmfd <- genMessageFailureDetail
    fd <- specificValues(gefd, gmfd)
  } yield fd

  def genSpan: Generator[Span] = for {
    spanId <- genSpanId
    spanName <- genSpanName
    startTime <- genValidTsTime
    failure <- Generator.optionGenerator(genFailureDetail)
    duration <- genFiniteDuration
    notes <- genNotes
  } yield Span(spanId, spanName, startTime, failure, duration, notes)

  def genTraceContext[F[_]: Effect]: Generator[TraceContext[F]] = for {
    span <- genSpan
    sampled <- genBoolean
    system <- genTraceSystem[F]
  } yield TraceContext(span, sampled, system)
}
