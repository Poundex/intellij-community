// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:JvmName("IjentTunnelsUtil")

package com.intellij.platform.ijent.tunnels

import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.EelTunnelsApi
import com.intellij.platform.eel.component1
import com.intellij.platform.eel.component2
import com.intellij.platform.eel.withConnectionToRemotePort
import com.intellij.platform.ijent.coroutineNameAppended
import com.intellij.platform.ijent.spi.IjentThreadPool
import com.intellij.util.io.toByteArray
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

private val LOG: Logger = Logger.getInstance(EelTunnelsApi::class.java)

/**
 * A tunnel to a remote server (a.k.a. local port forwarding).
 *
 * This function spawns a server on the IDE-host side, and starts accepting connections to "localhost:[localPort]".
 * Each connection to this hostname results in a connection to a server located at [address] on the remote side.
 * The data is transferred from the local connections to remote ones and back, thus establishing a tunnel.
 *
 * The lifetime of port forwarding is bound to the accepting [CoroutineScope]. The receiving [CoroutineScope] cannot complete normally when
 * it runs port forwarding.
 *
 * This function returns when the server starts accepting connections.
 */
@OptIn(DelicateCoroutinesApi::class)
fun CoroutineScope.forwardLocalPort(tunnels: EelTunnelsApi, localPort: Int, address: EelTunnelsApi.HostAddress) {
  val serverSocket = ServerSocket()
  serverSocket.bind(InetSocketAddress("localhost", localPort), 1024)
  serverSocket.soTimeout = 2.seconds.toInt(DurationUnit.MILLISECONDS)
  this.coroutineContext.job.invokeOnCompletion {
    LOG.debug("Closing connection to server socket")
    serverSocket.close()
  }
  LOG.info("Accepting a connection within IDE client on port $localPort; Conections go to remote address $address")
  var connectionCounter = 0
  // DO NOT use Dispatchers.IO here. Sockets live long enough to cause starvation of the IO dispatcher.
  launch(IjentThreadPool.asCoroutineDispatcher() + coroutineNameAppended("Local port forwarding server")) {
    while (true) {
      try {
        LOG.debug("Accepting a coroutine on server socket")
        val socket = runInterruptible {
          serverSocket.accept()
        }
        socket.soTimeout = 2.seconds.toInt(DurationUnit.MILLISECONDS)
        val currentConnection = connectionCounter
        connectionCounter++
        launch {
          socket.use {
            tunnels.withConnectionToRemotePort(address, {
              LOG.error("Failed to connect to remote port $localPort - $address: $it")
            }) { (channelTo, channelFrom) ->
              redirectClientConnectionDataToIJent(currentConnection, socket, channelTo)
              redirectIJentDataToClientConnection(currentConnection, socket, channelFrom)
            }
          }
        }
      }
      catch (e: SocketTimeoutException) {
        ensureActive() // just checking if it is time to abort the port forwarding
      }
    }
  }.invokeOnCompletion {
    LOG.info("Local server on $localPort (was tunneling to $address) is terminated")
  }
}

private fun CoroutineScope.redirectClientConnectionDataToIJent(connectionId: Int, socket: Socket, channelToIJent: SendChannel<ByteBuffer>) = launch(coroutineNameAppended("Reader for connection $connectionId")) {
  val inputStream = socket.getInputStream()
  val buffer = ByteArray(4096)
  try {
    while (true) {
      try {
        val bytesRead = runInterruptible {
          inputStream.read(buffer, 0, buffer.size)
        }
        LOG.trace("Connection $connectionId; Bytes read: $bytesRead")
        if (bytesRead >= 0) {
          LOG.trace("Sending message to IJent for $connectionId")
          channelToIJent.send(ByteBuffer.wrap(buffer.copyOf(), 0, bytesRead)) // important to make a copy, we have no guarantees when buffer will be processed
        }
        else {
          channelToIJent.close()
          break
        }
      }
      catch (e: SocketTimeoutException) {
        ensureActive()
      }
    }
  }
  catch (e: ClosedSendChannelException) {
    LOG.warn("Connection $connectionId closed by IJent; $e")
  }
  catch (e: SocketException) {
    LOG.warn("Connection $connectionId closed", e)
    channelToIJent.close()
    throw CancellationException("Closed because of a socket exception: ${e.message}")
  }
}

private fun CoroutineScope.redirectIJentDataToClientConnection(connectionId: Int, socket: Socket, backChannel: ReceiveChannel<ByteBuffer>) = launch(coroutineNameAppended("Writer for connection $connectionId")) {
  val outputStream = socket.getOutputStream()
  try {
    for (data in backChannel) {
      LOG.debug("IJent port forwarding: $connectionId; Received ${data.remaining()} bytes from IJent; sending them back to client connection")
      try {
        runInterruptible {
          outputStream.write(data.toByteArray())
          outputStream.flush()
        }
      }
      catch (e: SocketTimeoutException) {
        ensureActive()
      }
    }
    outputStream.flush()
  }
  catch (e: EelTunnelsApi.RemoteNetworkException) {
    LOG.warn("Connection $connectionId closed", e)
  }
}

fun EelTunnelsApi.ResolvedSocketAddress.asInetAddress(): InetSocketAddress {
  val inetAddress = when (this) {
    is EelTunnelsApi.ResolvedSocketAddress.V4 -> InetAddress.getByAddress(ByteBuffer.allocate(4).putInt(bits.toInt()).toByteArray())
    is EelTunnelsApi.ResolvedSocketAddress.V6 -> InetAddress.getByAddress(ByteBuffer.allocate(16).putLong(higherBits.toLong()).putLong(8, lowerBits.toLong()).array())
  }
  return InetSocketAddress(inetAddress, port.toInt())
}