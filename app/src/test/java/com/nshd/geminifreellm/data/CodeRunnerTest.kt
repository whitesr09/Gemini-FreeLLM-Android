package com.nshd.geminifreellm.data

import org.junit.Assert.*
import org.junit.Test

class CodeRunnerTest {
    @Test fun consoleLogIsCaptured() {
        val result = CodeRunner.runJs("console.log('hello'); console.log('a', 'b');")
        assertTrue(result is JsRunResult.Success)
        assertEquals("hello\na b", (result as JsRunResult.Success).output)
    }

    @Test fun returnValueIsReported() {
        val result = CodeRunner.runJs("const x = 21 * 2; x;")
        assertTrue(result is JsRunResult.Success)
        assertEquals("42", (result as JsRunResult.Success).returned)
    }

    @Test fun printAliasWorks() {
        val result = CodeRunner.runJs("print('via print');")
        assertEquals("via print", (result as JsRunResult.Success).output)
    }

    @Test fun syntaxErrorReportsLine() {
        val result = CodeRunner.runJs("const a = 1;\nconst b = ;\n")
        assertTrue(result is JsRunResult.Failure)
        assertEquals(2, (result as JsRunResult.Failure).line)
    }

    @Test fun runtimeErrorIsFailure() {
        val result = CodeRunner.runJs("null.foo();")
        assertTrue(result is JsRunResult.Failure)
        assertTrue((result as JsRunResult.Failure).message.isNotBlank())
    }

    @Test fun infiniteLoopTimesOut() {
        val start = System.currentTimeMillis()
        val result = CodeRunner.runJs("while (true) {}")
        assertTrue(result is JsRunResult.Failure)
        assertTrue((result as JsRunResult.Failure).message.contains("Timed out"))
        assertTrue(System.currentTimeMillis() - start < CodeRunner.TIMEOUT_MS + 5000)
    }

    @Test fun languageDetection() {
        assertTrue(CodeRunner.isRunnable("js"))
        assertTrue(CodeRunner.isRunnable("JavaScript"))
        assertFalse(CodeRunner.isRunnable("python"))
        assertTrue(CodeRunner.isPreviewable("html"))
        assertFalse(CodeRunner.isPreviewable("css"))
    }
    @Test fun es6AndConsoleOutputLimitAreEnforced() {
        val result = CodeRunner.runJs("console.log('x'.repeat(70000));")
        assertTrue(result is JsRunResult.Success)
        val output = (result as JsRunResult.Success).output
        assertTrue(output.contains("Output truncated at 65536 characters"))
        assertTrue(output.length < 66_000)
    }

    @Test fun javaBridgeIsNotExposedToSnippets() {
        val result = CodeRunner.runJs("typeof Packages + ':' + typeof JavaAdapter;")
        assertTrue(result is JsRunResult.Success)
        assertEquals("undefined:undefined", (result as JsRunResult.Success).returned)
    }

    @Test fun es6ArrowFunctionsExecute() {
        val result = CodeRunner.runJs("[1, 2].map(x => x * 2).join(',');")
        assertTrue(result is JsRunResult.Success)
        assertEquals("2,4", (result as JsRunResult.Success).returned)
    }

    @Test fun largeReturnValuesAreBounded() {
        val result = CodeRunner.runJs("'x'.repeat(70000);")
        assertTrue(result is JsRunResult.Success)
        val returned = (result as JsRunResult.Success).returned.orEmpty()
        assertTrue(returned.contains("Return value truncated at 65536 characters"))
        assertTrue(returned.length < 66_000)
    }

}
