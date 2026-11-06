/**
 * Platform-agnostic model of the 1.21.11 play-protocol packets that matter for movement, combat and
 * latency analysis.
 *
 * <p>The platform layer (Paper/NMS) translates live packet objects into these immutable records, so
 * every analysis layer above can be tested with synthetic packet streams and no running server.
 * Values are copied exactly as the client encoded them (doubles stay doubles, floats stay floats);
 * nothing is rounded or normalised here.
 *
 * <p>Packets WhyNotAC does not model still travel through the timeline as {@code Unmodeled}
 * records, because their <em>timing and order</em> is evidence too.
 */
package dev.whynotac.protocol.packet;
