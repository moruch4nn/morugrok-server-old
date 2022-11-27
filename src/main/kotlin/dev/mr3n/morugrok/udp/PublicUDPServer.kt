package dev.mr3n.morugrok.udp

import dev.mr3n.morugrok.DaemonSocketServer
import dev.mr3n.morugrok.PublicServerThread
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import kotlin.concurrent.thread

/**
 * 1.非公開トンネルサーバー(サーバーとS3O1サービスをつなぐトンネル)を開く。
 * 2.クライアントの接続を待機し、接続した際に非公開トンネルサーバーを作成し、サーバーをのトンネルを作成する
 * 3.すべてのパケットを相互に転送する
 */
class PublicUDPServer(val port: Int, val outputStream: OutputStream): PublicServerThread() {
    val channel = DatagramSocket(port)
    val tunnelSocket = privateTunnelServer.accept()
    val tunnelOutputStream = tunnelSocket.getOutputStream()
    val tunnelInputStream = tunnelSocket.getInputStream()
    override fun close() {
        tunnelSocket.close()
        channel.close()
        receive.interrupt()
        send.interrupt()
    }

    val receive = thread {
        val data = ByteArray(DEFAULT_BUFFER_SIZE)
        val packet = DatagramPacket(data,data.size)
        while(true) {
            if(this.isInterrupted) { break }
            channel.receive(packet)
            tunnelOutputStream.write(packet.data)
            tunnelOutputStream.flush()
        } }
    val send = thread {
        val buffer = ByteArray(DaemonSocketServer.DEFAULT_BUFFER_SIZE)
        while(true) {
            if(this.isInterrupted) { break }
            val bytesRead: Int = tunnelInputStream.read(buffer)
            if (bytesRead == -1) throw SocketException()
            channel.send(DatagramPacket(buffer, bytesRead))
        } }

    init {
        // >>> サーバーに新しいトンネルを作成するリクエストを送信 >>>
        val byteBuffer = ByteBuffer.allocate(256)
        byteBuffer.put(1)
        byteBuffer.putInt(1,privateTunnelServer.port)
        "0.0.0.0".split(".").map { it.toInt()-128 }.map { it.toByte() }
            .forEachIndexed { index, byte -> byteBuffer.put(128+index,byte) }
        outputStream.write(byteBuffer.array())
        // <<< サーバーに新しいトンネルを作成するリクエストを送信 <<<
        receive.start()
        send.start()
    }
}