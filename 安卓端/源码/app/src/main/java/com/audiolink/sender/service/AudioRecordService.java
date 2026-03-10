package com.audiolink.sender.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import com.audiolink.sender.Constants;
import com.audiolink.sender.NetworkClient;
import com.audiolink.sender.OpusEncoder;
import com.audiolink.sender.PreferencesManager;
import com.audiolink.sender.AudioRecorder;
import com.audiolink.sender.SystemAudioRecorder;

public class AudioRecordService extends Service {
    private static final String TAG = "AudioRecordService";
    private static final int NOTIFICATION_ID = 1;
    
    // 音频源类型
    public static final int AUDIO_SOURCE_MIC = 0;
    public static final int AUDIO_SOURCE_SYSTEM = 1;
    
    private final IBinder binder = new LocalBinder();
    private AudioRecorder micRecorder;        // 麦克风录音
    private SystemAudioRecorder systemRecorder; // 系统音频录音
    private OpusEncoder opusEncoder;           // OPUS编码器
    private NetworkClient networkClient;
    private PreferencesManager prefs;
    private boolean isServiceRunning = false;
    private boolean isTransmitting = false;
    private int currentVolume = 0;
    private long totalTransmitBytes = 0;
    
    private int currentAudioSource = AUDIO_SOURCE_MIC;
    private String currentEncoding = Constants.ENCODING_PCM;
    private MediaProjectionManager projectionManager;
    private int resultCode = -1;
    private Intent resultData = null;
    
    // 网络状态
    private String networkType = "未知"; // WiFi / 4G / 无网络
    private ConnectivityManager.NetworkCallback connectivityCallback;
    
    public class LocalBinder extends Binder {
        public AudioRecordService getService() {
            return AudioRecordService.this;
        }
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = PreferencesManager.getInstance(this);
        networkClient = new NetworkClient();
        networkClient.setCallback(networkCallback);
        
        createNotificationChannel();
        registerNetworkCallback();
        updateNetworkType();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "android.media.projection.action.EXTRA_CONSENT_RESULT".equals(intent.getAction())) {
            resultCode = intent.getIntExtra("resultCode", -1);
            resultData = intent.getParcelableExtra("data");
            
            if (resultCode == -1 || resultData == null) {
                Log.e(TAG, "媒体投影授权被拒绝或无效");
                return START_NOT_STICKY;
            }
            
            Log.d(TAG, "媒体投影授权已授予");
            // 系统音频模式已在requestSystemAudioCapture后调用startSystemAudioRecording
        }
        
        startForeground(NOTIFICATION_ID, createNotification("语音采集服务", "服务运行中..."));
        isServiceRunning = true;
        
