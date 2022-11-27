package dev.mr3n.morugrok.tcp

import dev.mr3n.morugrok.DaemonSocketServer
import dev.mr3n.morugrok.PublicServerThread
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer

/**
 * 1.非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)を開く。
 * 2.クライアントの接続を待機し、接続した際に非公開トンネルサーバーを作成し、サーバーをのトンネルを作成する
 * 3.すべてのパケットを相互に転送する
 */
class PublicTCPServer(val port: Int, val outputStream: OutputStream): PublicServerThread() {
    // 公開サーバー
    val serverSocket = ServerSocket(port)
    // クライアント<->サーバー間をつなぐトンネルとコネクションの一覧
    val connectionSockets = mutableListOf<ConnectionSocket>()
        get() = field.apply { removeAll(field.filter { it.closed }) }

    // コネクションがすでに閉じているかどうか
    var closed: Boolean = false
        private set

    // 公開サーバー関連のすべてのコネクションを閉じる
    override fun close() {
        this.closed = true
        privateTunnelServer.close()
        try { serverSocket.close() } catch(_: Exception) {}
        connectionSockets.forEach { it.close() }
        DaemonSocketServer.AVAILABLE_PORTS.add(port)
        DaemonSocketServer.LOGGER.info("公開サーバーを停止しました。")
    }

    init {
        // >>> サーバーに公開サーバーのポートを送信する >>>
        val byteBuffer = ByteBuffer.allocate(256)
        byteBuffer.put(0)
        byteBuffer.putInt(1,this.port)
        outputStream.write(byteBuffer.array())
        // <<< サーバーに公開サーバーのポートを送信する <<<
    }

    override fun run() {
        // 使用できるポート一覧から今回使用したポートを削除
        DaemonSocketServer.AVAILABLE_PORTS.remove(port)
        // クライアントからの新規接続を待機する
        while(true) {
            // クライアントからの接続が来たら許可する
            val clientSocket = this.serverSocket.accept()

            // クライアントのIPアドレス
            val address = clientSocket.inetAddress.hostAddress
            // >>> サーバーに新しいトンネルを作成するリクエストを送信 >>> TODO
            val byteBuffer = ByteBuffer.allocate(256)
            byteBuffer.put(1)
            byteBuffer.putInt(1,privateTunnelServer.port)
            address.split(".").map { it.toInt()-128 }.map { it.toByte() }
                .forEachIndexed { index, byte -> byteBuffer.put(128+index,byte) }
            outputStream.write(byteBuffer.array())
            // <<< サーバーに新しいトンネルを作成するリクエストを送信 <<<

            // トンネルサーバー作成のリクエストを許可する
            val serverSocket = this.privateTunnelServer.accept()
            // >>>>
            connectionSockets.add(ConnectionSocket(clientSocket, serverSocket)) // クライアントからパケットを受け取ってサーバーに送信
            connectionSockets.add(ConnectionSocket(serverSocket, clientSocket)) // サーバーからパケットを受け取ってクライアントに送信
            // <<<<
        }
    }

    class ConnectionSocket(val receive: Socket, val send: Socket): Thread() {

        var closed = false
            private set

        fun close() {
            try { receive.close() } catch(_: Exception) {}
            try { send.close() } catch(_: Exception) {}
            closed = true
        }

        override fun run() {
            try {
                val inputStream = receive.getInputStream()
                val outputStream = send.getOutputStream()
                val buffer = ByteArray(DaemonSocketServer.DEFAULT_BUFFER_SIZE)
                while(true) {
                    val bytesRead: Int = inputStream.read(buffer)
                    if (bytesRead == -1) throw SocketException() // end
                    outputStream.write(buffer,0,bytesRead)
                    outputStream.flush()
                }
            } catch(e: SocketException) {
                this.close()
            }
        }
        init { this.start() }
    }
    init { this.start() }
}