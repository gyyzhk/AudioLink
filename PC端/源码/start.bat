@echo off
chcp 65001 >nul
title PDC680 音频接收端

echo ========================================
echo    PDC680 音频接收端
echo ========================================
echo.

python -c "import tkinter" 2>nul
if errorlevel 1 (
    echo 错误: 未安装 Python 或 tkinter
    echo 请先安装 Python 3.x
    pause
    exit /b 1
)

echo 启动图形界面...
python gui.py

pause
