# WhyNotAC Real Open-Source Cheat Corpus (`cheat-corpus/real/`, `DATA-02`)

> **Strict separation from synthetic benchmarks:** Every `.wnrec` file in this directory is a **real network-edge recording** captured from **unmodified open-source Minecraft 1.21.11 cheat clients** (Wurst Client `v7.57` and Meteor Client `1.21.11-86`) running on Fabric Loader `0.18.4` against a local `127.0.0.1` Paper `1.21.11-132` server with WhyNotAC in `SHADOW` mode (`armed-checks: []`, `mitigations: false`, `auto-kick: false`, `auto-ban: false`). Synthetic scenario traces live separately under [`cheat-corpus/`](../README.md).

## Isolation & Ethics Provenance

- **Authorization:** Collected with explicit project-owner approval (`DATA-02` gate).
- **Network isolation:** Executed exclusively inside a rootless Podman container with `--network=none` (kernel network namespace isolation; only container-local `127.0.0.1` loopback exists — verified by `UnknownHostException` on `api.github.com` / `sessionserver.mojang.com`). **Zero public servers were ever touched.**
- **Binary provenance (SHA-256 verified):**
  - Server: Paper `1.21.11-132` (`paper-1.21.11-132.jar`, SHA-256 `5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba`)
  - Loader: Fabric Loader `0.18.4` + Fabric API `0.141.6+1.21.11` (SHA-256 `bdff7fd7e220085cfad2ff9b1f40dde6534ae0b96cf378f97a374bc54cb9ed0f`)
  - **Wurst Client `v7.57-MC1.21.11`** (`GPL-3.0-or-later`, SHA-256 `66e7285aeb51b820cfbe30cc10d81cac20871f0ca51e9ba2f54a1d7014780317`)
  - **Meteor Client `1.21.11-20260810.140237-86`** (`GPL-3.0`, SHA-256 `93390cfdb1030e47f116357ba421c90a9b1ca4832b6551c2dec1ade5eeb42e71`)

## Measured Detection Summary (`Checks.all`, `SHADOW` Mode)

| Client | Modules Tested | Detected (`TP`) | Missed (`FN`) | Measured TPR | Evidence Classes | Rejected / Errors |
|---|---:|---:|---:|---:|---|---:|
| **Wurst Client `v7.57-MC1.21.11`** | 13 | 13 | 0 | **100.0%** | `P-A` (2), `P-M` (13) | 0 / 0 |
| **Meteor Client `1.21.11-86`** | 14 | 13 | 1 | **92.9%** | `P-A` (2), `P-M` (12) | 0 / 0 |
| **Combined `DATA-02` Real Corpus** | **27** | **26** | **1** | **96.3%** | **9 independent proof checks** | **0 / 0** |

> **Timing note:** Each `.wnrec` captures a full session from login (`t = 0 s`), including ~7.5–9.5 s of world load, teleport setup, baseline anchor, and `.t <module> on` chat toggle before the cheat module activates. `First Tick` and `Session t` below are measured from recording start (reproducible offline via [`RealCheatCorpusReplayTest`](../../whynotac-analysis/src/test/java/dev/whynotac/analysis/checks/RealCheatCorpusReplayTest.java)).

## Per-Module Measured Results — Wurst Client `v7.57-MC1.21.11` (`13/13`, `100.0%` TPR)

| Recording | Module (Default Settings) | Size | Ticks | Outcome | Fired Checks & Proof Class | Count | First Tick | Session `t` |
|---|---|---:|---:|---|---|---:|---:|---:|
| `wurst-flight.wnrec` | `Flight` | 38.8 KB | 262 | **DETECTED** | `movement` (`P-M`) | 1 | 123 | 9.48 s |
| `wurst-speed.wnrec` | `SpeedHack` | 34.0 KB | 255 | **DETECTED** | `movement` (`P-M`) | 1 | 148 | 8.85 s |
| `wurst-timer.wnrec` | `Timer` (`2.0x`) | 34.9 KB | 368 | **DETECTED** | `client-clock` (`P-M`) | 5 | 163 | 8.96 s |
| `wurst-killaura.wnrec` | `Killaura` (facing away from Husk) | 29.5 KB | 262 | **DETECTED** | `attack-aim` (`P-M`) | 5 | 136 | 7.97 s |
| `wurst-reach.wnrec` | `Reach` (`4.2+` blocks on Husk) | 29.1 KB | 240 | **DETECTED** | `attack-reach` (`P-A`) | 4 | 132 | 8.24 s |
| `wurst-antikb.wnrec` | `AntiKnockback` (Zombie hit) | 36.8 KB | 289 | **DETECTED** | `movement` (`P-M`), `velocity-response` (`P-A`) | 1 + 5 | 146 / 147 | 8.46 s |
| `wurst-noslow.wnrec` | `NoSlowdown` (sprint + golden apple) | 33.7 KB | 248 | **DETECTED** | `movement` (`P-M`) | 1 | 144 | 9.13 s |
| `wurst-fastbreak.wnrec` | `FastBreak` (stone w/ iron pickaxe) | 33.3 KB | 244 | **DETECTED** | `break-time` (`P-M`) | 2 | 135 | 8.17 s |
| `wurst-scaffold.wnrec` | `ScaffoldWalk` (stone bridge) | 39.8 KB | 248 | **DETECTED** | `action-grammar` (`P-M`), `tick-framing` (`P-M`), `use-aim` (`P-M`) | 4 + 18 + 4 | 149 | 8.17 s |
| `wurst-blink.wnrec` | `Blink` (3.5 s hold + release) | 34.3 KB | 301 | **DETECTED** | `movement` (`P-M`), `tick-framing` (`P-M`) | 1 + 1 | 152 / 245 | 8.05 s |
| `wurst-criticals.wnrec` | `Criticals` (packet mini-hop) | 31.1 KB | 247 | **DETECTED** | `tick-framing` (`P-M`), `action-grammar` (`P-M`) | 10 + 4 | 139 | 8.44 s |
| `wurst-jesus.wnrec` | `Jesus` (walk over water) | 33.1 KB | 237 | **DETECTED** | `movement` (`P-M`) | 1 | 136 | 8.35 s |
| `wurst-step.wnrec` | `Step` (1-block stone ledge, `autoJump:false`) | 31.3 KB | 207 | **DETECTED** | `tick-framing` (`P-M`) | 1 | 129 | 10.70 s |