        return START_STICKY;
    }
    
    @Override
    public void onDestroy() {
        stopRecording();
        isServiceRunning = false;
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
    
    /**
     * 设置音频源
     */
    public void setAudioSource(int source) {
        this.currentAudioSource = source;
    }
    
    /**
     * 启动麦克风录音
     * @return true if started successfully
     */
    public boolean startMicRecording() {
        // 先停止之前的录音
        stopRecording();
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("语音采集服务", "正在录音..."));
        
        // 获取编码格式 (0=PCM, 1=OPUS)
        int encodingType = prefs.getAudioEncoding();
        if (encodingType == 1) {
            currentEncoding = Constants.ENCODING_OPUS;
        } else {
            currentEncoding = Constants.ENCODING_PCM;
        }
        
        String ip = prefs.getServerIp();
        int port = prefs.getServerPort();
        String protocol = prefs.getProtocol();
        String deviceId = prefs.getDeviceId();
        
        // 配置网络客户端，包含编码格式
        networkClient.configure(ip, port, protocol, deviceId, currentEncoding);
        networkClient.connect();
        
        // 创建麦克风录音器
        micRecorder = new AudioRecorder(Constants.SAMPLE_RATE, Constants.CHANNEL_CONFIG, Constants.AUDIO_FORMAT);
        micRecorder.setVadEnabled(prefs.isVadEnabled());
        
        // 如果使用OPUS编码
        if (Constants.ENCODING_OPUS.equals(currentEncoding)) {
            // 初始化OPUS编码器
            opusEncoder = new OpusEncoder();
            opusEncoder.setCallback(new OpusEncoder.AudioEncodeCallback() {
                @Override
                public void onEncodedData(byte[] data, int size) {
                    if (networkClient.isConnected()) {
                        if (networkClient.sendData(data)) {
                            isTransmitting = true;
                            totalTransmitBytes += size;
                        }
                    }
                }
                
                @Override
                public void onError(String error) {
                    Log.e(TAG, "OPUS编码错误: " + error);
                }
            });
            
            if (opusEncoder.init()) {
                opusEncoder.start();
            } else {
                Log.e(TAG, "OPUS编码器初始化失败，回退到PCM");
                currentEncoding = Constants.ENCODING_PCM;
            }
        }
        
        // 设置录音回调
        micRecorder.setCallback(new AudioRecorder.AudioRecordCallback() {
            @Override
            public void onAudioData(byte[] data, int size) {
                currentVolume = calculateVolume(data);
                
                if (Constants.ENCODING_OPUS.equals(currentEncoding) && opusEncoder != null) {
                    // OPUS模式：先编码再发送
                    opusEncoder.inputData(data);
                } else {
                    // PCM模式：直接发送
                    if (networkClient.isConnected()) {
                        if (networkClient.sendData(data)) {
                            isTransmitting = true;
                            totalTransmitBytes += size;
                        }
                    } else {
                        isTransmitting = false;
                    }
                }
            }
            
            @Override
            public void onError(int errorCode) {
                Log.e(TAG, "麦克风录音错误: " + errorCode);
            }
        });
        if (micRecorder.init()) {
            micRecorder.startRecording();
            currentAudioSource = AUDIO_SOURCE_MIC;
            isServiceRunning = true;
            Log.d(TAG, "麦克风录音已启动，编码: " + currentEncoding);
            return true;
        } else {
            Log.e(TAG, "麦克风初始化失败 - 请检查麦克风权限");
            return false;
        }
    }
    
    /**
     * 请求系统音频捕获权限
     */
    public void requestSystemAudioCapture() {
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent consentIntent = projectionManager.createScreenCaptureIntent();
        consentIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(consentIntent);
    }
    
    /**
     * 启动系统音频录音（在授权后调用）
     */
    public void startSystemAudioRecording() {
        if (resultCode == -1 || resultData == null) {
            Log.e(TAG, "没有媒体投影结果");
            return;
        }
        
        // 先停止之前的录音
        stopRecording();
        
        MediaProjection projection = projectionManager.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            Log.e(TAG, "获取媒体投影失败");
            return;
        }
        
        String ip = prefs.getServerIp();
        int port = prefs.getServerPort();
        String protocol = prefs.getProtocol();
        String deviceId = prefs.getDeviceId();
        
        networkClient.configure(ip, port, protocol, deviceId);
        networkClient.connect();
        
        // 创建系统音频录音器
        systemRecorder = new SystemAudioRecorder(Constants.SAMPLE_RATE, Constants.CHANNEL_CONFIG, Constants.AUDIO_FORMAT);
        systemRecorder.setMediaProjection(projection);
        systemRecorder.setVadEnabled(prefs.isVadEnabled());
        systemRecorder.setCallback(new SystemAudioRecorder.AudioRecordCallback() {
            @Override
            public void onAudioData(byte[] data, int size) {
                currentVolume = calculateVolume(data);
                if (networkClient.isConnected()) {
                    if (networkClient.sendData(data)) {
                        isTransmitting = true;
                        totalTransmitBytes += size;
                    }
                } else {
                    isTransmitting = false;
                }
            }
            
            @Override
            public void onError(int errorCode) {
                Log.e(TAG, "系统音频错误: " + errorCode);
            }
        });
        
        if (systemRecorder.init()) {
            systemRecorder.startRecording();
            currentAudioSource = AUDIO_SOURCE_SYSTEM;
            Log.d(TAG, "系统音频录音已启动");
        } else {
            Log.e(TAG, "系统音频初始化失败");
        }
    }
    
    /**
     * 设置媒体投影结果（从Activity传递过来）
     */
    public void setMediaProjectionResult(int code, Intent data) {
        this.resultCode = code;
        this.resultData = data;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "audio_sender_channel", 
                "语音采集服务", 
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("系统音频采集服务");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification(String title, String content) {
        Intent intent = new Intent(this, com.audiolink.sender.ui.MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE
        );
        
        String audioSourceText = (currentAudioSource == AUDIO_SOURCE_SYSTEM) ? "系统音频" : "麦克风";
        
        return new NotificationCompat.Builder(this, "audio_sender_channel")
            .setContentTitle(title)
            .setContentText(content + " [" + audioSourceText + "]")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build();
    }
    
    private void stopRecording() {
        // 停止麦克风录音
        if (micRecorder != null) {
            micRecorder.release();
            micRecorder = null;
        }
        
        // 停止系统音频录音
        if (systemRecorder != null) {
            systemRecorder.release();
            systemRecorder = null;
        }
        
        // 停止OPUS编码器
        if (opusEncoder != null) {
            opusEncoder.stop();
            opusEncoder = null;
        }
        
        // 断开网络
        if (networkClient != null) {
            networkClient.disconnect();
        }
        
        isTransmitting = false;
    }
    
    private int calculateVolume(byte[] audioData) {
        double sum = 0;
        for (int i = 0; i < audioData.length; i += 2) {
            if (i + 1 < audioData.length) {
                short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
                sum += sample * sample;
            }
        }
        double rms = Math.sqrt(sum / (audioData.length / 2));
        return (int) (rms / 10);
    }
    
    private final NetworkClient.NetworkCallback networkCallback = new NetworkClient.NetworkCallback() {
        @Override
        public void onConnected() {
            Log.d(TAG, "网络已连接");
        }
        
        @Override
        public void onDisconnected() {
            Log.d(TAG, "网络已断开");
            isTransmitting = false;
        }
        
        @Override
        public void onError(String error) {
            Log.e(TAG, "网络错误: " + error);
            isTransmitting = false;
        }
        
        @Override
        public void onDataSent(int bytes) {
            // 数据发送成功
        }
    };
    
    public void updateServerConfig(String ip, int port, String protocol) {
        prefs.setServerIp(ip);
        prefs.setServerPort(port);
        prefs.setProtocol(protocol);
        networkClient.configure(ip, port, protocol, prefs.getDeviceId());
        networkClient.disconnect();
        networkClient.connect();
    }
    
    public boolean testConnection() {
        networkClient.configure(
            prefs.getServerIp(), 
            prefs.getServerPort(), 
            prefs.getProtocol(), 
            prefs.getDeviceId()
        );
        return networkClient.connect();
    }
    
    public int getCurrentVolume() {
        return currentVolume;
    }
    
    public boolean isTransmitting() {
        return isTransmitting;
    }
    
    public boolean isServiceRunning() {
        return isServiceRunning;
    }
    
    public long getTotalTransmitBytes() {
        return totalTransmitBytes;
    }
    
    public int getCurrentAudioSource() {
        return currentAudioSource;
    }
    
    // ========== 网络状态监测 ==========
    
    private void registerNetworkCallback() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return;
        
        NetworkRequest request = new NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build();
        
        connectivityCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                updateNetworkType();
                Log.d(TAG, "网络可用: " + networkType);
            }
            
            @Override
            public void onLost(Network network) {
                networkType = "无网络";
                Log.d(TAG, "网络丢失");
            }
            
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                updateNetworkType();
            }
        };
        
        cm.registerNetworkCallback(request, connectivityCallback);
    }
    
    private void updateNetworkType() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            networkType = "未知";
            return;
        }
        
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) {
            networkType = "无网络";
            return;
        }
        
        NetworkCapabilities capabilities = cm.getNetworkCapabilities(activeNetwork);
        if (capabilities == null) {
            networkType = "未知";
            return;
        }
        
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            networkType = "WiFi";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            networkType = "4G/5G";
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            networkType = "以太网";
        } else {
            networkType = "未知";
        }
    }
    
    /**
     * 获取网络类型
     */
    public String getNetworkType() {
        return networkType;
    }
    
    /**
     * 获取传输速度
     */
    public String getTransferSpeed() {
        if (networkClient != null) {
            return networkClient.getFormattedSpeed();
        }
        return "0 B/s";
    }
    
    /**
     * 获取总发送量
     */
    public String getTotalSent() {
        if (networkClient != null) {
            return networkClient.getFormattedTotalBytes();
        }
        return "0 B";
    }
}
