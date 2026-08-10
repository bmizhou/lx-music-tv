# LX Music TV - Android TV 音乐播放器

说明：本项目由AI开发，如果使用过程中遇到问题可以尝试用ai解决，以下内容也是大部分由AI生成的说明，不一定准确。

一款专为 Android TV 设计的音乐播放器，完美支持遥控器操作，参考洛雪音乐（lx-music）功能设计，支持导入洛雪 JS 播放源。

---

## 📚 参考项目

本项目在功能、交互与视觉上参考了以下开源项目，在此致谢：

| 项目 | 说明 | 地址 |
| --- | --- | --- |
| LX Music（洛雪音乐桌面版） | 核心功能参考：JS 播放源 / 多平台搜索 / 音源 SDK | https://github.com/lyswhut/lx-music-desktop |
| LX Music API Server（洛雪 API 服务器） | JS 音源协议与 SDK 参考 | https://github.com/lyswhut/lx-music-api-server |
| LX Music Flutter（洛雪音乐安卓版） | 缓存机制参考（播放缓存 / 缓存管理） | https://github.com/lyswhut/lx-music-flutter |
| music-tv（菠萝音乐） | 播放页毛玻璃布局参考 | https://github.com/boluofan/music-tv |
| Echo Music | 整体主题风格（深色 + 强调色）参考 | https://github.com/EchoMusicApp/Echo-Music |
| blbl | 侧栏交互、焦点驱动分页等参考 | https://github.com/cat3399/blbl |

---

## 📸 截图

### 主页

![主页-歌单广场](screenshots/home-playlists.png)

![主页-排行榜](screenshots/home-ranking.png)

![主页-搜索](screenshots/home-search.png)

![主页-收藏歌曲](screenshots/home-favorite-songs.png)

![主页-收藏歌单](screenshots/home-favorite-playlists.png)

![主页-左侧播放卡片](screenshots/home-player-card.png)

### 设置

![设置](screenshots/home-settings.png)

![设置-播放源管理](screenshots/home-settings-source.png)

![设置-主题](screenshots/home-settings-theme.png)

### 播放

![播放页](screenshots/player.png)

![播放页-控制栏](screenshots/player-controls.png)

---

## 🎵 功能总览

### 1. 播放源管理
- **洛雪 JS 播放源**：支持导入 lx-music 格式的 JS 播放源
- **Web 端管理**：TV 端启动 HTTP 服务器，手机/电脑浏览器打开地址即可上传、删除播放源，并配置平台生效范围
- **多源优先级**：启用顺序即优先级（越靠前越高），播放失败自动切换到下一个可用源；重启后优先级保持不变
- **平台配置**：每个源可精确指定对哪些音乐平台生效，全部不勾选 = 全平台生效

### 2. 音乐搜索
- **多平台搜索**：内置 API+ JS 源兜底
- **搜索类型**：歌曲 / 歌单
- **搜索联想**：支持首字母搜索联想
- **热门搜索**：多平台热搜接口 + 内置兜底词，始终有内容可点
- **TV 小键盘**：内置 6 列字母数字键盘，遥控器逐键输入，搜索键触发
- **三栏布局**：左键盘 / 中联想 / 右热门，焦点左→中→右严格跳转

### 3. 音乐浏览
- **歌单广场**：按平台浏览热门歌单
- **排行榜**：多平台官方榜单
- 歌单详情：封面 + 歌曲列表 + 一键播放全部

### 4. 播放体验
- **多音源解析**：JS 源优先获取直链，失败自动回退内置 API
- **播放队列**：列表播放、上下曲切换、顺序/随机/单曲循环
- **音质选择**：标准 128k / 高品质 320k / 无损 FLAC / Hi-Res（按源支持情况）
- **歌词同步**：多平台歌词接口 + LRC 解析 + 滚动高亮
- **后台播放**：前台服务 + 媒体通知常驻，返回桌面音乐继续；重新进入自动恢复播放卡片与队列
- **TV 进度条**：遥控器左右键 ±10s / 媒体快进快退 ±30s / 触摸点击定位
- **播放列表面板**：右侧队列弹窗，封面 + 序号 + 当前曲目高亮，点击切换

