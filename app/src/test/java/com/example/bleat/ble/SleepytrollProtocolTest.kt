package com.example.bleat.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frames come from docs/SLEEPYTROLL_PROTOCOL.md (sniffed session). */
class SleepytrollProtocolTest {

  // ---- channel 2 -----------------------------------------------------------

  @Test fun `channel 2 sample frame decodes`() {
    val t = SleepytrollProtocol.parseStatus("5d01640203012e1a9dd0")!!
    assertEquals(93, t.batteryPct)
    assertTrue(t.running)
    assertFalse(t.standby)
    assertEquals(100, t.speed)
    assertEquals(2, t.soundSensitivity)
    assertEquals(3, t.movementSensitivity)
    assertEquals(1 * 3600 + 46 * 60 + 26, t.timerSeconds)
    assertEquals("01:46:26", t.timerText)
    assertFalse(t.lowBattery)
  }

  @Test fun `state byte 3 is standby`() {
    val t = SleepytrollProtocol.parseStatus("5d03640203000000")!!
    assertFalse(t.running)
    assertTrue(t.standby)
  }

  @Test fun `sensitivities clamp to 4 and low battery is below 6`() {
    val t = SleepytrollProtocol.parseStatus("0501640909000000")!!
    assertEquals(4, t.soundSensitivity)
    assertEquals(4, t.movementSensitivity)
    assertTrue(t.lowBattery)
  }

  @Test fun `short or non-hex channel 2 body is rejected`() {
    assertNull(SleepytrollProtocol.parseStatus("5d0164"))
    assertNull(SleepytrollProtocol.parseStatus("zz01640203012e1a"))
  }

  // ---- channel 1 -----------------------------------------------------------

  @Test fun `identity splits serial and version`() {
    val d = SleepytrollProtocol.parseIdentity("586949573-28360.0.94ffa", DeviceInfo(mode = 2))
    assertEquals("586949573", d.serial)
    assertEquals("28360.0.94ffa", d.version)
    assertEquals(2, d.mode) // untouched fields survive
  }

  @Test fun `identity without a dash has no version`() {
    val d = SleepytrollProtocol.parseIdentity("ABC", DeviceInfo())
    assertEquals("ABC", d.serial)
    assertNull(d.version)
  }

  // ---- channel 3 -----------------------------------------------------------

  @Test fun `counters decode mode cycles total and motor minutes`() {
    //            mode  --   --   cycles total  h   m
    val body = "02" + "00" + "00" + "001b" + "0154" + "00" + "0c"
    val d = SleepytrollProtocol.parseCounters(body, DeviceInfo(serial = "S"))
    assertEquals(2, d.mode)
    assertEquals(27, d.batteryCycles)
    assertEquals(340, d.deviceTotalMin)
    assertEquals(12, d.motorMinutes)
    assertEquals("S", d.serial)
  }

  @Test fun `motor hours are folded into minutes`() {
    val d = SleepytrollProtocol.parseCounters("020000001b0154020a", DeviceInfo())
    assertEquals(2 * 60 + 10, d.motorMinutes)
  }

  @Test fun `14-char counters body has no motor minutes`() {
    val d = SleepytrollProtocol.parseCounters("020000001b0154", DeviceInfo())
    assertEquals(27, d.batteryCycles)
    assertNull(d.motorMinutes)
  }

  @Test fun `too-short counters body leaves previous info unchanged`() {
    val prev = DeviceInfo(motorMinutes = 99)
    assertSame(prev, SleepytrollProtocol.parseCounters("0200", prev))
  }

  // ---- channel 4 -----------------------------------------------------------

  @Test fun `channel 4 routing`() {
    assertEquals(Channel4.Ack("OK"), SleepytrollProtocol.classifyChannel4("OK"))
    assertEquals(Channel4.WillDisconnect, SleepytrollProtocol.classifyChannel4("0000ab"))
    assertEquals(Channel4.WillDisconnect, SleepytrollProtocol.classifyChannel4("000001")) // zero check wins
    assertEquals(Channel4.MotorWarning, SleepytrollProtocol.classifyChannel4("123401"))
    assertEquals(Channel4.Other("12"), SleepytrollProtocol.classifyChannel4("12"))
    assertEquals(Channel4.Other("123400"), SleepytrollProtocol.classifyChannel4("123400"))
  }

  // ---- line framing --------------------------------------------------------

  @Test fun `channel prefix is split off and trimmed`() {
    assertEquals('2' to "abcd", SleepytrollProtocol.splitChannel("2, abcd "))
    assertNull(SleepytrollProtocol.splitChannel("OK"))
    assertNull(SleepytrollProtocol.splitChannel(",x"))
  }

  @Test fun `assembler returns every complete line in a packet`() {
    val a = LineAssembler()
    assertEquals(listOf("2,aa", "4,OK"), a.feed("2,aa\r\n4,OK\r\n"))
  }

  @Test fun `assembler joins a line split across two packets`() {
    val a = LineAssembler()
    assertEquals(emptyList<String>(), a.feed("2,5d0164"))
    assertEquals(listOf("2,5d01640203012e1a9dd0"), a.feed("0203012e1a9dd0\r\n"))
  }

  @Test fun `assembler keeps a trailing partial line for the next packet`() {
    val a = LineAssembler()
    assertEquals(listOf("4,OK"), a.feed("4,OK\r\n3,par"))
    assertEquals(listOf("3,partial"), a.feed("tial\r\n"))
  }

  @Test fun `assembler handles CR and LF arriving in different packets`() {
    val a = LineAssembler()
    assertEquals(emptyList<String>(), a.feed("4,OK\r"))
    assertEquals(listOf("4,OK"), a.feed("\n"))
  }

  @Test fun `assembler drops blank lines`() {
    assertEquals(listOf("4,OK"), LineAssembler().feed("\r\n\r\n4,OK\r\n"))
  }

  @Test fun `assembler discards unterminated garbage past the cap`() {
    val a = LineAssembler(maxBuffer = 16)
    assertEquals(emptyList<String>(), a.feed("x".repeat(20)))
    assertEquals(listOf("4,OK"), a.feed("4,OK\r\n")) // garbage did not prefix the next line
  }

  @Test fun `assembler reset clears buffered partial`() {
    val a = LineAssembler()
    a.feed("2,partial")
    a.reset()
    assertEquals(listOf("4,OK"), a.feed("4,OK\r\n"))
  }

  @Test fun `assembler decodes bytes as ASCII`() {
    assertEquals(listOf("4,OK"), LineAssembler().feed("4,OK\r\n".toByteArray(Charsets.US_ASCII)))
  }
}
