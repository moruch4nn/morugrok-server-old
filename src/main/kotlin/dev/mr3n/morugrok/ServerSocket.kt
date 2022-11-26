package dev.mr3n.morugrok

import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
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
        val connections = mutableListOf<PublicTCPServer>()
            get() = field.filterNot { it.closed }.toMutableList()

        /**
         * サーバー関連のすべてのコネクションを閉じる
         */
        fun close() {
            outputStream.close()
            inputStream.close()
            socket.close()
            connections.forEach { it.close() }
            CONNECTIONS.remove(this)
            LOGGER.info("サーバー(${socket.inetAddress.hostAddress})との接続を切断しました。")
            this.interrupt()
        }

        override fun run() {
            val byteArray = ByteArray(4)
            DataInputStream(inputStream).readFully(byteArray)
            val byteBuffer = ByteBuffer.wrap(byteArray)
            val requestPort = byteBuffer.getInt(0).toInt()
            val port = if(AVAILABLE_PORTS.contains(requestPort)) requestPort else AVAILABLE_PORTS.random()
            LOGGER.info("新しくサーバー(${socket.inetAddress.hostAddress})との接続を確立しました。現在はクライアントからの新規接続を待機しています。")
            // 公開サーバー(クライアントがアクセスするサーバー)と非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)を開く
            connections.add(PublicTCPServer(port,outputStream))
            val watchDogStream = socket.getInputStream()
            while(true) {
                try { watchDogStream.read();sleep(1000) } catch(e: IOException) { close();break }
            }
        }

        init { this.start() }
    }

    companion object {
        val LOGGER: Logger = Logger.getLogger("morugrok-server")
        val CONNECTIONS: MutableList<TCPConnection> = mutableListOf()
        val AVAILABLE_PORTS = (10000..30000).toMutableList()
        val DEFAULT_BUFFER_SIZE = 3000000
    }
    init { this.start() }
}

/**
 * 非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)
 */
class PrivateTunnelServer(val port: Int) {
    val serverSocket = ServerSocket(port)

    var closed: Boolean = false
        private set

    fun close() {
        this.closed = true
        serverSocket.close()
        DaemonSocketServer.AVAILABLE_PORTS.add(port)
    }

    /**
     * トンネリングサーバー...サーバー間をつなげるソケットを取得します。
     * 1クライアントあたり1トンネル必要です。
     */
    fun accept(): Socket {
        return serverSocket.accept()
    }
}

/**
 * 1.非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)を開く。
 * 2.クライアントの接続を待機し、接続した際に非公開トンネルサーバーを作成し、サーバーをのトンネルを作成する
 * 3.すべてのパケットを相互に転送する
 */
class PublicTCPServer(val port: Int, val outputStream: OutputStream): Thread() {
    // 公開サーバー
    val serverSocket = ServerSocket(port)
    // 非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)
    val privateTunnelServer = PrivateTunnelServer(DaemonSocketServer.AVAILABLE_PORTS.random())
    // クライアント<->サーバー間をつなぐトンネルとコネクションの一覧
    val connectionSockets = mutableListOf<ConnectionSocket>()
        get() = field.apply { removeAll(field.filter { it.closed }) }

    // コネクションがすでに閉じているかどうか
    var closed: Boolean = false
        private set

    // 公開サーバー関連のすべてのコネクションを閉じる
    fun close() {
        this.closed = true
        privateTunnelServer.close()
        connectionSockets.forEach { it.close() }
        DaemonSocketServer.AVAILABLE_PORTS.add(port)
        DaemonSocketServer.LOGGER.info("公開サーバーを停止しました。")
        this.interrupt()
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
            // >>> サーバーに新しいトンネルを作成するリクエストを送信 >>>
            val byteBuffer = ByteBuffer.allocate(256)
            byteBuffer.put(1)
            byteBuffer.putInt(1,privateTunnelServer.port)
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
            if(!receive.isClosed) { receive.close() }
            if(!send.isClosed) { send.close() }
            closed = true
            this.interrupt()
        }

        override fun run() {
            try {
                val inputStream = receive.getInputStream()
                val outputStream = send.getOutputStream()
                val buffer = ByteArray(DaemonSocketServer.DEFAULT_BUFFER_SIZE)
                while(true) {
                    val bytesRead: Int = inputStream.read(buffer)
                    if (bytesRead == -1) break
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