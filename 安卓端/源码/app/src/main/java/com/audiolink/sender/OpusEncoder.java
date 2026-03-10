package com.audiolink.sender;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * OPUS 音频编码器
 * 使用 Android MediaCodec 进行 OPUS 编码
 */
public class OpusEncoder {
    private static final String TAG = "OpusEncoder";
    
    private static final String MIME_TYPE = "audio/opus";
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNELS = 1;
    private static final int BIT_RATE = 24000; // 24kbps
    
    private MediaCodec encoder;
    private boolean isEncoding = false;
    private Thread encoderThread;
    private BlockingQueue<byte[]> inputQueue;
    private AudioEncodeCallback callback;
    
    private int inputBufferIndex = -1;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;
    
    public interface AudioEncodeCallback {
        void onEncodedData(byte[] data, int size);
        void onError(String error);
    }
    
    public OpusEncoder() {
        inputQueue = new ArrayBlockingQueue<>(100);
    }
    
    public void setCallback(AudioEncodeCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 初始化编码器
     */
    public boolean init() {
        try {
            MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            
            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            
            inputBuffers = encoder.getInputBuffers();
            outputBuffers = encoder.getOutputBuffers();
            
            Log.d(TAG, "OPUS编码器初始化成功");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "OPUS编码器初始化失败: " + e.getMessage());
            if (callback != null) callback.onError("编码器初始化失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 输入原始PCM数据
     */
    public void inputData(byte[] pcmData) {
        if (!isEncoding || encoder == null) return;
        
        try {
            inputQueue.offer(pcmData);
        } catch (Exception e) {
            Log.e(TAG, "输入数据失败: " + e.getMessage());
        }
    }
    
    /**
     * 开始编码
     */
    public void start() {
        if (isEncoding) return;
        
        isEncoding = true;
        encoderThread = new Thread(this::encodeLoop, "OpusEncoderThread");
        encoderThread.start();
        Log.d(TAG, "OPUS编码已启动");
    }
    
    /**
     * 停止编码
     */
    public void stop() {
        isEncoding = false;
        
        if (encoderThread != null) {
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "等待编码线程结束失败");
            }
            encoderThread = null;
        }
        
        if (encoder != null) {
            try {
                encoder.stop();
                encoder.release();
            } catch (Exception e) {
                Log.e(TAG, "释放编码器失败: " + e.getMessage());
            }
            encoder = null;
        }
        
        inputQueue.clear();
        Log.d(TAG, "OPUS编码已停止");
    }
    
    /**
     * 编码循环
     */
    private void encodeLoop() {
        while (isEncoding) {
            try {
                // 获取输入缓冲区
                inputBufferIndex = encoder.dequeueInputBuffer(10000);
                
                if (inputBufferIndex >= 0) {
                    // 从队列获取PCM数据
                    byte[] pcmData = inputQueue.poll();
                    
                    if (pcmData != null) {
                        ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                        inputBuffer.clear();
                        inputBuffer.put(pcmData);
                        
                        long presentationTimeUs = System.nanoTime() / 1000;
                        encoder.queueInputBuffer(inputBufferIndex, 0, pcmData.length, presentationTimeUs, 0);
                    } else {
                        encoder.queueInputBuffer(inputBufferIndex, 0, 0, 0, 0);
                    }
                }
                
                // 获取输出缓冲区
                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                int outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                
                if (outputBufferIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        ByteBuffer outputBuffer = outputBuffers[outputBufferIndex];
                        outputBuffer.position(bufferInfo.offset);
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                        
                        byte[] encodedData = new byte[bufferInfo.size];
                        outputBuffer.get(encodedData);
                        
                        if (callback != null) {
                            callback.onEncodedData(encodedData, bufferInfo.size);
                        }
                    }
                    
                    encoder.releaseOutputBuffer(outputBufferIndex, false);
                }
                
            } catch (Exception e) {
                Log.e(TAG, "编码错误: " + e.getMessage());
                if (callback != null) callback.onError("编码错误: " + e.getMessage());
                break;
            }
        }
    }
}