## Per-Module Measured Results — Meteor Client `1.21.11-86` (`13/14`, `92.9%` TPR)

| Recording | Module (Settings) | Size | Ticks | Outcome | Fired Checks & Proof Class | Count | First Tick | Session `t` |
|---|---|---:|---:|---|---|---:|---:|---:|
| `meteor-flight.wnrec` | `flight` (default) | 37.3 KB | 249 | **DETECTED** | `movement` (`P-M`) | 1 | 116 | 10.13 s |
| `meteor-speed.wnrec` | `speed` (default) | 32.3 KB | 220 | **DETECTED** | `movement` (`P-M`) | 1 | 119 | 8.47 s |
| `meteor-timer.wnrec` | `timer` (`multiplier=2.0`) | 36.0 KB | 412 | **DETECTED** | `client-clock` (`P-M`) | 5 | 222 | 11.40 s |
| `meteor-killaura.wnrec` | `kill-aura` (`entities=husk`, default `rotate=Always`, stationary target `2.7` b, 5 s) | 31.5 KB | 286 | **MISSED (`FN`)** | — (honest FN; see analysis below) | 0 | — | — |
| `meteor-reach.wnrec` | `reach` (`extra-entity-reach=1.5`) | 31.3 KB | 296 | **DETECTED** | `attack-reach` (`P-A`) | 4 | 195 | 11.26 s |
| `meteor-velocity.wnrec` | `velocity` (Zombie hit) | 37.4 KB | 273 | **DETECTED** | `movement` (`P-M`), `velocity-response` (`P-A`) | 1 + 4 | 135 / 136 | 9.88 s |
| `meteor-noslow.wnrec` | `no-slow` (sprint + golden apple) | 32.5 KB | 251 | **DETECTED** | `movement` (`P-M`) | 1 | 151 | 8.22 s |
| `meteor-speedmine.wnrec` | `speed-mine` (stone w/ iron pickaxe) | 31.9 KB | 247 | **DETECTED** | `break-time` (`P-M`) | 2 | 151 | 8.10 s |
| `meteor-scaffold.wnrec` | `scaffold` (stone bridge) | 34.5 KB | 225 | **DETECTED** | `action-grammar` (`P-M`), `use-aim` (`P-M`), `movement` (`P-M`) | 4 + 4 + 1 | 133 / 134 | 8.13 s |
| `meteor-blink.wnrec` | `blink` (3.5 s hold + release) | 35.8 KB | 290 | **DETECTED** | `movement` (`P-M`), `tick-framing` (`P-M`) | 1 + 1 | 148 / 240 | 8.21 s |
| `meteor-criticals.wnrec` | `criticals` (packet mini-hop) | 30.7 KB | 243 | **DETECTED** | `tick-framing` (`P-M`), `action-grammar` (`P-M`) | 10 + 4 | 142 | 8.10 s |
| `meteor-jesus.wnrec` | `jesus` (walk over water) | 32.1 KB | 231 | **DETECTED** | `movement` (`P-M`) | 1 | 137 | 8.13 s |
| `meteor-step.wnrec` | `step` (1-block stone ledge, `autoJump:false`) | 30.6 KB | 207 | **DETECTED** | `movement` (`P-M`) | 1 | 127 | 8.82 s |
| `meteor-guimove.wnrec` | `gui-move` (sprint while chest menu open) | 30.5 KB | 219 | **DETECTED** | `action-grammar` (`P-M`) | 1 | 156 | 8.75 s |

### Honest Analysis of the 1 False Negative (`meteor-killaura.wnrec`)

In `meteor-killaura.wnrec`, Meteor's default `KillAura` (`rotate = Always`) targets a single stationary `minecraft:husk` (`NoAI:1b`) at `2.7` blocks for 5 seconds. On the wire, Meteor sends:
1. One single 180° yaw rotation (`90.0° -> -90.0°`, `pitch = 13.44°`) on tick `158` in the same tick as the first `InteractEntity(ATTACK)` + `Swing(MAIN_HAND)` (protocol-valid order before `MovePlayer`), then
2. Ten attacks at exact 12-tick sword-cooldown spacing (`ticks 158, 170, 182, ..., 266`) while aiming directly at the stationary target within vanilla `3.0`-block reach.

Because a legitimate vanilla player can legally turn 180° in one tick and strike a stationary mob 2.7 blocks away 10 times, **no deterministic `P-A` / `P-M` proof check can soundly claim cheating here** (and must not, to preserve zero false positives). Statistical checks (`aim-pattern`, `click-pattern`) intentionally require longer engagements (`>= 60` click intervals or repeated combat snap-backs / moving-target tracking) and `design-probe` (`D`, opt-in invisible combat decoys) is disabled by default (`ai.active-probes.enabled: false`). This single recording is kept verbatim in `cheat-corpus/real/` as an honest documented detection boundary.

## Reproducing Offline Verification

```bash
cd WhyNotAC_Workspace
./gradlew :whynotac-analysis:test --tests dev.whynotac.analysis.checks.RealCheatCorpusReplayTest
```