### 5. 收藏
- 歌曲收藏：搜索/浏览/播放页一键 ♥
- 歌单收藏：歌单搜索/广场/排行榜收藏
- 收藏页双栏（歌曲/歌单），歌单内歌曲列表页内展开，返回键退出

### 6. TV 交互细节
- **统一焦点指示**：全界面亮红边框 + 发光，遥控器焦点一目了然（无深蓝底看不清问题）
- **退出确认**：首页返回键弹窗三选（取消/退出/后台播放）
- **屏幕常亮**：播放页抑制屏保与自动锁屏
- **播放历史**：Room 持久化最近播放

---

## 🛠 技术栈

| 类别 | 选型 |
| --- | --- |
| 语言 | Kotlin 2.1.20 |
| UI | Jetpack Compose（Compose BOM 2024.10.01，Material 3） |
| 架构 | MVVM + StateFlow + ViewModel |
| 播放器 | ExoPlayer 2.19.1（含播放缓存 CacheDataSource/SimpleCache） |
| JS 引擎 | QuickJS（wang.harlon.quickjs:wrapper-android:3.2.3） |
| 网络 | OkHttp 5.4.0 / NanoHTTPD 2.3.1（HTTP 服务器） |
| 存储 | Room 2.7.2 + DataStore |
| 构建 | AGP 9.3.0 / Gradle（项目内置 wrapper） |

## 📁 项目结构

```
LXMusicTV/
├── app/src/main/java/com/lxmusic/tv/
│   ├── MainActivity.kt          # 导航 + 状态编排
│   ├── LXMusicApplication.kt
│   ├── data/
│   │   ├── database/            # Room 实体 / DAO / 迁移
│   │   ├── model/               # 领域模型（MusicSource / Song / Playlist...）
│   │   ├── source/              # SourceManagerImpl / MusicSearchService / BrowseDataService
│   │   │                         # ScriptParser / SearchSuggestEngine / KwCrypto
│   │   └── storage/             # DataStoreManager
│   ├── network/                 # HttpClient / 各平台 API（Kugou/Kuwo/QQ/Netease/NeteaseWeApi）
│   ├── presentation/
│   │   ├── screen/              # MainScreen / SearchScreen / SearchResultScreen
│   │   │                         # FavoritesScreen / PlayerScreen / SourceManagementScreen / TvKeyboard
│   │   ├── component/           # FocusIndicators（统一焦点样式）/ RemoteImage
│   │   ├── lyrics/  theme/  animation/
│   │   └── ...                  
│   ├── script/                  # JavaScriptEngine（QuickJS 封装）/ SourceExecutor
│   ├── service/
│   │   ├── player/              # PlayerService（前台播放服务）
│   │   └── http/                # NanoHTTPD Web 管理服务器
│   └── viewmodel/               # MainViewModel
└── build.gradle.kts
```

## 📺 遥控器操作指南

| 场景 | 操作 |
| --- | --- |
| 全局导航 | 方向键移动焦点，确认键进入/播放，返回键返回上一级 |
| 搜索页 | 键盘区域逐个按键输入；行尾键右键→联想→热门；搜索键确认搜索 |
| 播放页（2.0） | 「下键」弹出控制栏：进度条聚焦时左右键 ±10s / 媒体键 ±30s；按键排左右导航；「上键」收起控制栏；返回键退出 |
| 播放模式 | 循环按钮循环切换 顺序→随机→单曲循环 |
| 首页返回 | 弹出退出确认（取消/退出/后台播放），「后台播放」返回桌面音乐继续 |
| 播放源管理 | 服务器开关 + 音源启用开关（带优先级徽标），Web 端上传新源 |


## 🔧 构建

### 环境要求
- Android Studio（新版）+ Android SDK 35
- JDK 17+
- Gradle 8.11.1（项目内置 wrapper）

### 步骤
1. 克隆 / 打开项目
2. 同步 Gradle
3. 连接 Android TV 设备（或电视盒子，开启 ADB）
4. `./gradlew :app:installDebug` 或 Android Studio 直接 Run

## 📄 许可证

Apache License 2.0
