# AudioLink 语音传输系统

## 项目介绍

AudioLink 是一款局域网语音传输系统，可以将手机麦克风音频实时传输到电脑端。

## 版本

- PC接收端: v02
- 安卓发送端: v02

## 功能特点

- 实时语音传输
- 支持TCP/UDP/HTTP协议
- 支持PCM/OPUS音频编码
- 录音文件自动保存为WAV格式
- 跨平台支持

## 文件说明

```
├── PC端/
│   └── 源码/           # PC接收端Python源码
│
└── 安卓端/
    └── 源码/           # Android Studio项目
```

## 使用方法

### PC端

1. 安装Python依赖：
```
pip install numpy pyaudio opuslib
```

2. 运行接收端：
```
双击 启动接收端.bat
```

### 安卓端

1. 安装APK或用Android Studio编译
2. 配置服务器IP和端口
3. 选择协议和音频源
4. 开始录音传输

## 技术规格

- 采样率: 16000 Hz
- 声道: 单声道
- 位深: 16 bit
- 端口: 8080
