# jniLibs 目录

放置 llama.cpp 预编译库 `libllama.so`，按 ABI 分子目录：

```
jniLibs/
├── arm64-v8a/
│   └── libllama.so
├── armeabi-v7a/
│   └── libllama.so
└── x86_64/
    └── libllama.so
```

放置后重新构建，APP 即可启用本地 AI 离线推理。
详见工程根目录 README.md「集成本地 AI 引擎」章节。
