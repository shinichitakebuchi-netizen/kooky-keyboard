# 綴 コンセプト動画のBGM（36秒）：琴のような撥弦＋りん＋低いドローン。都節音階（ミ・ファ・ラ・シ・ド）
import numpy as np
from scipy.signal import fftconvolve, butter, sosfilt
import wave, sys

SR = 44100
DUR = 36.0
N = int(SR * DUR)
mix = np.zeros((N, 2))
rng = np.random.default_rng(7)

def hz(n):  # 音名→周波数
    names = {"C": -9, "D": -7, "E": -5, "F": -4, "G": -2, "A": 0, "B": 2}
    return 440 * 2 ** ((names[n[0]] + 12 * (int(n[1:]) - 4)) / 12)

def put(sig, t, pan=0.0, gain=1.0):
    i = int(t * SR)
    if i >= N: return
    sig = sig[: N - i] * gain
    l = np.cos((pan + 1) * np.pi / 4); r = np.sin((pan + 1) * np.pi / 4)
    mix[i:i + len(sig), 0] += sig * l
    mix[i:i + len(sig), 1] += sig * r

def koto(f, dur=3.0, bright=1.0):
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for k in range(1, 14):
        fk = f * k * (1 + 0.0004 * k * k)
        if fk > 15000: break
        a = (1 / k ** 1.25) * (1 + 0.6 * np.sin(k * 1.3)) * (bright if k > 3 else 1)
        s += a * np.exp(-t * (1.6 + 0.85 * k)) * np.sin(2 * np.pi * fk * t + rng.uniform(0, 6.28))
    # 爪の当たる音
    n = rng.normal(0, 1, int(SR * 0.012)) * np.linspace(1, 0, int(SR * 0.012)) ** 2
    s[: len(n)] += n * 0.25
    s *= np.minimum(1, t / 0.002)
    return s / 3.2

def rin(f=698.0, dur=6.0):
    t = np.arange(int(SR * dur)) / SR
    s = np.zeros_like(t)
    for r, a, d in [(1, 1.0, .55), (1.003, .6, .5), (2.71, .45, .9), (2.716, .3, .95), (5.15, .22, 1.6), (8.43, .1, 2.6)]:
        s += a * np.exp(-t * d) * np.sin(2 * np.pi * f * r * t)
    s *= np.minimum(1, t / 0.004)
    return s / 4.5

# ---- ドローン（ずっと低く鳴っている） ----
t = np.arange(N) / SR
drone = (np.sin(2 * np.pi * hz("E2") * t) * .5 + np.sin(2 * np.pi * hz("B2") * t) * .3 + np.sin(2 * np.pi * hz("E3") * t) * .12)
drone *= 0.55 + 0.45 * np.sin(2 * np.pi * t / 9.0) ** 2
env = np.clip((t - 2.2) / 3.0, 0, 1) * np.clip((35.5 - t) / 3.0, 0, 1)
drone *= env * 0.06
mix[:, 0] += drone; mix[:, 1] += drone

# ---- 一枚目：線が引かれる → 糸目4つ → 「綴」 ----
put(rin(698), 0.35, 0, .9)
for i, n in enumerate(["E4", "F4", "A4", "B4"]):
    put(koto(hz(n), 2.5), 1.45 + i * .2, -.3 + i * .2, .55)
put(koto(hz("E3"), 4.0), 2.3, -.1, .8); put(koto(hz("B3"), 4.0), 2.32, .1, .45)

# ---- 二枚目：言葉を、綴る。（1文字ずつ） ----
for i, n in enumerate(["E5", "C5", "B4", "A4", "F4", "E4", "B3"]):
    put(koto(hz(n), 2.2, .7), 4.4 + i * .14 + (0.14 if i > 3 else 0), .25 - i * .07, .32)
put(koto(hz("A3"), 3.5), 5.7, 0, .45)

# ---- 使っているところ：ゆっくりした繰り返し（80BPM）＋場面の切り替えにりん ----
beat = 0.75
pat = [["E4", "B4", "A4", "F4"], ["E4", "A4", "B4", "C5"], ["B4", "A4", "F4", "E4"], ["E4", "F4", "A4", "B4"]]
bass = ["E3", "A2", "B2", "E3", "F3", "E3", "A2", "B2"]
tt = 8.0; bar = 0
while tt < 29.4:
    for j, n in enumerate(pat[bar % 4]):
        x = tt + j * beat
        if x >= 29.4: break
        put(koto(hz(n), 2.4, .8), x, (-.35, .2, -.1, .35)[j], .3 if j else .38)
    put(koto(hz(bass[bar % len(bass)]), 3.5, .6), tt, 0, .5)
    tt += 4 * beat; bar += 1
for sc in [8.0, 11.0, 14.5, 17.5, 20.5, 23.5, 26.5]:
    put(rin(1046.5 if sc != 8.0 else 698, 4.0), sc, .2, .25)

# ---- 終わり：糸目 → 「綴」 → 静かに ----
put(rin(698), 30.3, 0, .9)
for i, n in enumerate(["E4", "F4", "A4", "B4"]):
    put(koto(hz(n), 2.5), 31.1 + i * .15, -.3 + i * .2, .5)
put(koto(hz("E3"), 5.0), 31.6, -.1, .85); put(koto(hz("B3"), 5.0), 31.62, .1, .5); put(koto(hz("E4"), 5.0), 31.64, .15, .3)
put(koto(hz("A4"), 3.0, .6), 33.0, .3, .22); put(koto(hz("E5"), 3.0, .5), 33.6, -.2, .16)

# ---- 響き（簡単な残響） ----
L = int(SR * 2.8)
ir_t = np.arange(L) / SR
ir = np.stack([rng.normal(0, 1, L), rng.normal(0, 1, L)], 1) * np.exp(-ir_t * 2.4)[:, None]
sos = butter(2, 5000, "low", fs=SR, output="sos")
ir = sosfilt(sos, ir, axis=0); ir /= np.sqrt((ir ** 2).sum(0))
wet = np.stack([fftconvolve(mix[:, c], ir[:, c])[:N] for c in range(2)], 1)
outp = mix * 0.8 + wet * 0.45
# 最後は消えていく
outp *= np.clip((DUR - t) / 2.0, 0, 1)[:, None]
outp *= np.minimum(1, t / 0.05)[:, None]
outp = outp / np.abs(outp).max() * 0.85
w = wave.open(sys.argv[1], "wb"); w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
w.writeframes((outp * 32767).astype(np.int16).tobytes()); w.close()
print("ok")
