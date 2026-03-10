package com.audiolink.sender;

import android.util.Log;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class NetworkClient {
    private static final String TAG = "NetworkClient";
    
    private String serverIp;
    private int serverPort;
    private String protocol;
    private String deviceId;
    private String encoding = Constants.ENCODING_PCM; // PCM or OPUS
    
    private Socket tcpSocket;
    private DatagramSocket udpSocket;
    private OutputStream outputStream;
    
    private ExecutorService executor;
    private ConcurrentLinkedQueue<byte[]> sendQueue;
    private AtomicBoolean isConnected;
    private AtomicBoolean isConnecting;
    private AtomicInteger retryCount;
    
    // 统计信息
    private AtomicLong totalBytesSent = new AtomicLong(0);
    private long lastSpeedCheckTime = 0;
    private long lastBytesSent = 0;
    private float currentSpeed = 0; // bytes per second
    
    private NetworkCallback callback;
    private AutoReconnectThread reconnectThread;
    
    public interface NetworkCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onDataSent(int bytes);
    }
    
    public NetworkClient() {
        executor = Executors.newSingleThreadExecutor();
        sendQueue = new ConcurrentLinkedQueue<>();
        isConnected = new AtomicBoolean(false);
        isConnecting = new AtomicBoolean(false);
        retryCount = new AtomicInteger(0);
    }
    
    public void configure(String ip, int port, String protocol, String deviceId) {
        configure(ip, port, protocol, deviceId, Constants.ENCODING_PCM);
    }
    
    public void configure(String ip, int port, String protocol, String deviceId, String encoding) {
        this.serverIp = ip;
        this.serverPort = port;
        this.protocol = protocol;
        this.deviceId = deviceId;
        this.encoding = encoding;
        stopReconnect();
        if (isConnected.get()) {
            disconnect();
        }
    }
    
    public boolean connect() {
        if (isConnecting.get()) return false;
        isConnecting.set(true);
        
        try {
            if (Constants.PROTOCOL_TCP.equals(protocol)) {
                return connectTcp();
            } else if (Constants.PROTOCOL_UDP.equals(protocol)) {
                return connectUdp();
            } else {
                // HTTP 协议只需要设置标志，不需要真正建立连接
                isConnected.set(true);
                isConnecting.set(false);
                retryCount.set(0);
                if (callback != null) callback.onConnected();
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "连接失败: " + e.getMessage());
            isConnecting.set(false);
            if (callback != null) callback.onError("连接失败: " + e.getMessage());
            return false;
        }
    }
    
    private boolean connectTcp() throws IOException {
        tcpSocket = new Socket();
        tcpSocket.connect(new InetSocketAddress(serverIp, serverPort), Constants.CONNECT_TIMEOUT);
        tcpSocket.setSoTimeout(Constants.READ_TIMEOUT);
        
        outputStream = tcpSocket.getOutputStream();
        
        // 发送握手包
        sendHandshake();
        
        isConnected.set(true);
        isConnecting.set(false);
        retryCount.set(0);
        
        if (callback != null) callback.onConnected();
        startSendThread();
        return true;
    }
    
    private boolean connectUdp() throws IOException {
        // 创建UDP socket
        udpSocket = new DatagramSocket();
        udpSocket.setSoTimeout(Constants.READ_TIMEOUT);
        
        // 发送握手包（UDP也需要握手以便服务器识别设备）
        sendHandshake();
        
        isConnected.set(true);
        isConnecting.set(false);
        retryCount.set(0);
        
        if (callback != null) callback.onConnected();
        startSendThread();
        return true;
    }
    
    private void sendHandshake() throws IOException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        // 握手协议：AUDILINK|DeviceID|Timestamp|Encoding|
        String handshake = Constants.HANDSHAKE_MAGIC + "|" + deviceId + "|" + timestamp + "|" + encoding + "|";
        byte[] handshakeBytes = new byte[Constants.HANDSHAKE_PACKET_SIZE];
        byte[] data = handshake.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(data, 0, handshakeBytes, 0, Math.min(data.length, Constants.HANDSHAKE_PACKET_SIZE));
        
        if (Constants.PROTOCOL_TCP.equals(protocol)) {
            outputStream.write(handshakeBytes);
            outputStream.flush();
        } else if (Constants.PROTOCOL_UDP.equals(protocol)) {
            DatagramPacket packet = new DatagramPacket(
                handshakeBytes, 
                handshakeBytes.length,
                new InetSocketAddress(serverIp, serverPort)
            );
            udpSocket.send(packet);
        }
        
        Log.d(TAG, "发送握手: " + handshake);
    }
    
    public void disconnect() {
        isConnected.set(false);
        isConnecting.set(false);
        stopReconnect();
        
        try {
            if (outputStream != null) { 
                outputStream.close(); 
                outputStream = null; 
            }
            if (tcpSocket != null) { 
                tcpSocket.close(); 
                tcpSocket = null; 
            }
            if (udpSocket != null) { 
                udpSocket.close(); 
                udpSocket = null; 
            }
        } catch (IOException e) {
            Log.e(TAG, "断开连接时出错: " + e.getMessage());
        }
        
        if (callback != null) callback.onDisconnected();
    }
    
    public boolean sendData(byte[] data) {
        if (data == null || data.length == 0) return false;
        
        // 如果未连接，加入队列等待重连后发送
        if (!isConnected.get()) {
            sendQueue.offer(data);
            // 尝试重连
            if (retryCount.get() < Constants.MAX_RETRY_COUNT) {
                startReconnect();
            }
            return false;
        }
        
        if (Constants.PROTOCOL_TCP.equals(protocol)) {
            return sendTcp(data);
        } else if (Constants.PROTOCOL_UDP.equals(protocol)) {
            return sendUdp(data);
        } else if (Constants.PROTOCOL_HTTP.equals(protocol)) {
            return sendHttp(data);
        }
        return false;
    }
    
    private boolean sendTcp(byte[] data) {
        try {
            if (outputStream != null) {
                outputStream.write(data);
                outputStream.flush();
                
                // 更新统计
                totalBytesSent.addAndGet(data.length);
                updateSpeed(data.length);
                
                if (callback != null) callback.onDataSent(data.length);
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "TCP发送错误: " + e.getMessage());
            handleDisconnect();
        }
        return false;
    }
    
    private boolean sendUdp(byte[] data) {
        try {
            if (udpSocket != null) {
                DatagramPacket packet = new DatagramPacket(
                    data, 
                    data.length,
                    new InetSocketAddress(serverIp, serverPort)
                );
                udpSocket.send(packet);
                
                // 更新统计
                totalBytesSent.addAndGet(data.length);
                updateSpeed(data.length);
                
                if (callback != null) callback.onDataSent(data.length);
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "UDP发送错误: " + e.getMessage());
        }
        return false;
    }
    
    private boolean sendHttp(byte[] data) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + serverIp + ":" + serverPort + "/audio");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(Constants.CONNECT_TIMEOUT);
            conn.setReadTimeout(Constants.READ_TIMEOUT);
            conn.setRequestProperty("Content-Type", "audio/pcm");
            conn.setRequestProperty("Content-Length", String.valueOf(data.length));
            conn.getOutputStream().write(data);
            conn.getOutputStream().flush();
            if (conn.getResponseCode() == 200) {
                if (callback != null) callback.onDataSent(data.length);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "HTTP发送错误: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return false;
    }
    
    private void startSendThread() {
        executor.execute(() -> {
            while (isConnected.get()) {
                byte[] data = sendQueue.poll();
                if (data != null) {
                    if (Constants.PROTOCOL_TCP.equals(protocol)) {
                        sendTcp(data);
                    } else if (Constants.PROTOCOL_UDP.equals(protocol)) {
                        sendUdp(data);
                    }
                }
                try { Thread.sleep(100); } catch (InterruptedException e) { break; }
            }
        });
    }
    
    private void handleDisconnect() {
        isConnected.set(false);
        if (callback != null) callback.onDisconnected();
        // 尝试重连
        startReconnect();
    }
    
    private void startReconnect() {
        if (reconnectThread != null && reconnectThread.isRunning()) {
            return;
        }
        reconnectThread = new AutoReconnectThread();
        reconnectThread.start();
    }
    
    private void stopReconnect() {
        if (reconnectThread != null) {
            reconnectThread.stopRunning();
            reconnectThread = null;
        }
    }
    
    private class AutoReconnectThread extends Thread {
        private volatile boolean running = true;
        
        public boolean isRunning() {
            return running && isAlive();
        }
        
        public void stopRunning() {
            running = false;
            interrupt();
        }
        
        @Override
        public void run() {
            while (running && retryCount.get() < Constants.MAX_RETRY_COUNT) {
                try {
                    Thread.sleep(Constants.RECONNECT_DELAY);
                } catch (InterruptedException e) {
                    break;
                }
                
                if (!running) break;
                
                retryCount.incrementAndGet();
                Log.d(TAG, "尝试重连... (" + retryCount.get() + "/" + Constants.MAX_RETRY_COUNT + ")");
                
                if (connect()) {
                    Log.d(TAG, "重连成功!");
                    break;
                }
            }
            
            if (retryCount.get() >= Constants.MAX_RETRY_COUNT) {
                Log.d(TAG, "重连次数已达上限");
                if (callback != null) callback.onError("连接失败，已达最大重试次数");
            }
        }
    }
    
    public void setCallback(NetworkCallback callback) {
        this.callback = callback;
    }
    
    public boolean isConnected() {
        return isConnected.get();
    }
    
    public void release() {
        stopReconnect();
        disconnect();
        executor.shutdown();
    }
    
    // ========== 统计方法 ==========
    
    private void updateSpeed(int bytes) {
        long now = System.currentTimeMillis();
        if (lastSpeedCheckTime == 0) {
            lastSpeedCheckTime = now;
            lastBytesSent = totalBytesSent.get();
            return;
        }
        
        long elapsed = now - lastSpeedCheckTime;
        if (elapsed >= 1000) { // 每秒更新一次
            long bytesDiff = totalBytesSent.get() - lastBytesSent;
            currentSpeed = (float) bytesDiff / (elapsed / 1000.0f);
            lastSpeedCheckTime = now;
            lastBytesSent = totalBytesSent.get();
        }
    }
    
    /**
     * 获取传输速度 (bytes/s)
     */
    public float getCurrentSpeed() {
        return currentSpeed;
    }
    
    /**
     * 获取格式化的传输速度
     */
    public String getFormattedSpeed() {
        if (currentSpeed < 1024) {
            return String.format("%.0f B/s", currentSpeed);
        } else if (currentSpeed < 1024 * 1024) {
            return String.format("%.1f KB/s", currentSpeed / 1024);
        } else {
            return String.format("%.1f MB/s", currentSpeed / (1024 * 1024));
        }
    }
    
    /**
     * 获取总发送字节数
     */
    public long getTotalBytesSent() {
        return totalBytesSent.get();
    }
    
    /**
     * 获取格式化的总发送量
     */
    public String getFormattedTotalBytes() {
        long bytes = totalBytesSent.get();
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
