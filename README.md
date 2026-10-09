# lyrics

独立的歌词提词板，沿用 Lyrics Plus 的歌词布局、逐字高亮、注音、翻译和滚动动效。

## 使用

- 提词页左滑进入列表，右滑进入搜索；列表右滑、搜索左滑返回提词。
- 模糊搜索歌名、歌手或同时输入两者，选择歌曲后自动保存歌词。保存的歌曲可离线提词。
- 列表支持收藏、筛选，左右滑动条目删除；下一首按当前列表顺序切换。
- 集中模式在歌手下方显示对称的收藏、播放 / 暂停、下一首按钮；右下角设置常显；播放只控制歌词计时，不播放音频。
- 集中模式直接上下滑动调整进度，无需长按：上滑前进，下滑后退，松手恢复原播放状态。行内高度连续映射逐字时间。
- 集中模式点击歌词进入全文；全文隐藏标题和播放控件，点击某句跳转，系统返回手势或点击空白回到集中模式。全文普通上下滚动用于浏览，长按拖动沿虚线连续调整逐字进度。
- 设置只保留注音、字号、背景取色、歌词源和关于项目。关于页提供版本、项目地址、更新检查，点击出现爱心、星星和点赞特效。

伴奏由外部设备播放。不同版本的前奏 / 间奏可能不同，可用上下拖动校准。

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
匿名统计说明和第三方声明见 `docs/telemetry.md` 和 `THIRD_PARTY_NOTICES.md`。

搜索结果按匹配来源显示网易云音乐、QQ 音乐和 LRCLIB 标签，同一录音的多来源合并展示。标签表示搜索匹配来源，实际歌词源在加载时自动选择。
