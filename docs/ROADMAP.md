# Roadmap

Planned work that is not started. Items are ideas with a stated motivation, not commitments or a
schedule. Decisions already taken are in [adr/](adr/README.md).

## Cheaper feed refreshes

**Motivation.** Every sync downloads and parses each subscribed feed in full, even when nothing
has changed. That costs bandwidth, battery and server load, and feeds with long back catalogues are
large. `OkHttpFeedFetcher` sends a plain GET and keeps no HTTP cache.

**Direction.**

- Conditional requests: store each feed's `ETag` and `Last-Modified` and send `If-None-Match` and
  `If-Modified-Since`. A `304 Not Modified` skips parsing and the Room merge entirely.
- An OkHttp disk cache for feed responses, or the validators kept in Room beside the podcast, so
  they survive process death. Choose between the two when starting.
- Treat `304` as a successful refresh in `PodcastSyncPipeline`, so it counts towards
  `refreshedFeeds` and does not look like a failure.
- Honour `Cache-Control: max-age` and `Retry-After` so a well-behaved feed is not polled more often than it asks.

**Related, not part of this.** Size caps and an overall call timeout on feed and download
responses are a hardening task and are tracked separately.

**Tests.** Use MockWebServer for the validators, the `304` path and the failure cases, test-first
as for any feature.
