# lyrics

独立的歌词提词板，沿用 Lyrics Plus 的歌词布局、逐字高亮、注音、翻译和滚动动效。

## 使用

- 左滑歌词主界面进入列表；右滑列表返回。
- 搜索歌名或歌手，选择歌曲后自动保存歌词。保存的歌曲可离线提词。
- 列表支持收藏、筛选和删除；下一首按当前列表顺序切换。
- 顶部播放 / 暂停只控制歌词计时，不播放音频。
- 长按歌词约半秒，上下拖动校准时间：上滑前进，下滑后退。松手恢复原播放状态。
- 单击歌词仍可切换聚焦 / 全文模式，普通上下滚动仍用于浏览全文。
- 保留注音、字号、背景取色、屏幕常亮和歌词源切换。

伴奏由外部设备播放。不同版本的前奏 / 间奏可能不同，可用长按拖动校准。

## 独立发布

- 产品分支：`lyrics`；主分支保留 Lyrics Plus。
- 应用名称：`lyrics`；应用 ID：`com.lyricsplus.lyrics`，可与原版同时安装。
- 图标沿用原 L 造型，渐变改为紫色。
- 向 `lyrics` 提交后自动运行单元测试并构建 `lyrics.apk`。
- 发布标签为 `lyrics-v<version>-build-<run_number>-<attempt>`，不修改正式版 Latest。
- 应用内更新只检查 `lyrics-v` 发布通道。

## 构建

```sh
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
bash scripts/assemble-official-release.sh -PlyricsBuildNumber=1
```

使用 Android SDK 36 和 JetBrains Runtime 21（Gradle daemon），Java/Kotlin 编译目标 17。
歌词源、匹配评分、缓存、日语注音和 WebView 渲染器复用原项目实现。
匿名统计设置和第三方声明见 `docs/telemetry.md` 和 `THIRD_PARTY_NOTICES.md`。
