# 0003. Streaming, XXE-hardened XML parsing

Status: Accepted

## Context

RSS feeds and OPML files come from untrusted sources and can be very large. Parsing them with a DOM or as a whole string uses memory proportional to the document and, with a permissive parser, exposes the app to XML external entity (XXE) attacks.

## Decision

Parse with a custom streaming parser over `XmlPullParser`, reading from an `InputStream`, so a feed is never held in memory as a string. Parsers are built by `newHardenedParser`, which turns off DOCTYPE processing (and so external entity declarations). The `XmlPullParserFactory` is injected so the parsers are unit-testable on the JVM.

## Consequences

- Memory use is bounded regardless of feed size.
- The parser code is hand-written and must cope with malformed input itself, which is covered by tests.
- DOCTYPE-dependent documents are not supported, which is not a concern for real RSS or OPML.
