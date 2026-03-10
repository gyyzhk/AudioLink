@echo off
chcp 65001 >nul
title 语音接收端 - AudioLink Receiver

echo ========================================
echo     AudioLink 语音接收端
echo ========================================
echo.

REM 检查Python是否安装
python --version >nul 2>&1
if errorlevel 1 (
    echo [错误] 未检测到Python，请先安装Python 3.x
    echo 下载地址: https://www.python.org/downloads/
    pause
    exit /b 1
)

REM 检查依赖
echo [1/3] 检查依赖...
pip show numpy >nul 2>&1
if errorlevel 1 (
    echo        安装numpy...
    pip install numpy
)

pip show pyaudio >nul 2>&1
if errorlevel 1 (
    echo        安装pyaudio...
    pip install pyaudio
)

REM OPUS支持 (可选)
pip show opuslib >nul 2>&1
if errorlevel 1 (
    echo        [可选] opuslib未安装，OPUS解码将不可用
) else (
    echo        [OK] opuslib已安装，支持OPUS解码
)

echo.
echo [2/3] 启动服务器...
echo [提示] 按 Ctrl+C 停止服务器
echo.

REM 启动GUI
python improved_gui.py

echo.
echo [3/3] 服务器已停止
pause
