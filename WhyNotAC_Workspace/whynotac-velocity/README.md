# whynotac-velocity — Velocity 3.x proxy companion (`NET-01` / `NET-22`)

Proxy-side companion for deployments where WhyNotAC backend Paper servers sit behind a Velocity proxy.

- **Signed Client-Edge Declarations (`NET-01`):** On `ServerPostConnectEvent`, encodes a HMAC-SHA256 signed [`NetworkEnvelope.ProxyClientEdge`](../whynotac-protocol) carrying the client's true wire protocol version, proxy online-mode / Floodgate authentication status, and proxy translator presence (`ViaVersion` / `Geyser`).
- **Policy-Enforced Cross-Server Relay (`NET-22`):** Authenticates `whynotac:bridge` plugin messages with `network-secret.key`, blocks any cross-server sync carrying unauthenticated or claimed identities (`docs/POLICY.md`), and broadcasts verified enforcement/overturn/GDPR notices and authenticated profile summaries across connected backend servers.
- **Commands:** `/wnacproxy status` (permission `whynotac.proxy.admin`).
