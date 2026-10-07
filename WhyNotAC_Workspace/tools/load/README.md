# Load test

`run_load.sh <work-dir> <baseline|whynotac> [bots] [measure-seconds] [spread|crowd]` starts a real
Paper 1.21.11 server, connects many honest protocol-774 bots (`load_bots.py`, built on the E2E
probe's protocol code) and measures one window after a warm-up. Run it once without the plugin
(`baseline`) and once with it (`whynotac`, session recording on) and compare.

The server runs on CPUs 0-1 and the bots on the remaining ones (`taskset`), so the bots' own load does
not show up in the server's numbers. `spread` places the bots far apart (per-player cost), `crowd`
keeps them together (every bot sees every other one).

Every bot behaves like a vanilla client and never breaks a rule. Under load, **any** WhyNotAC anomaly,
divergence or lost event for these bots is a false positive, and the test reports it.

| Measured | How |
|---|---|
| Tick time (MSPT) and TPS | Paper's `mspt` and `tps` over the last minute (the measurement window) |
| Server CPU | utime + stime of the server process over the window, in cores |
| WhyNotAC's share of CPU samples, per thread | JFR `profile`, attributed by `JfrAttribution.java`: a sample is WhyNotAC's when a WhyNotAC frame comes before any Netty pipeline dispatch frame on its stack (the handlers WhyNotAC forwards to do not count); `anywhere` is the upper bound |
| Main-thread cost of the per-tick transaction loop | `/whynotac stats`: wall and CPU time per tick |
| WhyNotAC heap | `GC.class_histogram` after a full GC, `dev.whynotac.*` classes |
| False positives and lost data | `/whynotac stats` during load and after every bot has left: anomalies by kind, divergences, lost events, listener errors, rejected recording blocks |
| Bot side | joined, still connected at the end, pings seen and answered |

Result: `<work-dir>/load-<variant>.json`. The numbers depend on the machine; compare runs from the same
machine only.
