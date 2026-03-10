package com.audiolink.sender;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferencesManager {
    private static final String PREF_NAME = "audio_sender_prefs";
    private static PreferencesManager instance;
    private final SharedPreferences prefs;
    
    private PreferencesManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    public static synchronized PreferencesManager getInstance(Context context) {
        if (instance == null) {
            instance = new PreferencesManager(context);
        }
        return instance;
    }
    
    public String getServerIp() {
        return prefs.getString(Constants.PREF_SERVER_IP, "192.168.1.100");
    }
    
    public void setServerIp(String ip) {
        prefs.edit().putString(Constants.PREF_SERVER_IP, ip).apply();
    }
    
    public int getServerPort() {
        return prefs.getInt(Constants.PREF_SERVER_PORT, 8080);
    }
    
    public void setServerPort(int port) {
        prefs.edit().putInt(Constants.PREF_SERVER_PORT, port).apply();
    }
    
    public String getProtocol() {
        return prefs.getString(Constants.PREF_PROTOCOL, Constants.PROTOCOL_TCP);
    }
    
    public void setProtocol(String protocol) {
        prefs.edit().putString(Constants.PREF_PROTOCOL, protocol).apply();
    }
    
    public String getDeviceId() {
        return prefs.getString(Constants.PREF_DEVICE_ID, "DEVICE_001");
    }
    
    public void setDeviceId(String deviceId) {
        prefs.edit().putString(Constants.PREF_DEVICE_ID, deviceId).apply();
    }
    
    public int getThreshold() {
        return prefs.getInt(Constants.PREF_THRESHOLD, Constants.DEFAULT_THRESHOLD);
    }
    
    public void setThreshold(int threshold) {
        prefs.edit().putInt(Constants.PREF_THRESHOLD, threshold).apply();
    }
    
    public boolean isAutoStart() {
        return prefs.getBoolean(Constants.PREF_AUTO_START, false);
    }
    
    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(Constants.PREF_AUTO_START, autoStart).apply();
    }
    
    public boolean isVadEnabled() {
        return prefs.getBoolean(Constants.PREF_VAD_ENABLED, false);
    }
    
    public void setVadEnabled(boolean enabled) {
        prefs.edit().putBoolean(Constants.PREF_VAD_ENABLED, enabled).apply();
    }
    
    public boolean isAutoReconnect() {
        return prefs.getBoolean(Constants.PREF_AUTO_RECONNECT, true);
    }
    
    public void setAutoReconnect(boolean enabled) {
        prefs.edit().putBoolean(Constants.PREF_AUTO_RECONNECT, enabled).apply();
    }
    
    // 音频源设置 (0=麦克风, 1=系统音频)
    public static final String PREF_AUDIO_SOURCE = "audio_source";
    
    public int getAudioSource() {
        return prefs.getInt(PREF_AUDIO_SOURCE, 0); // 默认麦克风
    }
    
    public void setAudioSource(int source) {
        prefs.edit().putInt(PREF_AUDIO_SOURCE, source).apply();
    }
    
    // 音频编码格式 (0=PCM, 1=OPUS)
    public int getAudioEncoding() {
        return prefs.getInt(Constants.PREF_AUDIO_ENCODING, 0); // 默认PCM
    }
    
    public void setAudioEncoding(int encoding) {
        prefs.edit().putInt(Constants.PREF_AUDIO_ENCODING, encoding).apply();
    }
    
    // 获取编码类型字符串
    public String getAudioEncodingString() {
        int encoding = getAudioEncoding();
        if (encoding == 1) {
            return Constants.ENCODING_OPUS;
        }
        return Constants.ENCODING_PCM;
    }
}
