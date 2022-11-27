package dev.mr3n.morugrok

import dev.mr3n.morugrok.tcp.PublicTCPServer
import java.io.Closeable
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.logging.Logger


/**
 * サーバー = morugrokサービスを使用しているサーバー
 * クライアント = morugrokサービス越しにサーバーにアクセスしようとするクライアント
 */
class DaemonSocketServer(val port: Int): Thread() {
    val serverSocket = ServerSocket(port)

    override fun run() {
        // サーバーと接続を維持するためのハートビートパケットの受信待ち
        while(true) {
            LOGGER.info("デーモンを正常に起動しました。現在サーバーからの接続を待機中です。")
            val socket = serverSocket.accept()
            // 受信するとCONNECTIONSの中に入れる
            CONNECTIONS.add(TCPConnection(socket))
        }
    }

    class TCPConnection(val socket: Socket): Thread() {
        val outputStream = socket.getOutputStream()
        val inputStream = socket.getInputStream()
        val connections = mutableListOf<Closeable>()

        /**
         * サーバー関連のすべてのコネクションを閉じる
         */
        fun close() {
            connections.forEach { it.close() }
            CONNECTIONS.remove(this)
            LOGGER.info("サーバー(${socket.inetAddress.hostAddress})との接続を切断しました。")
            try { outputStream.close() } catch(_: Exception) {}
            try { inputStream.close() } catch(_: Exception) {}
            try { socket.close() } catch(_: Exception) {}
            this.interrupt()
        }

        override fun run() {
            val byteArray = ByteArray(4)
            DataInputStream(inputStream).readFully(byteArray)
            val byteBuffer = ByteBuffer.wrap(byteArray)
            val requestPort = byteBuffer.getInt(1)
            val port = if(AVAILABLE_PORTS.contains(requestPort)) requestPort else AVAILABLE_PORTS.random()
            val protocol = byteBuffer.get(0).toInt()
            LOGGER.info("新しくサーバー(${socket.inetAddress.hostAddress})との接続を確立しました。現在はクライアントからの新規接続を待機しています。")
            // 公開サーバー(クライアントがアクセスするサーバー)と非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)を開く
            when(protocol) {
                // TCP
                0 -> connections.add(PublicTCPServer(port,outputStream))
                // UDP
            }
            while(true) { if(inputStream.read()==-1) { close();break } }
        }

        init { this.start() }
    }

    companion object {
        val LOGGER: Logger = Logger.getLogger("morugrok-server")
        val CONNECTIONS: MutableList<TCPConnection> = mutableListOf()
        val AVAILABLE_PORTS = (10000..60000).toMutableSet()
        const val DEFAULT_BUFFER_SIZE = 3000000
    }
    init { this.start() }
}