#!/usr/bin/env python3
"""
One leg of the movement course for the real client, through OS-level input (XTest). The server builds the
course and places the player at the start of each leg (run_realclient.sh); this presses the keys.

Each step is KEYS/SECONDS: hold the comma-separated keys together for that long, then release them.
KEYS "-" waits. KEYS/SECONDS/TAP also taps the key TAP every 0.6 s while holding, KEYS/SECONDS/TAP/DELAY the first
time after DELAY seconds. A key "Button1".."Button3" is a mouse button (Button3: use); "move:DX:DY" moves the mouse by
(DX, DY) window pixels when it is pressed (a screen's cursor, or a turn in play).

Phase 26 extensions (PH26-04):
  --toggle-sprint       Translates held Control_L into tap-on / tap-off for vanilla toggleSprint:true.
  --toggle-crouch       Translates held Shift_L into tap-on / tap-off for vanilla toggleCrouch:true.
  --profile sprint_bridge  Runs a legitimate unsneaked sprint-jump bridging sequence.
  --self-test           Validates step parsing and toggle expansion offline (no X server required).

usage: course.py [--toggle-sprint] [--toggle-crouch] [--profile sprint_bridge] DISPLAY STEP...
"""
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # run with -I: import our own helper explicitly

PROFILES = {
    # Legitimate sprint-jump bridging (unsneaked forward/backward jump-placing)
    "sprint_bridge": [
        "move:0:220/0.15",
        "s,Control_L,Button3/1.8/space/0.15",
        "-/0.4",
    ],
    # Legitimate toggleSprint + toggleCrouch combo course
    "toggle_sprint_crouch": [
        "w,Control_L/2.0/space/0.2",
        "s,Shift_L,Button3/2.5",
        "w/1.5",
        "-/0.3",
    ],
}


def parse_args(argv):
    toggle_sprint = False
    toggle_crouch = False
    profile = None
    positional = []
    idx = 0
    while idx < len(argv):
        arg = argv[idx]
        if arg == "--toggle-sprint":
            toggle_sprint = True
        elif arg == "--toggle-crouch":
            toggle_crouch = True
        elif arg == "--profile":
            idx += 1
            profile = argv[idx]
        elif arg == "--self-test":
            return {"self_test": True}
        else:
            positional.append(arg)
        idx += 1
    if not positional:
        raise SystemExit("usage: course.py [--toggle-sprint] [--toggle-crouch] [--profile NAME] DISPLAY STEP...")
    display = positional[0]
    steps = positional[1:]
    if profile:
        if profile not in PROFILES:
            raise SystemExit(f"unknown profile: {profile} (available: {sorted(PROFILES)})")
        steps = list(PROFILES[profile]) + steps
    return {
        "self_test": False,
        "display": display,
        "steps": steps,
        "toggle_sprint": toggle_sprint,
        "toggle_crouch": toggle_crouch,
    }


def run_steps(inp, steps, toggle_sprint=False, toggle_crouch=False, sleep_fn=time.sleep, clock_fn=time.monotonic):
    for button in (1, 2, 3):
        inp.button(button, False)
    sprint_latched = False
    crouch_latched = False

    def tap_key(k):
        inp.key(k, True)
        sleep_fn(0.05)
        inp.key(k, False)

    for step in steps:
        parts = step.split("/")
        keys = [] if parts[0] == "-" else parts[0].split(",")
        seconds = float(parts[1])
        tap = parts[2] if len(parts) > 2 else None
        delay = float(parts[3]) if len(parts) > 3 else 0.0

        held = []
        want_sprint = "Control_L" in keys
        want_crouch = "Shift_L" in keys
        if toggle_sprint and want_sprint != sprint_latched:
            tap_key("Control_L")
            sprint_latched = want_sprint
        if toggle_crouch and want_crouch != crouch_latched:
            tap_key("Shift_L")
            crouch_latched = want_crouch

        for key in keys:
            if toggle_sprint and key == "Control_L":
                continue
            if toggle_crouch and key == "Shift_L":
                continue
            press(inp, key, True)
            held.append(key)

        end = clock_fn() + seconds
        sleep_fn(delay)
        while tap and clock_fn() + 0.1 < end:
            press(inp, tap, True)
            sleep_fn(0.1)
            press(inp, tap, False)
            sleep_fn(min(0.5, max(0.0, end - clock_fn())))
        sleep_fn(max(0.0, end - clock_fn()))
        for key in reversed(held):
            press(inp, key, False)

    # Always release latched toggle modes cleanly at leg end
    if toggle_sprint and sprint_latched:
        tap_key("Control_L")
    if toggle_crouch and crouch_latched:
        tap_key("Shift_L")


def press(i, key, down):
    if key.startswith("move:"):
        if down:
            dx, dy = key[len("move:"):].split(":")
            i.move(int(dx), int(dy))
    elif key.startswith("Button"):
        i.button(int(key[len("Button"):]), down)
    else:
        i.key(key, down)


def self_test():
    class FakeInput:
        def __init__(self):
            self.events = []
        def key(self, k, down):
            self.events.append(("key", k, down))
        def button(self, b, down):
            self.events.append(("button", b, down))
        def move(self, dx, dy):
            self.events.append(("move", dx, dy))

    fake = FakeInput()
    clock = [0.0]
    def fake_sleep(dt):
        clock[0] += max(0.0, dt)
    parsed = parse_args(["--toggle-sprint", "--toggle-crouch", "--profile", "sprint_bridge", ":99", "s,Shift_L/0.3"])
    run_steps(fake, parsed["steps"], parsed["toggle_sprint"], parsed["toggle_crouch"], fake_sleep, lambda: clock[0])
    # Verify Control_L and Shift_L were toggled ON and back OFF cleanly (even count of presses)
    ctrl_downs = sum(1 for t, k, d in fake.events if t == "key" and k == "Control_L" and d)
    shift_downs = sum(1 for t, k, d in fake.events if t == "key" and k == "Shift_L" and d)
    assert ctrl_downs == 2, f"Expected toggleSprint ON+OFF (2 taps), got {ctrl_downs}"
    assert shift_downs == 2, f"Expected toggleCrouch ON+OFF (2 taps), got {shift_downs}"
    assert any(t == "move" for t, _, _ in fake.events)
    print("REALCLIENT_COURSE_SELF_TEST: OK")


def main():
    cfg = parse_args(sys.argv[1:])
    if cfg.get("self_test"):
        self_test()
        return
    from xinput import Input  # noqa: E402
    run_steps(Input(cfg["display"]), cfg["steps"], cfg["toggle_sprint"], cfg["toggle_crouch"])


if __name__ == "__main__":
    main()
