# コンセプト動画のBGM

- 曲名：Infinite Peace
- 作者：Kevin MacLeod
- 入手元：FreePD（https://freepd.com/）※ SoundSafari/CC0-1.0-Music に収録されたもの
- ライセンス：CC0 1.0（パブリックドメイン。商用利用可・クレジット表記は不要。表記すると喜ばれる）
- 使い方：曲の頭から36秒。最初0.6秒でフェードイン、33.3秒から2.7秒でフェードアウト、-16 LUFS にそろえる

```
ffmpeg -i tsuzuri-concept.mp4 -i "Infinite Peace.mp3" -map 0:v -map 1:a -c:v copy \
  -af "silenceremove=start_periods=1:start_threshold=-45dB,atrim=0:36,afade=t=in:d=0.6,afade=t=out:st=33.3:d=2.7,loudnorm=I=-16:TP=-1.5:LRA=11" \
  -ar 48000 -c:a aac -b:a 192k -t 36 -movflags +faststart tsuzuri-concept-bgm.mp4
```

※ `bgm.py` はプログラムで作った最初のBGM案（不採用）。
