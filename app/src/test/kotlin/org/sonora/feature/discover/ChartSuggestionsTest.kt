// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.DirectoryException
import org.sonora.core.network.discovery.PodcastChartProvider

class ChartSuggestionsTest {
    private fun pod(title: String, feed: String = "https://f/$title") = DirectoryPodcast(title, null, feed, null)

    private class FakeCharts(val charts: Map<String, List<DirectoryPodcast>>, val failing: Set<String> = emptySet()) :
        PodcastChartProvider {
        val calls = mutableListOf<Pair<String, Int>>()
        override suspend fun top(country: String, limit: Int): List<DirectoryPodcast> {
            calls += country to limit
            delay(1000)
            if (country in failing) throw DirectoryException("$country down")
            return charts.getValue(country)
        }
    }

    @Test
    fun `UK shows come first, then the rest of the world, asking each chart for its own share`() = runTest {
        val charts = FakeCharts(mapOf("gb" to listOf(pod("UK1"), pod("UK2")), "us" to listOf(pod("US1"))))

        val result = ChartSuggestions(charts, listOf(ChartSlot("gb", 20), ChartSlot("us", 10))).suggestions()

        assertEquals(listOf("UK1", "UK2", "US1"), result.map { it.title })
        assertEquals(listOf("gb" to 20, "us" to 10), charts.calls)
    }

    @Test
    fun `a show in several charts appears once, in its earliest place`() = runTest {
        val charts = FakeCharts(
            mapOf("gb" to listOf(pod("Both", "https://x/")), "us" to listOf(pod("Both again", "https://X"), pod("US only"))),
        )

        val result = ChartSuggestions(charts, listOf(ChartSlot("gb", 5), ChartSlot("us", 5))).suggestions()

        assertEquals(listOf("Both", "US only"), result.map { it.title })
    }

    @Test
    fun `one chart failing still gives the others`() = runTest {
        val charts = FakeCharts(mapOf("us" to listOf(pod("US1"))), failing = setOf("gb"))

        val result = ChartSuggestions(charts, listOf(ChartSlot("gb", 5), ChartSlot("us", 5))).suggestions()

        assertEquals(listOf("US1"), result.map { it.title })
    }

    @Test
    fun `every chart failing is reported`() = runTest {
        val charts = FakeCharts(emptyMap(), failing = setOf("gb", "us"))

        val error = assertThrows<DirectoryException> {
            ChartSuggestions(charts, listOf(ChartSlot("gb", 5), ChartSlot("us", 5))).suggestions()
        }
        assertEquals("gb down", error.message)
    }

    @Test
    fun `charts are fetched at the same time, not one after another`() = runTest {
        val charts = FakeCharts(mapOf("gb" to listOf(pod("UK1")), "us" to listOf(pod("US1"))))

        ChartSuggestions(charts, listOf(ChartSlot("gb", 5), ChartSlot("us", 5))).suggestions()

        assertEquals(1000, currentTime)
    }

    @Test
    fun `having no charts configured is an error`() = runTest {
        assertThrows<IllegalStateException> { ChartSuggestions(FakeCharts(emptyMap()), emptyList()).suggestions() }
    }

    @Test
    fun `a source with no cache offers nothing instantly`() {
        val source = object : DirectorySuggestions {
            override suspend fun suggestions() = emptyList<DirectoryPodcast>()
        }
        assertEquals(emptyList<DirectoryPodcast>(), source.cached())
    }

    @Test
    fun `the default is the UK chart with a smaller global tail`() {
        assertEquals(listOf(ChartSlot("gb", 20), ChartSlot("us", 10)), ChartSuggestions.DEFAULT_SLOTS)
    }
}
