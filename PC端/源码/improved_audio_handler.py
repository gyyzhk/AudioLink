import os
import struct
import wave
import datetime
import logging
from threading import Lock
from config import HANDSHAKE_MAGIC, HANDSHAKE_SIZE, ENCODING_PCM, ENCODING_OPUS

logger = logging.getLogger(__name__)


class AudioHandler:
    """音频数据处理器 - 支持OPUS解码"""
    
    def __init__(self, base_dir, sample_rate=16000, channels=1, bits_per_sample=16):
        self.base_dir = base_dir
        self.sample_rate = sample_rate
        self.channels = channels
        self.bits_per_sample = bits_per_sample
        self.devices = {}  # device_id -> device_info
        self.lock = Lock()
        
        # 尝试导入OPUS解码器
        try:
            from opus_decoder import OpusDecoder
            self.opus_decoder = OpusDecoder(sample_rate, channels)
            if not self.opus_decoder.enabled:
                logger.warning("OPUS解码器不可用，将只支持PCM模式")
        except Exception as e:
            logger.warning(f"无法加载OPUS解码器: {e}")
            self.opus_decoder = None
        
        # 确保基础目录存在
        os.makedirs(base_dir, exist_ok=True)
    
    def parse_handshake(self, data):
        """
        解析握手包
        格式: AUDILINK|DeviceID|Timestamp|Encoding|
        返回: (device_id, timestamp, encoding) 或 None
        """
        if len(data) < HANDSHAKE_SIZE:
            logger.warning(f"握手数据太短: {len(data)} bytes, 数据: {data[:20] if len(data) >= 20 else data}")
            return None
        
        try:
            # 解码并清理
            handshake = data[:data.find(b'\x00') if b'\x00' in data else len(data)].decode('utf-8', errors='ignore')
            logger.info(f"收到握手数据: {repr(handshake)}")
            parts = handshake.split('|')
            
            if len(parts) >= 3 and parts[0] == HANDSHAKE_MAGIC:
                device_id = parts[1]
                timestamp = parts[2]
                
                # 解析编码格式
                encoding = ENCODING_PCM
                if len(parts) >= 4 and parts[3]:
                    encoding = parts[3].strip()
                
                logger.info(f"设备 {device_id} 握手成功，编码格式: {encoding}")
                return device_id, timestamp, encoding
                
        except Exception as e:
            logger.error(f"握手包解析失败: {e}")
        
        return None
    
    def get_device_dir(self, device_id):
        """获取设备目录"""
        device_dir = os.path.join(self.base_dir, device_id)
        os.makedirs(device_dir, exist_ok=True)
        return device_dir
    
    def start_recording(self, device_id, encoding=ENCODING_PCM):
        """开始录音"""
        with self.lock:
            if device_id not in self.devices:
                device_dir = self.get_device_dir(device_id)
                timestamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
                
                # 根据编码格式设置文件名
                if encoding == ENCODING_OPUS:
                    filename = f"audio_{timestamp}_opus.wav"
                else:
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
                    'frames': 0,
                    'encoding': encoding
                }
                logger.info(f"设备 {device_id} 开始录音: {filepath}, 编码: {encoding}")
                
                return filepath
            else:
                # 继续录音
                return self.devices[device_id]['filepath']
    
    def write_audio(self, device_id, data):
        """写入音频数据"""
        with self.lock:
            if device_id in self.devices:
                device_info = self.devices[device_id]
                encoding = device_info.get('encoding', ENCODING_PCM)
                
                # 如果是OPUS编码，先解码
                if encoding == ENCODING_OPUS and self.opus_decoder:
                    try:
                        # OPUS解码
                        pcm_data = self.opus_decoder.decode(data)
                        if pcm_data:
                            wf = device_info['file']
                            wf.writeframes(pcm_data)
                            device_info['frames'] += len(pcm_data)
                            return True
                    except Exception as e:
                        logger.error(f"OPUS解码错误: {e}")
                        # 解码失败，尝试直接写入
                        wf = device_info['file']
                        wf.writeframes(data)
                        device_info['frames'] += len(data)
                        return True
                else:
                    # PCM直接写入
                    wf = device_info['file']
                    wf.writeframes(data)
                    device_info['frames'] += len(data)
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
                encoding = device_info.get('encoding', ENCODING_PCM)
                
                wf.close()
                duration = frames / (self.sample_rate * self.channels * self.bits_per_sample / 8)
                
                logger.info(f"设备 {device_id} 停止录音: {filepath}, 时长: {duration:.2f}秒, 编码: {encoding}")
                
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
        
        # 释放OPUS解码器
        if self.opus_decoder:
            self.opus_decoder.release()
