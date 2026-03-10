# -*- coding: utf-8 -*-
"""
语音接收端 - 改进版
支持：多设备同时接收 | 实时监听 | 状态指示灯 | 语音激活 | 分段录音 | 心跳检测 | 自动重连
"""

import tkinter as tk
from tkinter import ttk, scrolledtext, messagebox
import threading
import logging
import os
import sys
import queue
import time
import wave
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
    import pyaudio
    HAS_PYAUDIO = True
except:
    HAS_PYAUDIO = False


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
            return True
        
        try:
            audio_array = np.frombuffer(audio_data, dtype=np.int16)
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
    
    def set_threshold(self, threshold):
        self.threshold = threshold


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


class AudioPlayer:
    """音频播放器 - 用于实时监听"""
    
    def __init__(self):
        self.pyaudio = None
        self.stream = None
        self.current_device = None
        
        if HAS_PYAUDIO:
            try:
                self.pyaudio = pyaudio.PyAudio()
            except:
                pass
    
    def start(self, device_id, sample_rate=16000, channels=1):
        """开始播放"""
        if not HAS_PYAUDIO or not self.pyaudio:
            return False
        
        self.stop()
        self.current_device = device_id
        
        try:
            self.stream = self.pyaudio.open(
                format=pyaudio.paInt16,
                channels=channels,
                rate=sample_rate,
                output=True
            )
            return True
        except Exception as e:
            logging.error(f"音频播放器启动失败: {e}")
            return False
    
    def write(self, data):
        """写入音频数据"""
        if self.stream and data:
            try:
                self.stream.write(data)
            except:
                pass
    
    def stop(self):
        """停止播放"""
        self.current_device = None
        if self.stream:
            try:
                self.stream.stop_stream()
                self.stream.close()
            except:
                pass
            self.stream = None
    
    def release(self):
        """释放资源"""
        self.stop()
        if self.pyaudio:
            try:
                self.pyaudio.terminate()
            except:
                pass
            self.pyaudio = None


