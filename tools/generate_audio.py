"""Generate the two small local alert sounds used by the app."""
import os
import wave

import numpy as np

SR = 44100
OUT = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
    "app", "src", "main", "res", "raw",
)


def save(name, x):
    x = np.asarray(x, dtype=np.float64)
    peak = np.max(np.abs(x)) or 1.0
    x = x / peak * 0.72
    pcm = np.int16(np.clip(x, -1, 1) * 32767)
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print(name, round(len(x) / SR, 2), "s", os.path.getsize(os.path.join(OUT, name)) // 1024, "KB")


def tone(freq, dur, amp=1.0, attack=0.01, release=0.08, harm=0.25):
    n = int(SR * dur)
    t = np.arange(n) / SR
    env = np.minimum(1, t / attack) * np.minimum(1, (dur - t) / release)
    env = np.clip(env, 0, 1)
    sig = (
        np.sin(2 * np.pi * freq * t)
        + harm * np.sin(2 * np.pi * freq * 2 * t)
        + 0.12 * np.sin(2 * np.pi * freq * 3 * t)
    )
    return amp * env * sig


# Gentle two-note chime, 2.8 s. Used only once by the light notification.
n = int(SR * 2.8)
x = np.zeros(n)
for start, freq, dur, amp in [
    (0.0, 1046.5, 1.6, 0.55),
    (0.34, 1318.5, 2.1, 0.50),
    (0.34, 1568.0, 1.7, 0.25),
]:
    s = int(SR * start)
    seg = tone(freq, dur, amp, attack=0.006, release=0.5, harm=0.18)
    x[s:s + len(seg)] += seg
fade = int(SR * 0.35)
x[-fade:] *= np.linspace(1, 0, fade)
save("gentle_chime.wav", x)


# Alarm pattern, exactly 6.0 s, with a pause at the loop boundary.
n = int(SR * 6.0)
x = np.zeros(n)


def add_beep(t0, freq, dur=0.16, amp=0.8):
    s = int(SR * t0)
    seg = tone(freq, dur, amp, attack=0.004, release=0.03, harm=0.35)
    x[s:s + len(seg)] += seg


for group in range(3):
    base = group * 2.0
    for freq, offset in [(880.0, 0.0), (1174.7, 0.28), (880.0, 0.56)]:
        add_beep(base + offset, freq)

edge = int(SR * 0.01)
x[:edge] *= np.linspace(0, 1, edge)
x[-edge:] *= np.linspace(1, 0, edge)
save("alarm_tone.wav", x)
