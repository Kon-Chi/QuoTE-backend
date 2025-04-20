package server

import zio.http.*
import zio.*

import io.circe.parser.*
import io.circe.Json

import protocol.*


object WebSocketServer extends ZIOAppDefault {
  private val socketApp = Handler.webSocket { channel =>
    channel.receiveAll {
      case ChannelEvent.Read(WebSocketFrame.Text(jsonString)) =>
        decode[Operation](jsonString) match {
          case op: AddString => // call handler 
          case op: RemoveNCharacters => // call handler
          case op: Undo => // call handler
          case op: Redo => // call handler
        }

      case _ =>
        ZIO.unit
    }
  }

  private val routes = Routes(
    Method.GET / "updates" -> handler(socketApp.toResponse)
  )

  override val run: ZIO[Any, Throwable, Nothing] = Server.serve(routes).provide(Server.default)
}


