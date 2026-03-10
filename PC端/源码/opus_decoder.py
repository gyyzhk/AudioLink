# -*- coding: utf-8 -*-
"""
OPUS解码器 - 用于PC端解码接收到的OPUS音频
"""

import os
import sys
import logging

logger = logging.getLogger(__name__)

# 尝试导入opus库
try:
    import opuslib
    HAS_OPUS = True
except ImportError:
    HAS_OPUS = False
    logger.warning("opuslib未安装，OPUS解码将不可用。请运行: pip install opuslib")


class OpusDecoder:
    """OPUS解码器"""
    
    def __init__(self, sample_rate=16000, channels=1):
        self.sample_rate = sample_rate
        self.channels = channels
        self.decoder = None
        self.enabled = HAS_OPUS
        
        if HAS_OPUS:
            try:
                self.decoder = opuslib.Decoder(sample_rate, channels)
                logger.info("OPUS解码器初始化成功")
            except Exception as e:
                logger.error(f"OPUS解码器初始化失败: {e}")
                self.enabled = False
        else:
            logger.warning("OPUS解码器不可用，将回退到PCM模式")
    
    def decode(self, """解码OP opus_data):
       US数据为PCM数据"""
        if not self.enabled or self.decoder is None:
            # 如果解码器不可用，返回原始数据
            return opus_data
        
        try:
            pcm_data = self.decoder.decode(opus_data, frame_size=len(opus_data), channels=self.channels)
            return pcm_data
        except Exception as e:
            logger.error(f"OPUS解码错误: {e}")
            return opus_data  # 解码失败返回原始数据
    
    def decode_float(self, opus_data):
        """解码OPUS数据为32位浮点PCM"""
        if not self.enabled or self.decoder is None:
            return None
        
        try:
            pcm_data = self.decoder.decode_float(opus_data, frame_size=len(opus_data), channels=self.channels)
            return pcm_data
        except Exception as e:
            logger.error(f"OPUS解码错误: {e}")
            return None
    
    def release(self):
        """释放解码器"""
        if self.decoder:
            try:
                self.decoder.destroy()
            except:
                pass
            self.decoder = None
