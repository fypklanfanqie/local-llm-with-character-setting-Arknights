# 资源目录说明（app/src/main/assets）

本目录用于存放立绘、角色语音与本地 BGM。Android 端没有微信云存储，
原小程序的 `cloud://` 资源在移植时被映射为本地 `assets/` 或公网 CDN。

## 目录结构

```
app/src/main/assets/
├── picture/          # 干员立绘（webp / png，文件名见 config/AssetPaths.kt）
│   ├── 立绘_阿米娅_2.webp
│   ├── 立绘_羽毛笔_skin1.webp
│   └── ...
├── music/            # 角色语音（wav）与本地 BGM（mp3）
│   ├── 任命助理.wav
│   ├── 干员报到.wav
│   ├── m_sys_title_combine.mp3
│   └── ...
└── background/       # 背景轮播图（webp / jpg）
```

## 资源优先级（见 AssetRepository.kt）

1. 若 `config/AppConfig.kt` 的 `ASSET_CDN_BASE` 已配置公网地址 → 拼接 CDN URL（推荐服务器托管）
2. 否则使用本地 `assets/`（即 `file:///android_asset/...`）

> 上传立绘/音乐到任意公网 CDN 后，只需在 `AppConfig.ASSET_CDN_BASE`
> 填入地址（如 `https://your-cdn.com/arknights`），即可全量生效，
> 无需打包进 APK（减小安装包体积，也避免版权资源随包分发）。

## 立绘/语音文件名

严格对应 `config/AssetPaths.kt` 中的 `PICTURES` / `SELECTION_PICTURES` / `VOICES`。
缺失的文件在 UI 中会显示风格化占位图 / 静默跳过，不会导致崩溃。

## 本地 BGM 文件名

对应 `config/AssetPaths.kt` 中 `BGM` 列表里 `music/*.mp3` 项。
网易云外链（music.163.com）多数已失效，已从默认列表移除；
如需在线 BGM，请将 mp3 放入 `music/` 目录或改用稳定的 CDN 直链。
