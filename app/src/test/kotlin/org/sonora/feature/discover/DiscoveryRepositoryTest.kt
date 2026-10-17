// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.feature.discover

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.sonora.core.model.DirectoryPodcast
import org.sonora.core.network.discovery.DirectoryException
import org.sonora.core.network.discovery.DiscoveryCredentialsSource
import org.sonora.core.network.discovery.PodcastDirectoryProvider
import org.sonora.core.network.discovery.PodcastIndexCredentials

class DiscoveryRepositoryTest {
    private fun pod(title: String, feed: String) = DirectoryPodcast(title, null, feed, null)

    private class FakeProvider(val results: List<DirectoryPodcast> = emptyList(), val error: Throwable? = null) :
        PodcastDirectoryProvider {
        val queries = mutableListOf<String>()
        override suspend fun search(query: String): List<DirectoryPodcast> {
            queries += query
            error?.let { throw it }
            return results
        }
    }

    private class FakeCredentials(var value: PodcastIndexCredentials?) : DiscoveryCredentialsSource {
        override suspend fun podcastIndexCredentials() = value
    }

    private fun repo(apple: PodcastDirectoryProvider, index: FakeProvider, creds: FakeCredentials, created: MutableList<PodcastIndexCredentials> = mutableListOf()) =
        DiscoveryRepository(apple, creds) { created += it; index }

    @Test
    fun `uses only Apple when no PodcastIndex credentials are set`() = runTest {
        val apple = FakeProvider(listOf(pod("A", "https://a")))
        val index = FakeProvider(listOf(pod("I", "https://i")))
        val created = mutableListOf<PodcastIndexCredentials>()

        repo(apple, index, FakeCredentials(null), created).search("q").test {
            assertEquals(listOf(pod("A", "https://a")), awaitItem())
            awaitComplete()
        }
        assertEquals(emptyList<String>(), index.queries)
        assertEquals(emptyList<PodcastIndexCredentials>(), created)
    }

    @Test
    fun `treats blank credentials as absent`() = runTest {
        val apple = FakeProvider(listOf(pod("A", "https://a")))
        val index = FakeProvider()
        repo(apple, index, FakeCredentials(PodcastIndexCredentials(" ", ""))).search("q").test {
            awaitItem()
            awaitComplete()
        }
        assertEquals(emptyList<String>(), index.queries)
    }

    @Test
    fun `merges PodcastIndex first then Apple and de-duplicates by feed url`() = runTest {
        val apple = FakeProvider(listOf(pod("Shared (Apple)", "https://shared/"), pod("Only Apple", "https://apple")))
        val index = FakeProvider(listOf(pod("Shared (Index)", "https://shared"), pod("Only Index", "https://index")))
        val creds = PodcastIndexCredentials("k", "s")

        repo(apple, index, FakeCredentials(creds)).search("q").test {
            val merged = expectMostRecentItem()
            awaitComplete()
            assertEquals(listOf("Shared (Index)", "Only Index", "Only Apple"), merged.map { it.title })
        }
    }

    @Test
    fun `emits progressively as each directory answers`() = runTest {
        val apple = FakeProvider(listOf(pod("A", "https://a")))
        val index = FakeProvider(listOf(pod("I", "https://i")))

        repo(apple, index, FakeCredentials(PodcastIndexCredentials("k", "s"))).search("q").test {
            assertEquals(listOf("I"), awaitItem().map { it.title })
            assertEquals(listOf("I", "A"), awaitItem().map { it.title })
            awaitComplete()
        }
    }

    @Test
    fun `one failing directory does not hide the other's results`() = runTest {
        val apple = FakeProvider(error = DirectoryException("apple down"))
        val index = FakeProvider(listOf(pod("I", "https://i")))

        repo(apple, index, FakeCredentials(PodcastIndexCredentials("k", "s"))).search("q").test {
            assertEquals(listOf("I"), awaitItem().map { it.title })
            awaitComplete()
        }
    }

    @Test
    fun `fails when every directory fails`() = runTest {
        val apple = FakeProvider(error = DirectoryException("apple down"))
        val index = FakeProvider(error = DirectoryException("index down"))

        repo(apple, index, FakeCredentials(PodcastIndexCredentials("k", "s"))).search("q").test {
            assertTrue(awaitError() is DirectoryException)
        }
    }

    @Test
    fun `Apple failure alone surfaces as an error`() = runTest {
        val apple = FakeProvider(error = DirectoryException("apple down"))
        repo(apple, FakeProvider(), FakeCredentials(null)).search("q").test {
            assertEquals("apple down", awaitError().message)
        }
    }

    @Test
    fun `searching one directory never touches the other`() = runTest {
        val apple = FakeProvider(listOf(pod("A", "https://a")))
        val index = FakeProvider(listOf(pod("I", "https://i")))
        val creds = FakeCredentials(PodcastIndexCredentials("k", "s"))

        repo(apple, index, creds).search("q", DirectorySource.PODCAST_INDEX).test {
            assertEquals(listOf(pod("I", "https://i")), awaitItem())
            awaitComplete()
        }
        assertEquals(emptyList<String>(), apple.queries)

        repo(apple, index, creds).search("q", DirectorySource.APPLE).test {
            assertEquals(listOf(pod("A", "https://a")), awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `PodcastIndex without credentials fails with a clear error`() = runTest {
        repo(FakeProvider(), FakeProvider(), FakeCredentials(null)).search("q", DirectorySource.PODCAST_INDEX).test {
            assertTrue(awaitError() is DirectoryException)
        }
    }

    @Test
    fun `a failing selected directory is an error, not a silent fallback`() = runTest {
        val apple = FakeProvider(error = DirectoryException("down"))
        repo(apple, FakeProvider(listOf(pod("I", "https://i"))), FakeCredentials(PodcastIndexCredentials("k", "s")))
            .search("q", DirectorySource.APPLE).test {
                assertTrue(awaitError() is DirectoryException)
            }
    }
}
