package server

import zio.http.*
import zio.*

import io.circe.parser.*

import protocol.*

object WebSocketServer extends ZIOAppDefault {
  private final val queueSize = 1000
  private val socketApp = Handler.webSocket { channel =>
    for {
      queue: Queue[Operation] <- Queue.bounded(queueSize)
      _ <- channel.receiveAll {
        case ChannelEvent.Read(WebSocketFrame.Text(jsonString)) =>
          for {
            op <- ZIO.fromEither(decode[Operation](jsonString))
            _ <- queue.offer(op)
          } yield ()

        case _ => ZIO.unit
      }
    } yield ()
  }

  private val routes = Routes(
    Method.GET / "updates" -> handler(socketApp.toResponse)
  )

  override val run: ZIO[Any, Throwable, Nothing] = Server.serve(routes).provide(Server.default)
}


