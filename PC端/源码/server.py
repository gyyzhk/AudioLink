import socket
import threading
import logging
import time
from config import HOST, PORT, HANDSHAKE_SIZE
from audio_handler import AudioHandler

logger = logging.getLogger(__name__)


class PDC680Server:
    """PDC680 TCP 服务器"""
    
    def __init__(self, audio_handler, on_client_connected=None, on_client_disconnected=None, on_audio_data=None):
        self.audio_handler = audio_handler
        self.on_client_connected = on_client_connected
        self.on_client_disconnected = on_client_disconnected
        self.on_audio_data = on_audio_data  # 音频数据实时回调
        self.server_socket = None
        self.running = False
        self.clients = {}  # addr -> {socket, device_id, connected_time}
        self.clients_lock = threading.Lock()
        self.listener_thread = None
    
    def start(self):
        """启动服务器"""
        self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.server_socket.bind((HOST, PORT))
        self.server_socket.listen(5)
        self.server_socket.settimeout(1.0)  # 用于优雅退出
        
        self.running = True
        logger.info(f"服务器启动，监听 {HOST}:{PORT}")
        
        self.listener_thread = threading.Thread(target=self._accept_clients, daemon=True)
        self.listener_thread.start()
    
    def stop(self):
        """停止服务器"""
        self.running = False
        
        # 关闭所有客户端
        with self.clients_lock:
            for addr, client_info in self.clients.items():
                try:
                    client_info['socket'].close()
                except:
                    pass
            self.clients.clear()
        
        # 关闭服务器socket
        if self.server_socket:
            try:
                self.server_socket.close()
            except:
                pass
        
        # 关闭所有录音
        self.audio_handler.close_all()
        
        logger.info("服务器已停止")
    
    def _accept_clients(self):
        """接受客户端连接"""
        while self.running:
            try:
                client_socket, addr = self.server_socket.accept()
                logger.info(f"新连接: {addr}")
                
                # 启动客户端处理线程
                client_thread = threading.Thread(
                    target=self._handle_client,
                    args=(client_socket, addr),
                    daemon=True
                )
                client_thread.start()
                
            except socket.timeout:
                continue
            except Exception as e:
                if self.running:
                    logger.error(f"接受连接错误: {e}")
    
    def _handle_client(self, client_socket, addr):
        """处理单个客户端"""
        device_id = None
        try:
            client_socket.settimeout(10.0)
            
            # 接收握手包
            handshake_data = client_socket.recv(HANDSHAKE_SIZE)
            if not handshake_data:
                logger.warning(f"客户端 {addr} 未发送握手数据")
                return
            
            # 解析握手
            result = self.audio_handler.parse_handshake(handshake_data)
            if not result:
                logger.warning(f"客户端 {addr} 握手失败")
                return
            
            device_id, timestamp = result
            logger.info(f"设备 {device_id} 握手成功 from {addr}")
            
            # 注册客户端
            with self.clients_lock:
                self.clients[addr] = {
                    'socket': client_socket,
                    'device_id': device_id,
                    'connected_time': time.time()
                }
            
            # 回调
            if self.on_client_connected:
                self.on_client_connected(device_id, addr)
            
            # 开始录音
            self.audio_handler.start_recording(device_id)
            
            # 持续接收音频数据
            client_socket.settimeout(30.0)  # 更长的超时
            while self.running:
                try:
                    data = client_socket.recv(4096)
                    if not data:
                        # 连接断开
                        break
                    
                    # 写入音频数据
                    self.audio_handler.write_audio(device_id, data)
                    
                    # 触发实时音频回调
                    if self.on_audio_data:
                        self.on_audio_data(device_id, data)
                    
                except socket.timeout:
                    # 超时，继续等待
                    continue
                except Exception as e:
                    logger.error(f"接收数据错误: {e}")
                    break
            
        except Exception as e:
            logger.error(f"客户端处理错误: {e}")
        
        finally:
            # 停止录音
            if device_id:
                filepath, duration = self.audio_handler.stop_recording(device_id)
                if self.on_client_disconnected:
                    self.on_client_disconnected(device_id, filepath, duration)
            
            # 移除客户端
            with self.clients_lock:
                if addr in self.clients:
                    del self.clients[addr]
            
            # 关闭socket
            try:
                client_socket.close()
            except:
                pass
            
            logger.info(f"客户端 {addr} 断开连接")
    
    def get_connected_clients(self):
        """获取已连接客户端列表"""
        with self.clients_lock:
            return {
                addr: info['device_id'] 
                for addr, info in self.clients.items()
            }
    
    def get_server_info(self):
        """获取服务器信息"""
        return {
            'host': HOST,
            'port': PORT,
            'running': self.running,
            'clients': len(self.clients),
            'active_devices': self.audio_handler.get_active_devices()
        }
