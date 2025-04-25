package server

import zio.http.*
import zio.*

import io.circe.parser.*

import quote.ot.*

type OpError = String

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

def applyOp(op: Operation)(text: String): Either[OpError, String] =
  op match
    case Insert(index, str) => 
      if index >= text.size || index < 0 then Left("Failed to apply Insert operation")
      else Right(text.take(index) + str + text.drop(index))
    case Delete(index, len) => 
      if len > text.size || index < 0 || index >= text.size then Left("Failed to apply Delete operation")
      else Right(text.take(index) + text.drop(index + len))

