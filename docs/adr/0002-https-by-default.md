# 0002. HTTPS by default, with an explicit cleartext opt-in

Status: Accepted

## Context

Podcast feeds, enclosures and artwork are fetched from arbitrary third-party hosts, and many still publish `http://` addresses or redirect an `https://` URL to an `http://` CDN. Sending requests in cleartext exposes listening habits and allows tampering.

## Decision

Allow only HTTPS by default. A user setting enables cleartext for local development. `isTransportAllowed` and `withTransportPolicy` (`core/network/Https.kt`) enforce this, `HttpsRedirectInterceptor` rewrites an `https` to `http` redirect to `https` instead of following it, and `upgradedToHttps` rewrites `http://` episode, download and artwork addresses before they are requested.

## Consequences

- Hosts that only serve HTTP cannot be reached unless the user opts in.
- Upgrading rewrites the address without asking, so an HTTP-only host that also answers on HTTPS keeps working but one that does not will fail.
- Every network path (feeds, playback, downloads, artwork, Nextcloud) must go through the policy; a new client that bypasses it breaks this decision.
