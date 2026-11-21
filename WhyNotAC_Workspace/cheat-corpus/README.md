# WhyNotAC Persistent Cheat Corpus (`cheat-corpus/`)

Deterministic `.wnrec` recordings exercising WhyNotAC's core deterministic (`P-A`, `P-M`) and statistical (`S`) check suites for reproducible false-negative regression testing and time-to-detect (TTD) benchmarking (`PH10-01`).

## Recordings

- `cheat-reach-4.2b.wnrec` — 4.7-block survival attack reach violation (`attack-reach`)
- `cheat-movement-fly.wnrec` — Impossible upward step/fly movement above flat terrain (`movement`)
- `cheat-velocity-antikb.wnrec` — 0% knockback suppression after confirmed `SetEntityMotion` (`velocity-response`)
- `cheat-invwalk-bounds.wnrec` — Same-tick `ContainerClick` + attack (`inventory-protocol`) and invalid hotbar slot `9` (`packet-bounds`)
- `cheat-tick-framing-timer.wnrec` — Multi-move framing exploit (`tick-framing`) and oscillating slow/fast timer balance (`timer-balance`)
- `cheat-ping-spoof.wnrec` — Sustained `Ping` vs `KeepAlive` RTT divergence (`ping-spoof`)
- `cheat-shield-autoblock.wnrec` — Cross-tick offhand `minecraft:shield` keep-using `AutoBlock` attack (`use-item-state` v2)
- `cheat-partial-velocity-explosion.wnrec` — 30% partial vertical Velocity spoofing `onGround=false`, suppressed `Explosion` knockback, and 30% stationary partial horizontal Velocity (`velocity-response` v3)
- `cheat-pitch-hotbar-spam.wnrec` — Out-of-bounds `pitch = 105°` Derp/Pitch-Flip and duplicate consecutive `SetCarriedItem` NoSlow slot-spam (`packet-bounds` v4)
- `cheat-transaction-skip-dup.wnrec` — Skipped WhyNotAC transaction (`skipped-transaction`) and duplicate/replayed `Pong` (`duplicate-transaction`) (`transaction-order` v2)

Regenerate cleanly anytime from `WhyNotAC_Workspace/`:

```bash
./gradlew -q :whynotac-paper:jar
java -jar whynotac-paper/build/libs/whynotac-paper-*.jar benchmark --write-corpus cheat-corpus
java -jar whynotac-paper/build/libs/whynotac-paper-*.jar benchmark regression cheat-corpus
```
