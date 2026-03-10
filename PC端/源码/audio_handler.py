import os
import struct
import wave
import datetime
import logging
from threading import Lock
from config import HANDSHAKE_MAGIC, HANDSHAKE_SIZE

logger = logging.getLogger(__name__)


class AudioHandler:
    """音频数据处理器"""
    
    def __init__(self, base_dir, sample_rate=16000, channels=1, bits_per_sample=16):
        self.base_dir = base_dir
        self.sample_rate = sample_rate
        self.channels = channels
        self.bits_per_sample = bits_per_sample
        self.devices = {}  # device_id -> device_info
        self.lock = Lock()
        
        # 确保基础目录存在
        os.makedirs(base_dir, exist_ok=True)
    
    def parse_handshake(self, data):
        """
        解析握手包
        格式: PDC680|DeviceID|时间戳|
        返回: (device_id, timestamp) 或 None
        """
        if len(data) < HANDSHAKE_SIZE:
            return None
        
        try:
            # 解码并清理
            handshake = data[:data.find(b'\x00')].decode('utf-8', errors='ignore')
            parts = handshake.split('|')
            
            if len(parts) >= 3 and parts[0] == HANDSHAKE_MAGIC:
                device_id = parts[1]
                timestamp = parts[2]
                return device_id, timestamp
        except Exception as e:
            logger.error(f"握手包解析失败: {e}")
        
        return None
    
    def get_device_dir(self, device_id):
        """获取设备目录"""
        device_dir = os.path.join(self.base_dir, device_id)
        os.makedirs(device_dir, exist_ok=True)
        return device_dir
    
    def start_recording(self, device_id):
        """开始录音"""
        with self.lock:
            if device_id not in self.devices:
                device_dir = self.get_device_dir(device_id)
                timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                filename = f"audio_{timestamp}.wav"
                filepath = os.path.join(device_dir, filename)
                
                # 创建WAV文件
                wf = wave.open(filepath, 'wb')
                wf.setnchannels(self.channels)
                wf.setsampwidth(self.bits_per_sample // 8)
                wf.setframerate(self.sample_rate)
                
                self.devices[device_id] = {
                    'file': wf,
                    'filepath': filepath,
                    'start_time': datetime.datetime.now(),
                    'frames': 0
                }
                logger.info(f"设备 {device_id} 开始录音: {filepath}")
                
                return filepath
            else:
                # 继续录音
                return self.devices[device_id]['filepath']
    
    def write_audio(self, device_id, data):
        """写入音频数据"""
        with self.lock:
            if device_id in self.devices:
                wf = self.devices[device_id]['file']
                wf.writeframes(data)
                self.devices[device_id]['frames'] += len(data)
                return True
            else:
                # 没有握手，先忽略
                return False
    
    def stop_recording(self, device_id):
        """停止录音"""
        with self.lock:
            if device_id in self.devices:
                device_info = self.devices[device_id]
                wf = device_info['file']
                filepath = device_info['filepath']
                frames = device_info['frames']
                
                wf.close()
                duration = frames / (self.sample_rate * self.channels * self.bits_per_sample / 8)
                
                logger.info(f"设备 {device_id} 停止录音: {filepath}, 时长: {duration:.2f}秒")
                
                del self.devices[device_id]
                return filepath, duration
            return None, 0
    
    def get_active_devices(self):
        """获取活跃设备列表"""
        with self.lock:
            return list(self.devices.keys())
    
    def get_device_info(self, device_id):
        """获取设备信息"""
        with self.lock:
            if device_id in self.devices:
                info = self.devices[device_id].copy()
                info['filepath'] = os.path.basename(info['filepath'])
                info['duration'] = info['frames'] / (self.sample_rate * self.channels * self.bits_per_sample / 8)
                del info['file']  # 移除文件对象
                return info
            return None
    
    def close_all(self):
        """关闭所有设备"""
        with self.lock:
            for device_id in list(self.devices.keys()):
                self.stop_recording(device_id)
