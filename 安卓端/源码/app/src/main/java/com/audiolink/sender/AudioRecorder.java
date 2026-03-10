package com.audiolink.sender;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

public class AudioRecorder {
    private static final String TAG = "AudioRecorder";
    
    private int sampleRate;
    private int channelConfig;
    private int audioFormat;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private AudioRecordCallback callback;
    private Thread recordingThread;
    private int bufferSize;
    
    // VAD 相关
    private boolean vadEnabled = false;
    private int vadThreshold = Constants.DEFAULT_THRESHOLD;
    private int silenceFrames = 0;
    private int speechFrames = 0;
    private boolean isSpeaking = false;
    
    public interface AudioRecordCallback {
        void onAudioData(byte[] data, int size);
        void onError(int errorCode);
    }
    
    public AudioRecorder(int sampleRate, int channelConfig, int audioFormat) {
        this.sampleRate = sampleRate;
        this.channelConfig = channelConfig;
        this.audioFormat = audioFormat;
        
        // 计算最小缓冲区大小
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = Constants.FRAME_SIZE * 2;
        }
        Log.d(TAG, "缓冲区大小: " + bufferSize);
    }
    
    public void setCallback(AudioRecordCallback callback) {
        this.callback = callback;
    }
    
    public void setVadEnabled(boolean enabled) {
        this.vadEnabled = enabled;
    }
    
    public void setVadThreshold(int threshold) {
        this.vadThreshold = threshold;
    }
    
    @SuppressLint("MissingPermission")
    public boolean init() {
        try {
            Log.d(TAG, "初始化AudioRecord: sampleRate=" + sampleRate + ", channelConfig=" + channelConfig + ", audioFormat=" + audioFormat);
            
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );
            
            Log.d(TAG, "AudioRecord state: " + audioRecord.getState());
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败，状态: " + audioRecord.getState());
                if (callback != null) callback.onError(-1);
                return false;
            }
            
            Log.d(TAG, "AudioRecord 初始化成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 初始化异常: " + e.getMessage());
            if (callback != null) callback.onError(-2);
            return false;
        }
    }
    
    public void startRecording() {
        if (audioRecord == null || isRecording) {
            return;
        }
        
        isRecording = true;
        audioRecord.startRecording();
        
        recordingThread = new Thread(() -> {
            byte[] buffer = new byte[bufferSize];
            
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                
                if (read > 0) {
                    // VAD 检测
                    if (vadEnabled) {
                        if (isVoiceActive(buffer, read)) {
                            speechFrames++;
                            silenceFrames = 0;
                            if (!isSpeaking) {
                                isSpeaking = true;
                                Log.d(TAG, "检测到声音");
                            }
                            // 有声音时发送
                            if (callback != null) {
                                byte[] data = new byte[read];
                                System.arraycopy(buffer, 0, data, 0, read);
                                callback.onAudioData(data, read);
                            }
                        } else {
                            silenceFrames++;
                            if (isSpeaking && silenceFrames > Constants.VAD_STOP_FRAMES) {
                                isSpeaking = false;
                                Log.d(TAG, "声音结束");
                            }
                            // 静音时也可以选择不发送，节省带宽
                        }
                    } else {
                        // 不使用VAD，直接发送
                        if (callback != null) {
                            byte[] data = new byte[read];
                            System.arraycopy(buffer, 0, data, 0, read);
                            callback.onAudioData(data, read);
                        }
                    }
                }
            }
        }, "AudioRecordThread");
        
        recordingThread.start();
    }
    
    public void stopRecording() {
        isRecording = false;
        
        if (recordingThread != null) {
            try {
                recordingThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待录音线程结束失败");
            }
            recordingThread = null;
        }
        
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (Exception e) {
                Log.e(TAG, "停止录音失败: " + e.getMessage());
            }
        }
    }
    
    public void release() {
        stopRecording();
        
        if (audioRecord != null) {
            audioRecord.release();
            audioRecord = null;
        }
    }
    
    private boolean isVoiceActive(byte[] buffer, int read) {
        // 计算RMS
        double sum = 0;
        for (int i = 0; i < read; i += 2) {
            if (i + 1 < read) {
                short sample = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xFF));
                sum += sample * sample;
            }
        }
        
        int count = read / 2;
        if (count == 0) return false;
        
        double rms = Math.sqrt(sum / count);
        int db = (int) (20 * Math.log10(rms + 1));
        
        return db > vadThreshold;
    }
    
    public int getCurrentVolume() {
        // 获取当前音量用于UI显示
        return vadThreshold;
    }
}
