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

/**
 * This data type represents metadata used to annotate a distributed trace
 * [[Span]].  Examples of `Note`s might be the input parameters to the program being executed
 * as part of the span, or it might be values derived from the result of that program.
 * @param name - the human readable name of the `Note`.
 * @param value - the possible value of the `Note` (or None if a note has no value when the [[Span]] is recorded.
 */
case class Note(name: Note.Name, value: Option[Note.Value]) {
  override def toString: String = s"name=$name,value=${value.fold("")(_.toString)}"
}

/**
 * The companion to the `Note` instances defines the `Note.Name` data type and the
 * associated `Note.Value` ADT, as well as providing convenience constructors for the different
 * value types.
 */
object Note {

  /**
   * This ADT represents a `Note` value - it has enumerated types to represent the primitive values of `Long`, `Double`,
   * `Boolean` and `String`.
   */
  sealed abstract class Value extends Product with Serializable
  final case class LongValue(value: Long) extends Value { override def toString: String = value.toString }
  final case class DoubleValue(value: Double) extends Value { override def toString: String = value.toString }
  final case class BooleanValue(value: Boolean) extends Value { override def toString: String = value.toString }
  final case class StringValue(value: String) extends Value { override def toString: String = value }
  final case class Name(value: String) { override def toString: String = value }

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
