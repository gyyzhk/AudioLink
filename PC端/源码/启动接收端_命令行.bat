@echo off
chcp 65001 >nul
title 语音接收端(命令行模式) - AudioLink Receiver

echo ========================================
echo     AudioLink 语音接收端 (命令行模式)
echo ========================================
echo.

REM 检查Python
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到Python
    pause
    exit /b 1
)

REM 检查依赖
echo [1/2] 检查numpy...
pip show numpy >nul 2>&1 || pip install numpy

echo [2/2] 启动服务器...
echo.
echo 服务器信息:
echo   端口: 8080
echo   保存目录: received\
echo.
echo 按 Ctrl+C 停止服务器
echo.

python -c "
import socket
import threading
import logging
import time
from improved_config import *
from improved_audio_handler import AudioHandler
from improved_server import PDC680Server

logging.basicConfig(level=logging.INFO, format=LOG_FORMAT)

# 创建音频处理器
audio_handler = AudioHandler(BASE_DIR, SAMPLE_RATE, CHANNELS, BITS_PER_SAMPLE)

# 创建并启动服务器
server = PDC680Server(audio_handler)
server.start()

print('='*40)
print('服务器已启动，监听端口 %d' % PORT)
print('按 Ctrl+C 停止服务器')
print('='*40)

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    print('\n正在停止服务器...')
    server.stop()
    print('服务器已停止')
"
