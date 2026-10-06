/**
 * Lossless, crash-safe recording of player timelines, and deterministic replay.
 *
 * <p>Every analysis layer WhyNotAC will ever have must be measurable on real, clean play before it
 * is allowed to produce evidence. Recordings are that ground truth: a recorded session replays into
 * the same {@link dev.whynotac.protocol.session.TimelineListener}s with the same sequence numbers,
 * timestamps and values, bit for bit, so a false positive found once becomes a permanent test.
 *
 * <h2>File format (version 1)</h2>
 * <pre>
 * header  : magic 89 'W' 'N' 'R' 'E' 'C' 0D 0A | u16 version | varint protocol | str game version
 *           | str WhyNotAC version | 16B recording id | 16B pseudonymous subject | i64 start epoch ms
 *           | u32 CRC32C(header)
 * block*  : "WNBK" | u32 compressed len | u32 raw len | u64 base seq | u64 base nanos | u64 base tick
 *           | u32 events | u64 events lost before | u32 CRC32C(fields + payload) | zlib payload
 * payload : record* , record = varint tag | varint length | body
 * </pre>
 * Blocks are self-contained (delta and string-table state restart in every block), so a damaged
 * block costs exactly its own events: the reader resynchronises on the next {@code WNBK} marker and
 * reports the loss as an explicit {@link dev.whynotac.protocol.session.TimelineEvent.Gap}. A file is
 * written as {@code .wnrec.part} and renamed to {@code .wnrec} only after its end record is durable;
 * after a crash every block that reached the disk is still readable.
 *
 * <h2>Privacy</h2>
 * No IP address and no player name are written. The player UUID is replaced by an HMAC-SHA256
 * pseudonym keyed with a per-server secret. Packet contents are limited to the modelled movement,
 * combat and latency fields; unmodelled packets (chat, for example) are reduced to their type id.
 */
package dev.whynotac.recording;
