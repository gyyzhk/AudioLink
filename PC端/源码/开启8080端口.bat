@echo off
chcp 65001 >nul
title 开启8080端口防火墙

echo ========================================
echo     开启8080端口防火墙规则
echo ========================================
echo.

REM 检查管理员权限
net session >nul 2>&1
if %errorLevel% neq 0 (
    echo [错误] 需要管理员权限！
    echo 请右键选择"以管理员身份运行"
    pause
    exit /b 1
)

echo [1/2] 删除旧规则（如果存在）...
netsh advfirewall firewall delete rule name="AudioLink Receiver 8080" >nul 2>&1
echo        完成

echo [2/2] 添加新规则...
netsh advfirewall firewall add rule name="AudioLink Receiver 8080" dir=in action=allow protocol=TCP localport=8080
echo        完成

echo.
echo ========================================
echo     端口8080已开启！
echo ========================================
echo.
echo 规则说明:
echo   名称: AudioLink Receiver 8080
echo   协议: TCP
echo   端口: 8080
echo   方向: 入站
echo.

pause
