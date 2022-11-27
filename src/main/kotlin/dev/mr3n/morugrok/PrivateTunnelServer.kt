package dev.mr3n.morugrok

import java.net.ServerSocket
import java.net.Socket

/**
 * 非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)
 */
class PrivateTunnelServer(val port: Int) {
    val serverSocket = ServerSocket(port)

    var closed: Boolean = false
        private set

    fun close() {
        this.closed = true
        try { serverSocket.close() } catch(_: Exception) {}
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