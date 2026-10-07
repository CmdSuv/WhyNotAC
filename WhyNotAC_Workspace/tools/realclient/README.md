# Real client ground truth

`run_realclient.sh <work-dir>` plays a session with the **official, unmodified Minecraft 1.21.11
client** against a real Paper server running WhyNotAC, entirely headless:

1. `fetch_client.py` downloads the client jar, its Linux libraries and the asset index from Mojang,
   each file checked against the SHA-1 Mojang publishes. Sounds and translations are skipped.
2. The client runs on a virtual X display (Xvfb) with Mesa's software OpenGL (llvmpipe), joins with
   `--quickPlayMultiplayer`, and is played by `drive.py` through **XTest**: real keyboard and mouse
   events that pass through GLFW and the client's own input code (sensitivity, per-frame turning).
3. `RealClientCheck.java` reads WhyNotAC's recording and checks it against the vanilla models.
   `verify_realclient.py` then checks everything else.

What a session proves (all checked automatically):

| Check | Meaning |
|---|---|
| Every yaw and pitch change is explained by integer mouse counts through `MouseTurnModel` | The aim model is exact on the real game, not only on our own probe |
| No pong between a tick's first packet and its `ClientTickEnd` (play phase) | A vanilla client answers transactions at frame start. This wire-order invariant can become deterministic evidence against off-thread ping spoofers |
| 0 divergences, edge view, brand `vanilla` | The network edge and the game agree on a real client's traffic |
| All movement keys, clicks and hotbar keys reach the server; about 20 ticks per second | Input and tick clock as the game produces them |

The recordings are a ground-truth corpus for the physics engine.
