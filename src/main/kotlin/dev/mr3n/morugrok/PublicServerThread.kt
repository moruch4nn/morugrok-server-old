package dev.mr3n.morugrok

import java.io.Closeable

abstract class PublicServerThread: Thread(), Closeable {
    val privateTunnelServer = PrivateTunnelServer(DaemonSocketServer.AVAILABLE_PORTS.random())
}