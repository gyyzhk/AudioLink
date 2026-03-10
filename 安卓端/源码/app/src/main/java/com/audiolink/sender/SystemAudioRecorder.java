package com.audiolink.sender;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

public class SystemAudioRecorder {
    private static final String TAG = "SystemAudioRecorder";
    
    private int sampleRate;
    private int channelConfig;
    private int audioFormat;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private AudioRecordCallback callback;
    private Thread recordingThread;
    private int bufferSize;
    private Handler mainHandler;
    
    // VAD 相关
    private boolean vadEnabled = false;
    private int vadThreshold = Constants.DEFAULT_THRESHOLD;
    private int silenceFrames = 0;
    private boolean isSpeaking = false;
    
    public interface AudioRecordCallback {
        void onAudioData(byte[] data, int size);
        void onError(int errorCode);
    }
    
    public SystemAudioRecorder(int sampleRate, int channelConfig, int audioFormat) {
        this.sampleRate = sampleRate;
        this.channelConfig = channelConfig;
        this.audioFormat = audioFormat;
        
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            bufferSize = Constants.FRAME_SIZE * 2;
        }
        
        mainHandler = new Handler(Looper.getMainLooper());
    }
    
    public void setCallback(AudioRecordCallback callback) {
        this.callback = callback;
    }
    
    public void setMediaProjection(Object projection) {
        // 系统音频录制需要更高API，暂不实现
        Log.d(TAG, "系统音频录制功能需要更多配置");
    }
    
    public void setVadEnabled(boolean enabled) {
        this.vadEnabled = enabled;
    }
    
    public void setVadThreshold(int threshold) {
        this.vadThreshold = threshold;
    }
    
    @SuppressLint("MissingPermission")
    public boolean init() {
        // 使用麦克风录音作为后备
        try {
            audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            );
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败");
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(-1));
                }
                return false;
            }
            
            Log.d(TAG, "麦克风录音器初始化成功 (系统音频模式)");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "AudioRecord 初始化异常: " + e.getMessage());
            if (callback != null) {
                mainHandler.post(() -> callback.onError(-2));
            }
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
                    if (vadEnabled) {
                        if (isVoiceActive(buffer, read)) {
                            silenceFrames = 0;
                            if (!isSpeaking) {
                                isSpeaking = true;
                                Log.d(TAG, "检测到声音");
                            }
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
                        }
                    } else {
                        if (callback != null) {
                            byte[] data = new byte[read];
                            System.arraycopy(buffer, 0, data, 0, read);
                            callback.onAudioData(data, read);
                        }
                    }
                }
            }
        }, "SystemAudioRecordThread");
        
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
}
