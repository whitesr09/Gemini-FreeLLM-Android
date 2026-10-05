package com.nshd.geminifreellm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolRegistryTest {
    @Test fun calculatorIsSafeAndCorrect() {
        val result = LocalToolRegistry.execute("calculator", """{"expression":"(2+3)*4"}""").result
        assertEquals("20.0", result)
        val rejected = LocalToolRegistry.execute("calculator", """{"expression":"java.lang.Runtime.getRuntime()"}""").result
        assertTrue(rejected.contains("Only basic arithmetic"))
    }

    @Test fun convertsLengthAndTemperature() {
        assertEquals("1.0", LocalToolRegistry.execute("unit_conversion", """{"value":100,"from":"cm","to":"m"}""").result)
        assertEquals("212.0", LocalToolRegistry.execute("unit_conversion", """{"value":100,"from":"c","to":"f"}""").result)
    }

    @Test fun unknownToolDoesNotExecuteCommands() {
        assertTrue(LocalToolRegistry.execute("shell", """{"command":"rm -rf /"}""").result.contains("Unsupported"))
    }
}
