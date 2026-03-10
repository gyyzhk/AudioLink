package com.audiolink.sender.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.audiolink.sender.Constants;
import com.audiolink.sender.NetworkClient;
import com.audiolink.sender.PreferencesManager;
import com.audiolink.sender.databinding.ActivityMainBinding;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    
    // 录音质量
    public static final int QUALITY_HIGH = 0;   // 48kHz, 128kbps
    public static final int QUALITY_MID = 1;    // 16kHz, 64kbps
    public static final int QUALITY_LOW = 2;     // 8kHz, 32kbps
    
    private ActivityMainBinding binding;
    private PreferencesManager prefs;
    private NetworkClient networkClient;
    private AudioRecord audioRecord;
    private boolean isRecording = false;
    private Thread recordingThread;
    private int bufferSize;
    private int currentQuality = QUALITY_MID;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        prefs = PreferencesManager.getInstance(this);
        
        initViews();
        loadConfig();
        checkPermissions();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
    
    private void initViews() {
        // 协议选择
        String[] protocols = {Constants.PROTOCOL_TCP, Constants.PROTOCOL_UDP, Constants.PROTOCOL_HTTP};
        ArrayAdapter<String> protocolAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, protocols);
        binding.spProtocol.setAdapter(protocolAdapter);
        
        // 录音质量选择
        String[] qualities = {"高音质 (48kHz)", "标准音质 (16kHz)", "低音质 (8kHz)"};
        ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, qualities);
        binding.spQuality.setAdapter(qualityAdapter);
        
        // 编码选择
        String[] encodings = {"PCM (无压缩)", "OPUS (压缩)"};
        ArrayAdapter<String> encodingAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, encodings);
        binding.spEncoding.setAdapter(encodingAdapter);
        
        // 监听事件
        binding.btnTest.setOnClickListener(v -> testConnection());
        binding.swService.setOnCheckedChangeListener((buttonView, isChecked) -> toggleService(isChecked));
    }
    
    private void loadConfig() {
        binding.etServerIp.setText(prefs.getServerIp());
        binding.etServerPort.setText(String.valueOf(prefs.getServerPort()));
        binding.etDeviceId.setText(prefs.getDeviceId());
        
        // 协议
        String protocol = prefs.getProtocol();
        if (Constants.PROTOCOL_UDP.equals(protocol)) binding.spProtocol.setSelection(1);
        else if (Constants.PROTOCOL_HTTP.equals(protocol)) binding.spProtocol.setSelection(2);
        else binding.spProtocol.setSelection(0);
        
        // 质量
        binding.spQuality.setSelection(prefs.getAudioQuality());
        
        // 编码
        binding.spEncoding.setSelection(prefs.getAudioEncoding());
    }
    
    private void saveConfig() {
        prefs.setServerIp(binding.etServerIp.getText().toString().trim());
        prefs.setServerPort(Integer.parseInt(binding.etServerPort.getText().toString().trim()));
        prefs.setDeviceId(binding.etDeviceId.getText().toString().trim());
        prefs.setProtocol(binding.spProtocol.getSelectedItem().toString());
        prefs.setAudioQuality(binding.spQuality.getSelectedItemPosition());
        prefs.setAudioEncoding(binding.spEncoding.getSelectedItemPosition());
    }
    
    private void checkPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();
        
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECORD_AUDIO);
        }
        
        if (!permissionsNeeded.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }
    
    private void testConnection() {
        saveConfig();
        
        networkClient = new NetworkClient();
        networkClient.configure(prefs.getServerIp(), prefs.getServerPort(), 
            prefs.getProtocol(), prefs.getDeviceId());
        
        new Thread(() -> {
            boolean connected = networkClient.connect();
            runOnUiThread(() -> Toast.makeText(this, connected ? "连接成功" : "连接失败", Toast.LENGTH_SHORT).show());
        }).start();
    }
    
    private void toggleService(boolean enable) {
        if (enable) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先授予麦克风权限", Toast.LENGTH_SHORT).show();
                binding.swService.setChecked(false);
                checkPermissions();
                return;
            }
            
            saveConfig();
            startRecording();
        } else {
            stopRecording();
            Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
        }
    }
    
    private int getSampleRateForQuality(int quality) {
        switch (quality) {
            case QUALITY_HIGH: return 48000;
            case QUALITY_LOW: return 8000;
            default: return 16000;
        }
    }
    
    private void startRecording() {
        if (isRecording) return;
        
        currentQuality = binding.spQuality.getSelectedItemPosition();
        int sampleRate = getSampleRateForQuality(currentQuality);
        int channelConfig = AudioFormat.CHANNEL_IN_MONO;
        int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
        
        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        
        try {
            // 创建网络客户端
            networkClient = new NetworkClient();
            String encoding = (binding.spEncoding.getSelectedItemPosition() == 1) ? Constants.ENCODING_OPUS : Constants.ENCODING_PCM;
            networkClient.configure(prefs.getServerIp(), prefs.getServerPort(), 
                prefs.getProtocol(), prefs.getDeviceId(), encoding);
            networkClient.connect();
            
            // 创建AudioRecord
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channelConfig, audioFormat, bufferSize);
            
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_SHORT).show();
                return;
            }
            
            audioRecord.startRecording();
            isRecording = true;
            
            // 开始录音线程
            recordingThread = new Thread(() -> {
                byte[] buffer = new byte[bufferSize];
                while (isRecording) {
                    int read = audioRecord.read(buffer, 0, buffer.length);
                    if (read > 0 && networkClient.isConnected()) {
                        byte[] data = new byte[read];
                        System.arraycopy(buffer, 0, data, 0, read);
                        networkClient.sendData(data);
                        
                        int volume = calculateVolume(data);
                        runOnUiThread(() -> binding.tvVolume.setText("音量: " + volume));
                    }
                }
            });
            recordingThread.start();
            
            binding.tvStatus.setText("状态: 正在录音");
            Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            isRecording = false;
        }
    }
    
    private void stopRecording() {
        isRecording = false;
        
        if (recordingThread != null) {
            try { recordingThread.join(1000); } catch (Exception e) {}
            recordingThread = null;
        }
        
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception e) {}
            audioRecord.release();
            audioRecord = null;
        }
        
        if (networkClient != null) {
            networkClient.disconnect();
        }
        
        binding.tvStatus.setText("状态: 已停止");
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
}
