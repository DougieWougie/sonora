// SPDX-License-Identifier: GPL-3.0-or-later
package org.sonora.core.database

/** Holds the in-memory fakes to the same behaviour as Room; see [DaoContract]. */
class FakeDaoContractTest : DaoContract() {
    override val episodes = FakeEpisodeDao()
    override val podcasts = FakePodcastDao(episodes)
    override val queue = FakeSyncQueueDao()
    override val syncState = FakeSyncStateDao()
    override val changes = FakeSubscriptionChangeDao()
}
