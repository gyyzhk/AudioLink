package com.audiolink.sender;

public class Constants {
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = 1;
    public static final int AUDIO_FORMAT = 16;
    
    public static final int DEFAULT_THRESHOLD = 30;
    public static final int VAD_START_FRAMES = 3;
    public static final int VAD_STOP_FRAMES = 10;
    public static final int FRAME_SIZE = 1024;
    
    public static final int STORAGE_DAYS = 30;
    public static final long MIN_STORAGE_BYTES = 100 * 1024 * 1024;
    
    public static final int CONNECT_TIMEOUT = 5000;
    public static final int READ_TIMEOUT = 5000;
    public static final int MAX_RETRY_COUNT = 3;
    public static final int RECONNECT_DELAY = 3000;
    
    public static final String PREF_SERVER_IP = "server_ip";
    public static final String PREF_SERVER_PORT = "server_port";
    public static final String PREF_PROTOCOL = "protocol";
    public static final String PREF_THRESHOLD = "threshold";
    public static final String PREF_DEVICE_ID = "device_id";
    public static final String PREF_AUTO_START = "auto_start";
    public static final String PREF_VAD_ENABLED = "vad_enabled";
    public static final String PREF_AUTO_RECONNECT = "auto_reconnect";
    public static final String PREF_AUDIO_SOURCE = "audio_source";
    
    // 握手协议
    public static final String HANDSHAKE_MAGIC = "AUDILINK";
    public static final int HANDSHAKE_PACKET_SIZE = 64;
    
    public static final String PROTOCOL_TCP = "TCP";
    public static final String PROTOCOL_UDP = "UDP";
    public static final String PROTOCOL_HTTP = "HTTP";
    
    // 音频编码格式
    public static final String ENCODING_PCM = "PCM";
    public static final String ENCODING_OPUS = "OPUS";
    
    public static final String PREF_AUDIO_ENCODING = "audio_encoding";
}
