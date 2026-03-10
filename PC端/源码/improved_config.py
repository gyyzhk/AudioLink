# -*- coding: utf-8 -*-
"""
语音接收端配置文件 - 支持OPUS解码
"""

# 版本信息
VERSION = "1.0.0"
VERSION_NAME = "AudioLink 语音接收端 v1.0.0"

# 服务器配置
HOST = "0.0.0.0"
PORT = 8080
BASE_DIR = "received"

# 音频参数
SAMPLE_RATE = 16000
CHANNELS = 1
BITS_PER_SAMPLE = 16

# 握手协议
HANDSHAKE_MAGIC = "AUDILINK"
HANDSHAKE_SIZE = 64

# 支持的编码格式
ENCODING_PCM = "PCM"
ENCODING_OPUS = "OPUS"

# VAD配置
VAD_THRESHOLD = 500
VAD_MIN_SILENCE_FRAMES = 10

# 分段录音
DEFAULT_SEGMENT_MINUTES = 5

# 心跳检测
HEARTBEAT_TIMEOUT = 30

# 日志
LOG_LEVEL = "INFO"
LOG_FORMAT = "%(asctime)s - %(levelname)s - %(message)s"
