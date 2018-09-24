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

import cats._
import cats.effect.{ Clock, Sync }
import cats.implicits._

import java.util.concurrent.TimeUnit

import scala.language.higherKinds
import scala.util.matching.Regex

/**
 * System level configuration for the trace system.
 *
 * @param metadata - top-level metadata that should be included in all trace span recordings (examples include:
 *   node, deployment, and environment information).
 * @param emitter - [[TraceSystem#Emitter]] responsible for actually recording the span information for a distributed trace
 *   to some external sink (e.g., log file, remote database, JMX, etc.).
 * @param timer - [[TraceSystem#Timer]] responsible for generating timer to measure performance of [[Span]]s.
 * @tparam F - an effectful program type used to execute the `Emitter` and `Timer`.
 */
case class TraceSystem[F[_]](metadata: Map[String, String], emitter: TraceSystem.Emitter[F], timer: TraceSystem.Timer[F]) {
  override def toString: String = s"[metadata=${metadata.mkString(",")}] [emitter=${emitter.description}] [timer=${timer.description}]"
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

  /**
   * Represents a point of time.  Might be real-time or monotonic.  The [[Timer]] generates these
   * based on the associated `cats.effect.Clock`.  For a [[Timer#Monotonic]], the `cats.effect.Clock#monotonic`
   * will be used; otherwise, if it is a [[Timer#RealTime]], the `cats.effect.Clock#realTime` will be used.
   */
  final case class Time(value: Long, unit: TimeUnit, source: Time.Source) {
    override val toString: String = s"$value ${unit.toString.toLowerCase} (${source.toString.toLowerCase})"
  }
  object Time {
    sealed abstract class Source extends Product with Serializable
    object Source {
      case object Monotonic extends Source
      case object RealTime extends Source
      def fromString(str: String): Either[FailureDetail, Source] = if (str === Monotonic.toString) Right(Monotonic)
      else if (str === RealTime.toString) Right(RealTime) else Left(FailureDetail.Message(s"$str was neither $Monotonic nor $RealTime"))
    }
    final val TimeRegex: Regex = "([0-9]+) ([a-z]+) (\\([a-z]+\\))".r

    implicit val show: Show[Time] = Show.fromToString

    def parse(str: String): Either[FailureDetail, Time] = str match {
      case TimeRegex(valueStr, unitStr, sourceStr) => for {
        value <- Either.catchNonFatal { valueStr.toLong }.leftMap {
          _ => FailureDetail.Message(s"Could not parse $valueStr as a Long from $str for Time")
        }
        unit <- Either.catchNonFatal { TimeUnit.valueOf(unitStr.toUpperCase) }.leftMap {
          e => FailureDetail.Message(s"Could not parse $unitStr as a TimeUnit from $str for Time (${e.getMessage})")
        }
        source <- Source.fromString(sourceStr.capitalize).leftMap {
          fd => FailureDetail.Message(s"Could not parse $sourceStr as a Time.Source from $str for Time (${fd.render})")
        }
      } yield Time(value, unit, source)
      case other => Left(FailureDetail.Message(s"Could not parse $str to a Time value"))
    }
  }

  /**
   * This ADT represents a time generator using the associated `cats.effect.Clock`, using the asssociated
   * `java.util.concurrent.TimeUnit`. The valid sub-types are [[Timer#Monotonic]] or [[Timer#RealTime]]
   * where the `cats.effect.Clock#monotonic` or `cats.effect.Clock#realTime` functions will be used to
   * generate a point in time, used in determining the execution time of a [[Span]].
   */
  sealed abstract class Timer[F[_]] extends Product with Serializable {
    def clock: Clock[F]
    def unit: TimeUnit
    def time(implicit F: Functor[F]): F[Time]
    def translate[G[_]](trans: F ~> G): Timer[G]
    def description: String
  }
  object Timer {
    final case class Monotonic[F[_]](clock: Clock[F], unit: TimeUnit = TimeUnit.NANOSECONDS) extends Timer[F] {
      val description: String = s"A monotonic generator for time in units of $unit"
      def time(implicit F: Functor[F]): F[Time] = clock.monotonic(unit).map(Time(_, unit, Time.Source.Monotonic))
      def translate[G[_]](trans: F ~> G): Timer[G] = Monotonic(transClock(clock, trans), unit)
    }
    final case class RealTime[F[_]](clock: Clock[F], unit: TimeUnit = TimeUnit.MICROSECONDS) extends Timer[F] {
      val description: String = s"A real-time generator for time in units of $unit"
      def time(implicit F: Functor[F]): F[Time] = clock.realTime(unit).map(Time(_, unit, Time.Source.RealTime))
      def translate[G[_]](trans: F ~> G): Timer[G] = Monotonic(transClock(clock, trans), unit)
    }
    def monotonic[F[_]: Sync](unit: TimeUnit): Timer[F] = Monotonic(Clock.create[F], unit)
    def realTime[F[_]: Sync](unit: TimeUnit): Timer[F] = RealTime(Clock.create[F], unit)

    private def transClock[F[_], G[_]](clock: Clock[F], trans: F ~> G): Clock[G] = new Clock[G] {
      def realTime(unit: TimeUnit): G[Long] = trans(clock.realTime(unit))
      def monotonic(unit: TimeUnit): G[Long] = trans(clock.monotonic(unit))
    }
  }
  def monotonicTimer[F[_]: Sync]: Timer[F] = Timer.monotonic[F](TimeUnit.NANOSECONDS)
  def realTimeTimer[F[_]: Sync]: Timer[F] = Timer.realTime[F](TimeUnit.MICROSECONDS)
}
