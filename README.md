# ProMusic - 极简音乐播放器

> 厌倦了网络音乐APP里铺天盖地的广告和无止境的功能膨胀？  
> ProMusic 只做一件事：**干干净净地放歌。**

## 为什么写这个

主流音乐APP越来越臃肿——开屏广告、信息流推荐、直播入口、社区动态、商城……打开APP想听首歌，得先划过三屏广告。  

ProMusic 的出发点很简单：**把那些乱七八糟的东西全砍掉，只保留"听音乐"这一件事。**

- 没有广告
- 没有推荐算法
- 没有社交功能
- 没有商城
- 没有开屏弹窗
- 没有VIP弹窗

**就是一个音乐播放器。**

## 功能

| 功能 | 说明 |
|------|------|
| 本地播放 | 扫描手机本地音乐，按文件夹或全盘扫描 |
| 文件夹扫描 | 指定文件夹，可选是否包含子文件夹 |
| 网络歌单 | 通过URL加载JSON格式的网络歌单 |
| 后台播放 | 前台服务保活，切到后台继续放 |
| 播放模式 | 顺序循环 / 单曲循环 / 随机播放 |
| 列表管理 | 保存/打开本地播放列表，随时切换 |
| 曲目编辑 | 列表内删除曲目（不删源文件），添加单个文件 |

## 截图

*(待补充)*

## 技术栈

- **语言**: Java 11
- **最低SDK**: Android 10 (API 29)
- **播放**: MediaPlayer + Foreground Service
- **文件访问**: Storage Access Framework (SAF)
- **UI**: Material Design + RecyclerView

## 编译

```bash
# 使用 Gradle Wrapper 编译
./gradlew assembleDebug

# 生成的 APK 位于：
# app/build/outputs/apk/debug/app-debug.apk
```

## 项目结构

```
app/src/main/java/com/osmbbt/promusic/
├── model/          # 数据模型 (Track, Playlist)
├── library/        # 音乐扫描与歌单加载
│   ├── LocalMediaScanner.java   # MediaStore 全盘扫描
│   ├── FolderScanner.java       # SAF 文件夹扫描
│   ├── NetworkPlaylistLoader.java  # 网络歌单加载
│   ├── PlaylistManager.java     # 播放列表管理
│   └── PlaylistStorage.java     # 列表持久化
├── playback/       # 播放服务
│   ├── MusicService.java         # 前台绑定服务
│   └── PlaybackListener.java     # 回调接口
└── ui/             # 界面
    ├── MainActivity.java         # 主界面
    └── TrackAdapter.java         # 列表适配器
```

## License

MIT
