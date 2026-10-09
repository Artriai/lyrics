# lyrics

Android 歌词提词应用。支持逐字高亮、日语注音、翻译和离线保存。

从 [Releases](https://github.com/Artriai/lyrics/releases/latest) 下载 `lyrics.apk`，支持 Android 8.0 及以上。

## 使用

搜索并选择歌曲，点击播放开始歌词计时。伴奏需要另外播放，可拖动歌词校准进度。

- 左右滑动切换提词、列表和搜索页。
- 点击空白处切换集中显示与全文显示，长按歌词可精细调整进度。
- 搜索结果旁的 `+` 可保存歌词，下载后可离线使用。

歌词来源：网易云音乐、QQ 音乐和 LRCLIB。

## 构建

需要 Android SDK 36、JDK 17 和 JetBrains Runtime 21。

```sh
chmod +x gradlew
./gradlew assembleDebug
```

## 开源与统计

基于 [Lyrics Plus Android](https://github.com/Artriai/lyrics-plus-android)，采用 [GPL-3.0](LICENSE) 协议。

正式版包含匿名使用统计，详见 [统计说明](docs/telemetry.md)；第三方依赖见 [第三方声明](THIRD_PARTY_NOTICES.md)。
