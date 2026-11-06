"""
Real OS-level input for a Minecraft client on an X display, through the XTest extension (ctypes,
stdlib only). Events enter the X server exactly like a physical keyboard and mouse, so the client's
whole input path runs unmodified: GLFW, MouseHandler's per-frame sensitivity math, KeyMapping.
"""
import ctypes
import ctypes.util
import time

_x11 = ctypes.CDLL(ctypes.util.find_library("X11") or "libX11.so.6")
_xtst = ctypes.CDLL(ctypes.util.find_library("Xtst") or "libXtst.so.6")
_x11.XOpenDisplay.restype = ctypes.c_void_p
_x11.XOpenDisplay.argtypes = [ctypes.c_char_p]
_x11.XStringToKeysym.restype = ctypes.c_ulong
_x11.XStringToKeysym.argtypes = [ctypes.c_char_p]
_x11.XKeysymToKeycode.restype = ctypes.c_ubyte
_x11.XKeysymToKeycode.argtypes = [ctypes.c_void_p, ctypes.c_ulong]
_x11.XFlush.argtypes = [ctypes.c_void_p]
_x11.XSync.argtypes = [ctypes.c_void_p, ctypes.c_int]
_xtst.XTestFakeKeyEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
_xtst.XTestFakeButtonEvent.argtypes = [ctypes.c_void_p, ctypes.c_uint, ctypes.c_int, ctypes.c_ulong]
_xtst.XTestFakeRelativeMotionEvent.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_ulong]


class Input:
    def __init__(self, display=":99"):
        self.display = _x11.XOpenDisplay(display.encode())
        if not self.display:
            raise RuntimeError("cannot open X display " + display)

    def _code(self, key):
        code = _x11.XKeysymToKeycode(self.display, _x11.XStringToKeysym(key.encode()))
        if not code:
            raise ValueError("no keycode for " + key)
        return code

    def key(self, key, down):
        _xtst.XTestFakeKeyEvent(self.display, self._code(key), 1 if down else 0, 0)
        _x11.XFlush(self.display)

    def tap(self, key, hold=0.05):
        self.key(key, True)
        time.sleep(hold)
        self.key(key, False)

    def button(self, number, down):
        _xtst.XTestFakeButtonEvent(self.display, number, 1 if down else 0, 0)
        _x11.XFlush(self.display)

    def click(self, number=1, hold=0.05):
        self.button(number, True)
        time.sleep(hold)
        self.button(number, False)

    def move(self, dx, dy):
        _xtst.XTestFakeRelativeMotionEvent(self.display, int(dx), int(dy), 0)
        _x11.XFlush(self.display)
