# Real client ground truth

`run_realclient.sh <work-dir>` plays a session with the **official, unmodified Minecraft 1.21.11
client** against a real Paper server running WhyNotAC, entirely headless:

1. `fetch_client.py` downloads the client jar, its Linux libraries and the asset index from Mojang,
   each file checked against the SHA-1 Mojang publishes. Sounds and translations are skipped.
2. The client runs on a virtual X display (Xvfb) with Mesa's software OpenGL (llvmpipe), joins with
   `--quickPlayMultiplayer`, and is played by `drive.py` through **XTest**: real keyboard and mouse
   events that pass through GLFW and the client's own input code (sensitivity, per-frame turning).
3. A java agent (`agent/`, built by the script) records the client's exact state at the start and end of
   every `LocalPlayer.tick()`: position, velocity, rotation, collision flags, fall distance, input,
   and the fields `sendPosition()` reads. It adds two static calls to that one method and changes nothing
   else. Obfuscated names come from Mojang's mappings for the same version. It uses the JDK's internal
   ASM, so it has no dependencies.
4. `RealClientCheck.java` reads WhyNotAC's recording and checks it against the vanilla models.
   `verify_realclient.py` then checks everything else, and `verify_truth.py` aligns the client's own
   state with the server's recording.

What a session proves (all checked automatically):

| Check | Meaning |
|---|---|
| Every yaw and pitch step equals the commanded mouse motion, one for one, through `MouseTurnModel` (sensitivity 0.83, motions two ticks apart) | The aim model is bit-exact on the real game AND has the right absolute scale. Negative controls on the same data (multiplier /2, /3, x2, = 1, yaw off by one ulp per step) must all be rejected, so the check cannot pass by accident. Vanilla ignores the first cursor move after the mouse is grabbed (`ignoreFirstMove`): up to two leading motions may be consumed. A passenger's rotations are its boat's heading, not mouse steps: they are left out (while the server's `SetPassengers` lists the player), and the first rotation after a ride starts the steps again |
| No pong between a tick's first packet and its `ClientTickEnd` (play phase) | A vanilla client answers transactions at frame start. This wire-order invariant can become deterministic evidence against off-thread ping spoofers |
| 0 divergences, edge view, brand `vanilla` | The network edge and the game agree on a real client's traffic |
| All movement keys, clicks and hotbar keys reach the server; 19.5-20.5 ticks per second measured after the join | Input and tick clock as the game produces them (world loading drops ticks, so the first 3 s are excluded) |
| No case is opened | The honest client produces no evidence against itself |
| The movement packets rebuilt from the client's own tick states equal the server's recorded `MovePlayer` packets, bit for bit and in order | The per-tick ground truth (exact velocity included) is aligned with the server's timeline. The physics engine is tested against exactly this |

The recordings plus `truth.jsonl` are a ground-truth corpus for the physics engine.

The client is ProGuard-processed: its stack map frames mark `this` as dead where a method no longer uses
it. So the hook before each `return` takes no argument (it reuses the player from the hook at the start).
Reading local 0 there fails bytecode verification.
