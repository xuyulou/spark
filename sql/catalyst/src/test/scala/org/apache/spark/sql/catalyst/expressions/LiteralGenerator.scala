/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions

import java.sql.{Date, Timestamp}
import java.time.{Duration, Instant, LocalDate}
import java.util.concurrent.TimeUnit

import org.scalacheck.{Arbitrary, Gen}

import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.CalendarInterval

/**
 * Property is a high-level specification of behavior that should hold for a range of data points.
 *
 * For example, while we are evaluating a deterministic expression for some input, we should always
 * hold the property that the result never changes, regardless of how we get the result,
 * via interpreted or codegen.
 *
 * In ScalaTest, properties are specified as functions and the data points used to check properties
 * can be supplied by either tables or generators.
 *
 * Generator-driven property checks are performed via integration with ScalaCheck.
 *
 * @example {{{
 *   def toTest(i: Int): Boolean = if (i % 2 == 0) true else false
 *
 *   import org.scalacheck.Gen
 *
 *   test ("true if param is even") {
 *     val evenInts = for (n <- Gen.choose(-1000, 1000)) yield 2 * n
 *     forAll(evenInts) { (i: Int) =>
 *       assert (toTest(i) === true)
 *     }
 *   }
 * }}}
 *
 */
object LiteralGenerator {

  lazy val byteLiteralGen: Gen[Literal] =
    for { b <- Arbitrary.arbByte.arbitrary } yield Literal.create(b, ByteType)

  lazy val shortLiteralGen: Gen[Literal] =
    for { s <- Arbitrary.arbShort.arbitrary } yield Literal.create(s, ShortType)

  lazy val integerLiteralGen: Gen[Literal] =
    for { i <- Arbitrary.arbInt.arbitrary } yield Literal.create(i, IntegerType)

  lazy val longLiteralGen: Gen[Literal] =
    for { l <- Arbitrary.arbLong.arbitrary } yield Literal.create(l, LongType)

  lazy val floatLiteralGen: Gen[Literal] =
    for {
      f <- Gen.chooseNum(Float.MinValue / 2, Float.MaxValue / 2,
        Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity)
    } yield Literal.create(f, FloatType)

  lazy val doubleLiteralGen: Gen[Literal] =
    for {
      f <- Gen.chooseNum(Double.MinValue / 2, Double.MaxValue / 2,
        Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity)
    } yield Literal.create(f, DoubleType)

  // TODO cache the generated data
  def decimalLiteralGen(precision: Int, scale: Int): Gen[Literal] = {
    assert(scale >= 0)
    assert(precision >= scale)
    Arbitrary.arbBigInt.arbitrary.map { s =>
      val a = (s % BigInt(10).pow(precision - scale)).toString()
      val b = (s % BigInt(10).pow(scale)).abs.toString()
      Literal.create(
        Decimal(BigDecimal(s"$a.$b"), precision, scale),
        DecimalType(precision, scale))
    }
  }

  lazy val stringLiteralGen: Gen[Literal] =
    for { s <- Arbitrary.arbString.arbitrary } yield Literal.create(s, StringType)

  lazy val binaryLiteralGen: Gen[Literal] =
    for { ab <- Gen.listOf[Byte](Arbitrary.arbByte.arbitrary) }
      yield Literal.create(ab.toArray, BinaryType)

  lazy val booleanLiteralGen: Gen[Literal] =
    for { b <- Arbitrary.arbBool.arbitrary } yield Literal.create(b, BooleanType)

  lazy val dateLiteralGen: Gen[Literal] = {
    // Valid range for DateType is [0001-01-01, 9999-12-31]
    val minDay = LocalDate.of(1, 1, 1).toEpochDay
    val maxDay = LocalDate.of(9999, 12, 31).toEpochDay
    for { day <- Gen.choose(minDay, maxDay) }
      yield Literal.create(new Date(day * DateTimeUtils.MILLIS_PER_DAY), DateType)
  }

  lazy val timestampLiteralGen: Gen[Literal] = {
    // Catalyst's Timestamp type stores number of microseconds since epoch in
    // a variable of Long type. To prevent arithmetic overflow of Long on
    // conversion from milliseconds to microseconds, the range of random milliseconds
    // since epoch is restricted here.
    // Valid range for TimestampType is [0001-01-01T00:00:00.000000Z, 9999-12-31T23:59:59.999999Z]
    val minMillis = Instant.parse("0001-01-01T00:00:00.000000Z").toEpochMilli
    val maxMillis = Instant.parse("9999-12-31T23:59:59.999999Z").toEpochMilli
    for { millis <- Gen.choose(minMillis, maxMillis) }
      yield Literal.create(new Timestamp(millis), TimestampType)
  }

  // Valid range for DateType and TimestampType is [0001-01-01, 9999-12-31]
  private val maxIntervalInMonths: Int = 10000 * 12

  lazy val monthIntervalLiterGen: Gen[Literal] = {
    for { months <- Gen.choose(-1 * maxIntervalInMonths, maxIntervalInMonths) }
      yield Literal.create(months, IntegerType)
  }

  lazy val calendarIntervalLiterGen: Gen[Literal] = {
    val maxDurationInSec = Duration.between(
      Instant.parse("0001-01-01T00:00:00.000000Z"),
      Instant.parse("9999-12-31T23:59:59.999999Z")).getSeconds
    val maxMicros = TimeUnit.SECONDS.toMicros(maxDurationInSec)
    val maxDays = TimeUnit.SECONDS.toDays(maxDurationInSec).toInt
    for {
      months <- Gen.choose(-1 * maxIntervalInMonths, maxIntervalInMonths)
      micros <- Gen.choose(-1 * maxMicros, maxMicros)
      days <- Gen.choose(-1 * maxDays, maxDays)
    } yield Literal.create(new CalendarInterval(months, days, micros), CalendarIntervalType)
  }


  // Sometimes, it would be quite expensive when unlimited value is used,
  // for example, the `times` arguments for StringRepeat would hang the test 'forever'
  // if it's tested against Int.MaxValue by ScalaCheck, therefore, use values from a limited
  // range is more reasonable
  lazy val limitedIntegerLiteralGen: Gen[Literal] =
    for { i <- Gen.choose(-100, 100) } yield Literal.create(i, IntegerType)

  def randomGen(dt: DataType): Gen[Literal] = {
    dt match {
      case ByteType => byteLiteralGen
      case ShortType => shortLiteralGen
      case IntegerType => integerLiteralGen
      case LongType => longLiteralGen
      case DoubleType => doubleLiteralGen
      case FloatType => floatLiteralGen
      case DateType => dateLiteralGen
      case TimestampType => timestampLiteralGen
      case BooleanType => booleanLiteralGen
      case StringType => stringLiteralGen
      case BinaryType => binaryLiteralGen
      case CalendarIntervalType => calendarIntervalLiterGen
      case DecimalType.Fixed(precision, scale) => decimalLiteralGen(precision, scale)
      case dt => throw new IllegalArgumentException(s"not supported type $dt")
    }
  }
}
