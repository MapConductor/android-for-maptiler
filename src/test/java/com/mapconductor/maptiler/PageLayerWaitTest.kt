package com.mapconductor.maptiler

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PageLayerWait] returns only once the page's map has every layer, which is
 * what holds an overlay's `onAdded` until it is really on the map.
 *
 * The page is faked: [FakePage] answers the script the way the real one does,
 * from a set of layer ids that tests add to between polls.
 */
class PageLayerWaitTest {
    /** Answers [PageLayerWait.script] from [layers], counting the calls. */
    private class FakePage {
        val layers = mutableSetOf<String>()
        val asked = mutableListOf<List<String>>()
        var onAsk: (Int) -> Unit = {}

        fun evaluate(js: String): String {
            val ids =
                Regex("var ids=\\[(.*?)\\];")
                    .find(js)!!
                    .groupValues[1]
                    .split(',')
                    .filter { it.isNotEmpty() }
                    .map { it.trim('"') }
            asked += ids
            onAsk(asked.size)
            val present = ids.takeWhile { it in layers }.size
            // evaluateJavascript hands a string value back JSON-quoted.
            return "\"$present\""
        }
    }

    @Test
    fun returnsAtOnceForNoLayers() =
        runBlocking {
            var calls = 0
            assertTrue(
                PageLayerWait.await(emptyList()) {
                    calls++
                    "0"
                },
            )
            assertEquals(0, calls)
        }

    @Test
    fun waitsUntilEveryLayerIsOnTheMap() =
        runBlocking {
            val page = FakePage()
            // The layers arrive over three polls, as the SDK's queue empties.
            page.onAsk = { n ->
                if (n == 2) page.layers += "a"
                if (n == 3) page.layers += listOf("b", "c")
            }
            val done = PageLayerWait.await(listOf("a", "b", "c"), intervalMs = 1) { page.evaluate(it) }
            assertTrue(done)
            assertEquals(listOf("a", "b", "c"), page.layers.sorted())
            // Asks again only for the layers still missing.
            assertEquals(listOf(listOf("a", "b", "c"), listOf("a", "b", "c"), listOf("b", "c")), page.asked)
        }

    @Test
    fun givesUpAfterTheTimeout() =
        runBlocking {
            val page = FakePage()
            page.layers += "a"
            val done = PageLayerWait.await(listOf("a", "never"), timeoutMs = 100, intervalMs = 5) { page.evaluate(it) }
            assertFalse(done)
            assertTrue(page.asked.size > 1)
        }

    @Test
    fun aFailedAskCountsAsNothingYet() =
        runBlocking {
            var calls = 0
            val done =
                PageLayerWait.await(listOf("a"), intervalMs = 1) {
                    calls++
                    when (calls) {
                        1 -> throw IllegalStateException("bridge not loaded")
                        2 -> null
                        else -> "\"1\""
                    }
                }
            assertTrue(done)
            assertEquals(3, calls)
        }

    @Test
    fun parseReadsTheCountQuotedOrNot() {
        assertEquals(3, PageLayerWait.parse("\"3\""))
        assertEquals(3, PageLayerWait.parse("3"))
        assertEquals(0, PageLayerWait.parse(null))
        assertEquals(0, PageLayerWait.parse("null"))
    }

    @Test
    fun idsAreQuotedForTheScript() {
        val js = PageLayerWait.script(listOf("polygon-fill-a\"b", "x\\y"))
        assertTrue(js, js.contains("[\"polygon-fill-a\\\"b\",\"x\\\\y\"]"))
        assertTrue(js.contains(PageLayerWait.MAP_EXPR))
    }
}
