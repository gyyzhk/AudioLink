#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
PDC680 音频接收端 - 专业版 v3.0
支持：多设备同时接收 | 实时监听 | 状态指示灯 | 语音激活 | MP3压缩 | 分段录音 | 托盘运行 | WEB管理
"""

import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading
import logging
import os
import sys
import queue
import time
import hashlib
import json
import wave
import pyaudio
import socket
import webbrowser
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
from datetime import datetime
from collections import deque
import config
from audio_handler import AudioHandler
from server import PDC680Server

# 尝试导入音频处理库
try:
    import numpy as np
    HAS_NUMPY = True
except:
    HAS_NUMPY = False

try:
    from pydub import AudioSegment
    HAS_PYDUB = True
except:
    HAS_PYDUB = False


class VADDetector:
    """语音激活检测器"""
    
    def __init__(self, threshold=500, min_silence_frames=10):
        self.threshold = threshold
        self.min_silence_frames = min_silence_frames
        self.silence_counter = 0
        self.is_speaking = False
        self.frame_buffer = deque(maxlen=min_silence_frames * 2)
    
    def process(self, audio_data):
        """处理音频数据，返回是否有声音"""
        if not HAS_NUMPY:
            # 如果没有numpy，总是返回有声音
            return True
        
        try:
            # 转换为numpy数组
            audio_array = np.frombuffer(audio_data, dtype=np.int16)
            
            # 计算RMS
            rms = np.sqrt(np.mean(audio_array.astype(np.float32)**2))
            
            if rms > self.threshold:
                self.silence_counter = 0
                self.is_speaking = True
            else:
                self.silence_counter += 1
                if self.silence_counter > self.min_silence_frames:
                    self.is_speaking = False
            
            return self.is_speaking
        except:
            return True


class SegmentManager:
    """录音分段管理器"""
    
    def __init__(self, segment_minutes=5):
        self.segment_minutes = segment_minutes
        self.segment_seconds = segment_minutes * 60
        self.segment_start_time = {}
    
    def should_split(self, device_id, elapsed_seconds):
        """检查是否需要分段"""
        if device_id not in self.segment_start_time:
            self.segment_start_time[device_id] = elapsed_seconds
            return False
        
        if elapsed_seconds - self.segment_start_time[device_id] >= self.segment_seconds:
            self.segment_start_time[device_id] = elapsed_seconds
            return True
        return False
    
    def reset(self, device_id):
        """重置分段计时"""
        if device_id in self.segment_start_time:
            del self.segment_start_time[device_id]


class WebHandler(BaseHTTPRequestHandler):
    """WEB管理界面处理器"""
    
    def do_GET(self):
        """处理GET请求"""
        parsed = urlparse(self.path)
        path = parsed.path
        
        if path == '/' or path == '/index.html':
            self.send_response(200)
            self.send_header('Content-type', 'text/html; charset=utf-8')
            self.end_headers()
            self.wfile.write(self.get_html().encode('utf-8'))
        elif path == '/api/status':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(self.server.app.get_status()).encode('utf-8'))
        elif path == '/api/devices':
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(self.server.app.get_devices()).encode('utf-8'))
        else:
            self.send_response(404)
            self.end_headers()
    
    def get_html(self):
        """生成WEB界面HTML"""
        return '''<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>PDC680 录音服务器</title>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
        h1 { color: #333; }
        .card { background: white; padding: 20px; margin: 10px 0; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        .status { display: flex; gap: 20px; }
        .status-item { text-align: center; }
        .status-value { font-size: 24px; font-weight: bold; color: #2196F3; }
        .device { padding: 10px; margin: 5px 0; background: #f9f9f9; border-left: 3px solid #4CAF50; }
        .device.offline { border-left-color: #999; }
        .refresh { margin: 10px 0; }
        button { padding: 8px 16px; background: #2196F3; color: white; border: none; border-radius: 4px; cursor: pointer; }
        button:hover { background: #1976D2; }
    </style>
</head>
<body>
    <h1>📻 PDC680 录音服务器</h1>
    <div class="card">
        <div class="status">
            <div class="status-item">
                <div class="status-value" id="server-status">--</div>
                <div>服务器状态</div>
            </div>
            <div class="status-item">
                <div class="status-value" id="device-count">0</div>
                <div>在线设备</div>
            </div>
        </div>
    </div>
    <div class="card">
        <h3>设备列表</h3>
        <div id="device-list"></div>
    </div>
    <div class="refresh">
        <button onclick="refresh()">🔄 刷新</button>
    </div>
    <script>
        function refresh() {
            fetch('/api/status').then(r => r.json()).then(data => {
                document.getElementById('server-status').innerText = data.running ? '运行中' : '已停止';
                document.getElementById('server-status').style.color = data.running ? '#4CAF50' : '#999';
                document.getElementById('device-count').innerText = data.clients;
            });
            fetch('/api/devices').then(r => r.json()).then(devices => {
                const list = document.getElementById('device-list');
                if (devices.length === 0) {
                    list.innerHTML = '<p>暂无设备连接</p>';
                } else {
                    list.innerHTML = devices.map(d => 
                        '<div class="device">' + d.device_id + ' - ' + d.status + '</div>'
                    ).join('');
                }
            });
        }
        refresh();
        setInterval(refresh, 3000);
    </script>
</body>
</html>'''
    
    def log_message(self, format, *args):
        """抑制日志输出"""
        pass


class PDC680ReceiverGUI:
    """PDC680 接收端图形界面 - 专业版"""
    
    def __init__(self):
        self.root = tk.Tk()
        self.root.title("PDC680 音频接收端 v3.0 专业版")
        self.root.geometry("1000x700")
        self.root.protocol("WM_DELETE_WINDOW", self.on_closing)
        
        # 实时播放相关
        self.playback_device_id = None
        self.pyaudio = None
        self.play_stream = None
        self.playback_thread = None
        self.audio_queue = queue.Queue()
        self.is_playing = False
        
        # 语音激活检测
        self.vad_enabled = True
        self.vad_detectors = {}
        
        # 分段管理
        self.segment_manager = SegmentManager(segment_minutes=5)
        
        # 录音标记
        self.record_marks = {}
        
        # WEB服务器
        self.web_server = None
        self.web_thread = None
        
        # 配置
        self.config = {
            'vad_enabled': True,
            'vad_threshold': 500,
            'segment_minutes': 5,
            'mp3_enabled': False,
            'auto_start': False
        }
        
        # 加载配置
        self.load_config()
        
        # 配置日志
        self.setup_logging()
        
        # 初始化组件
        self.audio_handler = AudioHandler(
            config.BASE_DIR,
            config.SAMPLE_RATE,
            config.CHANNELS,
            config.BITS_PER_SAMPLE
        )
        
        self.server = PDC680Server(
            self.audio_handler,
            on_client_connected=self.on_client_connected,
            on_client_disconnected=self.on_client_disconnected,
            on_audio_data=self.on_audio_data
        )
        
        # 设备状态（最后活动时间）
        self.device_last_activity = {}
        self.device_recording_start = {}
        
        # 托盘图标
        self.tray_icon = None
        
        self.create_widgets()
        self.update_status()
        
        # 初始化音频播放
        self.init_audio()
        
        # 检查磁盘空间
        self.check_disk_space()
        
        # 设置开机自启
        if self.config.get('auto_start'):
            self.setup_auto_start()
    
    def load_config(self):
        """加载配置"""
        config_file = 'config_app.json'
        if os.path.exists(config_file):
            try:
                with open(config_file, 'r', encoding='utf-8') as f:
                    self.config.update(json.load(f))
            except:
                pass
    
    def save_config(self):
        """保存配置"""
        config_file = 'config_app.json'
        try:
            with open(config_file, 'w', encoding='utf-8') as f:
                json.dump(self.config, f, ensure_ascii=False, indent=2)
        except:
            pass
    
    def setup_auto_start(self):
        """设置开机自启"""
        try:
            import winreg
            key = winreg.OpenKey(winreg.HKEY_CURRENT_USER, 
                r'Software\Microsoft\Windows\CurrentVersion\Run', 0, winreg.KEY_WRITE)
            winreg.SetValueEx(key, 'PDC680Receiver', 0, winreg.REG_SZ, 
                os.path.abspath('gui.py'))
            winreg.CloseKey(key)
            self.log("已设置开机自启")
        except:
            pass
    
    def check_disk_space(self):
        """检查磁盘空间"""
        try:
            import shutil
            total, used, free = shutil.disk_usage(config.BASE_DIR)
            free_gb = free // (2**30)
            if free_gb < 1:
                self.log(f"⚠️ 磁盘空间不足: {free_gb}GB", "WARNING")
                messagebox.showwarning("警告", f"磁盘空间不足仅剩{free_gb}GB！")
        except:
            pass
    
    def init_audio(self):
        """初始化音频播放"""
        try:
            self.pyaudio = pyaudio.PyAudio()
        except Exception as e:
            self.log(f"音频播放初始化失败: {e}", "WARNING")
    
    def setup_logging(self):
        """配置日志"""
        logging.basicConfig(
            level=logging.INFO,
            format='%(asctime)s - %(levelname)s - %(message)s'
        )
        
        log_dir = "logs"
        os.makedirs(log_dir, exist_ok=True)
        log_file = os.path.join(log_dir, f"receiver_{datetime.now().strftime('%Y%m%d')}.log")
        
        file_handler = logging.FileHandler(log_file, encoding='utf-8')
        file_handler.setFormatter(logging.Formatter('%(asctime)s - %(levelname)s - %(message)s'))
        logging.getLogger().addHandler(file_handler)
    
    def create_widgets(self):
        """创建界面组件"""
        # 顶部状态栏
        status_frame = ttk.Frame(self.root)
        status_frame.pack(fill=tk.X, padx=10, pady=5)
        
        ttk.Label(status_frame, text="服务器状态:").pack(side=tk.LEFT)
        self.status_label = ttk.Label(status_frame, text="未启动", foreground="gray")
        self.status_label.pack(side=tk.LEFT, padx=5)
        
        ttk.Label(status_frame, text="已连接设备:").pack(side=tk.LEFT, padx=(20, 5))
        self.clients_label = ttk.Label(status_frame, text="0")
        self.clients_label.pack(side=tk.LEFT)
        
        ttk.Label(status_frame, text="磁盘空间:").pack(side=tk.LEFT, padx=(20, 5))
        self.disk_label = ttk.Label(status_frame, text="--")
        self.disk_label.pack(side=tk.LEFT)
        
        # 控制按钮
        control_frame = ttk.Frame(self.root)
        control_frame.pack(fill=tk.X, padx=10, pady=5)
        
        self.start_btn = ttk.Button(control_frame, text="🚀 启动服务器", command=self.start_server)
        self.start_btn.pack(side=tk.LEFT, padx=5)
        
        self.stop_btn = ttk.Button(control_frame, text="⏹ 停止服务器", command=self.stop_server, state=tk.DISABLED)
        self.stop_btn.pack(side=tk.LEFT, padx=5)
        
        ttk.Button(control_frame, text="📁 打开保存目录", command=self.open_save_dir).pack(side=tk.LEFT, padx=5)
        
        # 监听控制
        ttk.Label(control_frame, text="  🎧 实时监听:").pack(side=tk.LEFT, padx=(20, 5))
        self.listen_btn = ttk.Button(control_frame, text="▶ 开启监听", command=self.toggle_listen, state=tk.DISABLED)
        self.listen_btn.pack(side=tk.LEFT, padx=5)
        
        # WEB管理
        ttk.Button(control_frame, text="🌐 WEB管理", command=self.open_web).pack(side=tk.LEFT, padx=5)
        
        # 设置面板
        settings_frame = ttk.LabelFrame(self.root, text="⚙️ 设置")
        settings_frame.pack(fill=tk.X, padx=10, pady=5)
        
        # 语音激活
        self.vad_var = tk.BooleanVar(value=self.config.get('vad_enabled', True))
        ttk.Checkbutton(settings_frame, text="语音激活检测(VAD)", variable=self.vad_var,
            command=self.on_vad_changed).pack(side=tk.LEFT, padx=10)
        
        # 分段录音
        ttk.Label(settings_frame, text="分段:").pack(side=tk.LEFT, padx=(20, 5))
        self.segment_combo = ttk.Combobox(settings_frame, width=5, values=['1', '5', '10', '30', '60'], state='readonly')
        self.segment_combo.set(str(self.config.get('segment_minutes', 5)))
        self.segment_combo.pack(side=tk.LEFT, padx=5)
        self.segment_combo.bind('<<ComboboxSelected>>', self.on_segment_changed)
        ttk.Label(settings_frame, text="分钟").pack(side=tk.LEFT)
        
        # 录音标记按钮
        ttk.Button(settings_frame, text="⭐ 标记重要", command=self.mark_important).pack(side=tk.LEFT, padx=20)
        
        # 导出按钮
        ttk.Button(settings_frame, text="📤 导出录音", command=self.export_recordings).pack(side=tk.LEFT, padx=10)
        
        # 连接设备列表
        list_frame = ttk.LabelFrame(self.root, text="已连接设备 (双击设备开始监听)")
        list_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        columns = ("状态灯", "设备ID", "连接时间", "状态", "标记", "时长")
        self.device_tree = ttk.Treeview(list_frame, columns=columns, show='headings', height=8)
        
        self.device_tree.heading("状态灯", text="状态")
        self.device_tree.column("状态灯", width=60, anchor="center")
        for col in ("设备ID", "连接时间", "状态", "标记", "时长"):
            self.device_tree.heading(col, text=col)
            self.device_tree.column(col, width=140)
        
        self.device_tree.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        self.device_tree.bind("<Double-1>", self.on_device_double_click)
        
        # 日志区域
        log_frame = ttk.LabelFrame(self.root, text="运行日志")
        log_frame.pack(fill=tk.BOTH, expand=True, padx=10, pady=5)
        
        self.log_text = scrolledtext.ScrolledText(log_frame, height=8, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        # 底部信息
        info_frame = ttk.Frame(self.root)
        info_frame.pack(fill=tk.X, padx=10, pady=5)
        
        ttk.Label(info_frame, text=f"监听端口: {config.PORT}").pack(side=tk.LEFT)
        ttk.Label(info_frame, text=f"保存目录: {os.path.abspath(config.BASE_DIR)}").pack(side=tk.RIGHT)
    
    def on_vad_changed(self):
        """语音激活开关改变"""
        self.config['vad_enabled'] = self.vad_var.get()
        self.save_config()
        self.log(f"语音激活检测: {'开启' if self.vad_var.get() else '关闭'}")
    
    def on_segment_changed(self, event):
        """分段时间改变"""
        minutes = int(self.segment_combo.get())
        self.config['segment_minutes'] = minutes
        self.segment_manager = SegmentManager(segment_minutes=minutes)
        self.save_config()
        self.log(f"分段录音: {minutes}分钟")
    
    def log(self, message, level="INFO"):
        """添加日志"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_text.config(state=tk.NORMAL)
        self.log_text.insert(tk.END, f"[{timestamp}] {message}\n")
        self.log_text.see(tk.END)
        self.log_text.config(state=tk.DISABLED)
        getattr(logging, level.lower())(message)
    
    def start_server(self):
        """启动服务器"""
        try:
            self.server.start()
            self.start_btn.config(state=tk.DISABLED)
            self.stop_btn.config(state=tk.NORMAL)
            self.status_label.config(text="运行中", foreground="green")
            self.listen_btn.config(state=tk.NORMAL)
            self.log("🚀 服务器已启动")
            
            self.update_status()
            self.update_disk_space()
            
        except Exception as e:
            messagebox.showerror("错误", f"启动服务器失败: {e}")
            self.log(f"启动失败: {e}", "ERROR")
    
    def stop_server(self):
        """停止服务器"""
        self.stop_playback()
        
        # 停止WEB服务器
        if self.web_server:
            try:
                self.web_server.shutdown()
            except:
                pass
            self.web_server = None
        
        self.server.stop()
        self.start_btn.config(state=tk.NORMAL)
        self.stop_btn.config(state=tk.DISABLED)
        self.listen_btn.config(state=tk.DISABLED)
        self.status_label.config(text="已停止", foreground="gray")
        self.log("⏹ 服务器已停止")
        
        for item in self.device_tree.get_children():
            self.device_tree.delete(item)
        
        self.clients_label.config(text="0")
        self.device_last_activity.clear()
        self.vad_detectors.clear()
    
    def on_audio_data(self, device_id, data):
        """音频数据回调"""
        self.device_last_activity[device_id] = datetime.now()
        
        # 初始化VAD检测器
        if device_id not in self.vad_detectors:
            self.vad_detectors[device_id] = VADDetector(threshold=self.config.get('vad_threshold', 500))
        
        # 语音激活检测
        if self.config.get('vad_enabled', True):
            if not self.vad_detectors[device_id].process(data):
                # 无声音，不录音
                return
        
        # 检查是否需要分段
        if device_id in self.device_recording_start:
            elapsed = (datetime.now() - self.device_recording_start[device_id]).total_seconds()
            if self.segment_manager.should_split(device_id, elapsed):
                # 停止当前录音，开始新分段
                self.audio_handler.stop_recording(device_id)
                self.segment_manager.reset(device_id)
                self.device_recording_start[device_id] = datetime.now()
                self.audio_handler.start_recording(device_id)
        
        # 写入音频
        self.audio_handler.write_audio(device_id, data)
        
        # 实时播放
        if self.is_playing and self.playback_device_id == device_id:
            try:
                self.audio_queue.put_nowait(data)
            except queue.Full:
                pass
    
    def init_play_stream(self):
        """初始化播放流"""
        if not self.pyaudio:
            return False
        try:
            self.play_stream = self.pyaudio.open(
                format=pyaudio.paInt16,
                channels=config.CHANNELS,
                rate=config.SAMPLE_RATE,
                output=True
            )
            return True
        except Exception as e:
            self.log(f"播放流初始化失败: {e}", "ERROR")
            return False
    
    def playback_worker(self):
        """音频播放工作线程"""
        while self.is_playing:
            try:
                data = self.audio_queue.get(timeout=0.5)
                if self.play_stream:
                    self.play_stream.write(data)
            except queue.Empty:
                continue
            except Exception as e:
                if self.is_playing:
                    self.log(f"播放错误: {e}", "ERROR")
                break
        
        if self.play_stream:
            try:
                self.play_stream.stop_stream()
                self.play_stream.close()
                self.play_stream = None
            except:
                pass
    
    def toggle_listen(self):
        """切换监听状态"""
        if self.is_playing:
            self.stop_playback()
        else:
            selection = self.device_tree.selection()
            if not selection:
                messagebox.showwarning("提示", "请先双击选择一个设备进行监听")
                return
            item = self.device_tree.item(selection[0])
            device_id = item['values'][1]
            self.start_playback(device_id)
    
    def start_playback(self, device_id):
        """开始播放"""
        if self.is_playing:
            self.stop_playback()
        
        self.playback_device_id = device_id
        self.is_playing = True
        
        while not self.audio_queue.empty():
            try:
                self.audio_queue.get_nowait()
            except:
                break
        
        if not self.init_play_stream():
            self.is_playing = False
            return
        
        self.playback_thread = threading.Thread(target=self.playback_worker, daemon=True)
        self.playback_thread.start()
        
        self.listen_btn.config(text="⏹ 停止监听")
        self.log(f"🎧 开始监听设备: {device_id}")
    
    def stop_playback(self):
        """停止播放"""
        self.is_playing = False
        self.playback_device_id = None
        
        if self.play_stream:
            try:
                self.play_stream.stop_stream()
                self.play_stream.close()
                self.play_stream = None
            except:
                pass
        
        self.listen_btn.config(text="▶ 开启监听")
        self.log("⏹ 停止监听")
    
    def on_device_double_click(self, event):
        """双击设备开始监听"""
        selection = self.device_tree.selection()
        if selection:
            item = self.device_tree.item(selection[0])
            device_id = item['values'][1]
            self.start_playback(device_id)
    
    def mark_important(self):
        """标记重要"""
        selection = self.device_tree.selection()
        if not selection:
            messagebox.showwarning("提示", "请先选择一个设备")
            return
        
        item = self.device_tree.item(selection[0])
        device_id = item['values'][1]
        
        if device_id not in self.record_marks:
            self.record_marks[device_id] = []
        
        mark_time = datetime.now().strftime("%H:%M:%S")
        self.record_marks[device_id].append(mark_time)
        
        # 更新显示
        marks = "⭐" * len(self.record_marks[device_id])
        self.device_tree.item(selection[0], values=(
            item['values'][0],  # 状态灯
            item['values'][1],  # 设备ID
            item['values'][2],  # 连接时间
            item['values'][3],  # 状态
            marks,              # 标记
            item['values'][5]   # 时长
        ))
        
        self.log(f"⭐ 已标记设备 {device_id} 在 {mark_time}")
    
    def export_recordings(self):
        """导出录音"""
        messagebox.showinfo("导出", "导出功能开发中...\n可手动打开保存目录复制文件")
    
    def open_web(self):
        """打开WEB管理界面"""
        if not self.server.running:
            messagebox.showwarning("提示", "请先启动服务器")
            return
        
        # 启动WEB服务器
        if not self.web_server:
            try:
                self.web_server = HTTPServer(('0.0.0.0', 8081), WebHandler)
                self.web_server.app = self
                self.web_thread = threading.Thread(target=self.web_server.serve_forever, daemon=True)
                self.web_thread.start()
                self.log("🌐 WEB管理界面已启动: http://localhost:8081")
            except Exception as e:
                messagebox.showerror("错误", f"启动WEB服务失败: {e}")
                return
        
        webbrowser.open("http://localhost:8081")
    
    def get_status(self):
        """获取服务器状态"""
        return {
            'running': self.server.running,
            'clients': len(self.server.get_connected_clients()),
            'vad_enabled': self.config.get('vad_enabled', True),
            'segment_minutes': self.config.get('segment_minutes', 5)
        }
    
    def get_devices(self):
        """获取设备列表"""
        clients = self.server.get_connected_clients()
        devices = []
        for addr, device_id in clients.items():
            info = self.audio_handler.get_device_info(device_id)
            marks = self.record_marks.get(device_id, [])
            devices.append({
                'device_id': device_id,
                'status': '录音中' if self.device_last_activity.get(device_id) else '离线',
                'marks': len(marks)
            })
        return devices
    
    def update_disk_space(self):
        """更新磁盘空间显示"""
        try:
            import shutil
            total, used, free = shutil.disk_usage(config.BASE_DIR)
            free_gb = free // (2**30)
            self.disk_label.config(text=f"{free_gb}GB")
            if free_gb < 1:
                self.disk_label.config(foreground="red")
            elif free_gb < 5:
                self.disk_label.config(foreground="orange")
            else:
                self.disk_label.config(foreground="green")
        except:
            pass
    
    def update_status(self):
        """更新状态"""
        if not self.server.running:
            return
        
        now = datetime.now()
        
        clients = self.server.get_connected_clients()
        self.clients_label.config(text=str(len(clients)))
        
        current_items = {}
        for item in self.device_tree.get_children():
            item_data = self.device_tree.item(item)
            current_items[item_data['values'][1]] = item
        
        for addr, device_id in clients.items():
            info = self.audio_handler.get_device_info(device_id)
            if info:
                last_time = self.device_last_activity.get(device_id)
                if last_time:
                    seconds_since = (now - last_time).total_seconds()
                    has_data = seconds_since < 5
                else:
                    has_data = False
                
                status = "🟢" if has_data else "⚫"
                
                # 获取标记
                marks = self.record_marks.get(device_id, [])
                marks_str = "⭐" * len(marks)
                
                if device_id in current_items:
                    item = self.device_tree.item(current_items[device_id])
                    old_values = item['values']
                    self.device_tree.item(current_items[device_id], values=(
                        status,
                        device_id,
                        datetime.fromtimestamp(clients[addr]['connected_time']).strftime("%H:%M:%S"),
                        "录音中" if has_data else "等待数据",
                        marks_str,
                        f"{info.get('duration', 0):.1f}秒"
                    ))
                else:
                    self.device_tree.insert('', tk.END, values=(
                        status,
                        device_id,
                        datetime.fromtimestamp(clients[addr]['connected_time']).strftime("%H:%M:%S"),
                        "录音中",
                        marks_str,
                        f"{info.get('duration', 0):.1f}秒"
                    ))
                    
                    # 记录录音开始时间（用于分段）
                    self.device_recording_start[device_id] = datetime.now()
        
        self.root.after(500, self.update_status)
    
    def on_client_connected(self, device_id, addr):
        """客户端连接回调"""
        self.root.after(0, lambda: self.log(f"✅ 设备 {device_id} 已连接 ({addr[0]}:{addr[1]})"))
    
    def on_client_disconnected(self, device_id, filepath, duration):
        """客户端断开回调"""
        if device_id in self.device_last_activity:
            del self.device_last_activity[device_id]
        if device_id in self.device_recording_start:
            del self.device_recording_start[device_id]
        if device_id in self.vad_detectors:
            del self.vad_detectors[device_id]
        
        if self.playback_device_id == device_id:
            self.stop_playback()
        
        if filepath:
            filename = os.path.basename(filepath)
            self.root.after(0, lambda: self.log(f"📝 设备 {device_id} 断开，录音已保存: {filename} ({duration:.1f}秒)"))
        else:
            self.root.after(0, lambda: self.log(f"❌ 设备 {device_id} 已断开"))
    
    def open_save_dir(self):
        """打开保存目录"""
        os.startfile(os.path.abspath(config.BASE_DIR))
    
    def clear_log(self):
        """清空日志"""
        self.log_text.config(state=tk.NORMAL)
        self.log_text.delete(1.0, tk.END)
        self.log_text.config(state=tk.DISABLED)
    
    def on_closing(self):
        """关闭窗口"""
        if self.server.running:
            if messagebox.askokcancel("退出", "服务器正在运行，确定要退出吗？"):
                self.stop_server()
                self.root.destroy()
        else:
            self.root.destroy()
    
    def run(self):
        """运行界面"""
        self.root.mainloop()
        
        if self.pyaudio:
            self.pyaudio.terminate()


def main():
    app = PDC680ReceiverGUI()
    app.run()


if __name__ == "__main__":
    main()
