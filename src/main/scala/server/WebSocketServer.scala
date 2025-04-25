package server

import zio.http.*
import zio.*

import java.util.UUID

import io.circe.parser.*

import models.*

type OpError = String

object WebSocketServer extends ZIOAppDefault {
  private final val queueSize = 1000
  private def socketApp(queue: Queue[UserOperations]) = Handler.webSocket { channel =>
    for {
      clientId <- Random.nextUUID.map(_.toString)
      _ <- channel.receiveAll {
        case ChannelEvent.Read(WebSocketFrame.Text(jsonString)) =>
          for {
            op <- ZIO.fromEither(decode[UserOperations](jsonString))
            _ <- queue.offer(op)
          } yield ()

        case _ => ZIO.unit
      }
    } yield ()
  }

  private def routes(queue: Queue[UserOperations]) = Routes(
    Method.GET / "updates" -> handler(socketApp(queue).toResponse)
  )

  override val run: ZIO[Any, Throwable, Nothing] = for {
    queue: Queue[UserOperations] <- Queue.bounded(queueSize)
    server <- Server.serve(routes(queue)).provide(Server.default)
  } yield server
}

def applyOp(op: Operation)(text: String): Either[OpError, String] =
  op match
    case Insert(index, str) => 
      if index >= text.size || index < 0 then Left("Failed to apply Insert operation")
      else Right(text.take(index) + str + text.drop(index))
    case Delete(index, len) => 
      if len > text.size || index < 0 || index >= text.size then Left("Failed to apply Delete operation")
      else Right(text.take(index) + text.drop(index + len))

