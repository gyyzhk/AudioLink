# AudioLink 语音接收端配置
VERSION = "v02"
VERSION_NAME = "AudioLink 语音接收端 v02"

HOST = "0.0.0.0"  # 监听地址
PORT = 8080       # 监听端口

# 存储配置
BASE_DIR = "received"  # 基础存储目录
AUDIO_FORMAT = "wav"  # 音频格式

# 音频参数
SAMPLE_RATE = 16000   # 采样率
CHANNELS = 1          # 声道数
BITS_PER_SAMPLE = 16  # 位深

# 握手协议
HANDSHAKE_MAGIC = "AUDILINK"
HANDSHAKE_SIZE = 64
