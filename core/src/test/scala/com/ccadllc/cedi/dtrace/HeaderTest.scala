package com.ccadllc.cedi.dtrace

import org.scalatest.{ Matchers, WordSpec }

class HeaderTest extends WordSpec with Matchers with TestData {
  "the X-MoneyTrace format header" should {
    "be parsed correctly as a SpanId" in {
      val headerParseResult =
        SpanId.fromHeader(SpanId.HeaderName, xMoneyTraceHeader).right.toOption

      headerParseResult.map(_.toHeader) should contain(xMoneyTraceHeader)
    }
  }
}
