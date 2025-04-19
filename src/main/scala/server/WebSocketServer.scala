package server

import zio.http.*
import zio.*

val socket = Handler.webSocket { channel =>
  channel.receiveAll {
    case ChannelEvent.Read(WebSocketFrame.Text("foo")) =>
      channel.send(ChannelEvent.Read(WebSocketFrame.text("bar")))
    case _ =>
      ZIO.unit
  }
}

// a preliminary template of a route that asks a client for updates
val http = Routes(
  Method.GET / "updates" -> handler(socket.toResponse)
)