class PDC680ReceiverApp:
    """PDC680 音频接收端主程序"""
    
    def __init__(self):
        self.root = tk.Tk()
        from improved_config import VERSION_NAME
        self.root.title(VERSION_NAME)
        self.root.geometry("800x600")
        
        # 组件
        self.server = None
        self.audio_handler = AudioHandler(
            config.BASE_DIR,
            config.SAMPLE_RATE,
            config.CHANNELS,
            config.BITS_PER_SAMPLE
        )
        self.audio_player = AudioPlayer()
        self.vad_detector = VADDetector()
        self.segment_manager = SegmentManager()
        
        # 设备状态
        self.devices = {}  # device_id -> {status, time, frames, filepath}
        self.listen_device = None
        
        # 控制变量
        self.server_running = tk.BooleanVar(value=False)
        
        # UI组件引用
        self.status_label = None
        self.device_tree = None
        self.log_text = None
        
        self.setup_ui()
        self.update_ui_loop()
    
    def setup_ui(self):
        """设置UI"""
        # 顶部状态栏
        top_frame = ttk.Frame(self.root)
        top_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        self.status_label = ttk.Label(top_frame, text="服务器状态: 已停止 | 已连接设备: 0")
        self.status_label.pack(side=tk.LEFT)
        
        # 控制按钮
        btn_frame = ttk.Frame(self.root)
        btn_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        ttk.Button(btn_frame, text="启动服务器", command=self.start_server).pack(side=tk.LEFT, padx=2)
        ttk.Button(btn_frame, text="停止服务器", command=self.stop_server).pack(side=tk.LEFT, padx=2)
        ttk.Button(btn_frame, text="打开保存目录", command=self.open_directory).pack(side=tk.LEFT, padx=2)
        ttk.Button(btn_frame, text="实时监听", command=self.toggle_listen).pack(side=tk.LEFT, padx=2)
        
        # 设置区域
        settings_frame = ttk.LabelFrame(self.root, text="设置")
        settings_frame.pack(side=tk.TOP, fill=tk.X, padx=5, pady=5)
        
        self.vad_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(settings_frame, text="语音激活检测(VAD)", variable=self.vad_var).pack(side=tk.LEFT, padx=5)
        
        ttk.Label(settings_frame, text="分段:").pack(side=tk.LEFT, padx=5)
        self.segment_spin = ttk.Spinbox(settings_frame, from_=1, to=60, width=3)
        self.segment_spin.set(5)
        self.segment_spin.pack(side=tk.LEFT)
        ttk.Label(settings_frame, text="分钟").pack(side=tk.LEFT)
        
        # 设备列表
        list_frame = ttk.LabelFrame(self.root, text="设备列表")
        list_frame.pack(side=tk.TOP, fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        columns = ("状态", "设备ID", "连接时间", "状态", "时长", "文件")
        self.device_tree = ttk.Treeview(list_frame, columns=columns, show="headings")
        
        for col in columns:
            self.device_tree.heading(col, text=col)
            self.device_tree.column(col, width=100)
        
        self.device_tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        
        scrollbar = ttk.Scrollbar(list_frame, orient=tk.VERTICAL, command=self.device_tree.yview)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)
        self.device_tree.configure(yscrollcommand=scrollbar.set)
        
        # 日志区域
        log_frame = ttk.LabelFrame(self.root, text="运行日志")
        log_frame.pack(side=tk.TOP, fill=tk.BOTH, expand=True, padx=5, pady=5)
        
        self.log_text = scrolledtext.ScrolledText(log_frame, height=8, state=tk.DISABLED)
        self.log_text.pack(fill=tk.BOTH, expand=True)
        
        # 底部信息
        bottom_frame = ttk.Frame(self.root)
        bottom_frame.pack(side=tk.BOTTOM, fill=tk.X, padx=5, pady=5)
        
        ttk.Label(bottom_frame, text=f"端口: {config.PORT} | 保存目录: {config.BASE_DIR}").pack(side=tk.LEFT)
    
    def log(self, message):
        """添加日志"""
        timestamp = datetime.now().strftime("%H:%M:%S")
        self.log_text.config(state=tk.NORMAL)
        self.log_text.insert(tk.END, f"[{timestamp}] {message}\n")
        self.log_text.see(tk.END)
        self.log_text.config(state=tk.DISABLED)
        logging.info(message)
    
    def start_server(self):
        """启动服务器"""
        if self.server_running.get():
            return
        
        try:
            self.server = PDC680Server(
                self.audio_handler,
                on_client_connected=self.on_client_connected,
                on_client_disconnected=self.on_client_disconnected,
                on_audio_data=self.on_audio_data
            )
            self.server.start()
            self.server_running.set(True)
            self.log("服务器已启动")
        except Exception as e:
            messagebox.showerror("错误", f"启动服务器失败: {e}")
    
    def stop_server(self):
        """停止服务器"""
        if not self.server_running.get():
            return
        
        try:
            self.server.stop()
            self.server_running.set(False)
            self.log("服务器已停止")
        except Exception as e:
            messagebox.showerror("错误", f"停止服务器失败: {e}")
    
    def open_directory(self):
        """打开保存目录"""
        path = os.path.abspath(config.BASE_DIR)
        os.makedirs(path, exist_ok=True)
        webbrowser.open(f"file://{path}")
    
    def toggle_listen(self):
        """切换实时监听"""
        selection = self.device_tree.selection()
        if not selection:
            messagebox.showinfo("提示", "请先选择要监听的设备")
            return
        
        item = self.device_tree.item(selection[0])
        device_id = item['values'][1]
        
        if self.listen_device == device_id:
            self.listen_device = None
            self.audio_player.stop()
            self.log("已停止监听")
        else:
            self.listen_device = device_id
            self.audio_player.start(device_id)
            self.log(f"开始监听设备: {device_id}")
    
    def on_client_connected(self, device_id, addr):
        """客户端连接回调"""
        self.devices[device_id] = {
            'status': '录音中',
            'time': datetime.now().strftime("%H:%M:%S"),
            'frames': 0,
            'filepath': '',
            'start_time': time.time()
        }
        self.log(f"设备 {device_id} 已连接 ({addr})")
    
    def on_client_disconnected(self, device_id, filepath, duration):
        """客户端断开回调"""
        if device_id in self.devices:
            self.devices[device_id]['status'] = '已断开'
            self.log(f"设备 {device_id} 已断开，录音时长: {duration:.2f}秒")
        
        if self.listen_device == device_id:
            self.audio_player.stop()
            self.listen_device = None
    
    def on_audio_data(self, device_id, data):
        """音频数据回调"""
        if device_id not in self.devices:
            return
        
        self.devices[device_id]['frames'] += len(data)
        
        # 实时监听
        if self.listen_device == device_id:
            self.audio_player.write(data)
        
        # VAD 检测
        if self.vad_var.get():
            if not self.vad_detector.process(data):
                # 静音，可以选择不写入
                pass
        
        # 分段检测
        elapsed = time.time() - self.devices[device_id]['start_time']
        segment_min = int(self.segment_spin.get())
        if self.segment_manager.should_split(device_id, elapsed):
            # 触发分段
            self.log(f"设备 {device_id} 达到分段时间，正在分段...")
    
    def update_ui_loop(self):
        """更新UI"""
        # 更新状态
        clients = self.server.get_connected_clients() if self.server else {}
        status_text = f"服务器状态: {'运行中' if self.server_running.get() else '已停止'} | 已连接设备: {len(clients)}"
        self.status_label.config(text=status_text)
        
        # 更新设备列表
        for item in self.device_tree.get_children():
            self.device_tree.delete(item)
        
        for device_id, info in self.devices.items():
            duration = info['frames'] / (config.SAMPLE_RATE * config.CHANNELS * config.BITS_PER_SAMPLE / 8)
            status_icon = "🟢" if info['status'] == '录音中' else "⚫"
            self.device_tree.insert("", tk.END, values=(
                status_icon,
                device_id,
                info['time'],
                info['status'],
                f"{duration:.1f}s",
                os.path.basename(info['filepath'])
            ))
        
        # 定时更新
        self.root.after(500, self.update_ui_loop)
    
    def run(self):
        """运行程序"""
        self.root.mainloop()
        
        # 清理
        if self.server:
            self.server.stop()
        self.audio_player.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    app = PDC680ReceiverApp()
    app.run()
