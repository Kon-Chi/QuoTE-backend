package server

import scala.util.{Random => sRandom} // only for generation test text

import zio.http.*
import zio.*

import io.circe.*
import io.circe.syntax.*

import java.util.UUID

import io.circe.parser.*

import models.*
import quote.ot.*
import java.nio.file.attribute.UserPrincipal

type OpError = String
type RefClients = Ref[List[(UUID, WebSocketChannel)]]

object WebSocketServer extends ZIOAppDefault {
  private final val queueSize = 1000
  private def socketApp(queue: Queue[UserOperations], clients: RefClients) = Handler.webSocket { channel =>
    for {
      clientId <- Random.nextUUID
      _ <- clients.update((clientId, channel) :: _)

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

  private def notifyClients(currentId: UUID, refClients: RefClients, ops: List[Operation]): Task[Unit] = {
    for {
      clients <- refClients.get
      filteredClients = clients.filter{(id, _) => id != currentId}
      _ <- ZIO.foreach(filteredClients)(
        (_, channel) => channel.send(
          ChannelEvent.Read(WebSocketFrame.Text(ops.asJson.noSpaces))
        )
      )
    } yield ()
  }

  private def routes(queue: Queue[UserOperations], clients: RefClients) = Routes(
    Method.GET / "updates" -> handler(socketApp(queue, clients).toResponse)
  )

  private def opProcess(queue: Queue[UserOperations], clients: RefClients): IO[IllegalArgumentException, String] =
    for {
      queueSize <- queue.size
      testText <- Ref.make((0 until queueSize).foldLeft("")((acc, _) =>
        acc + sRandom.shuffle(List(" ", (sRandom.nextInt(26) + 'a').toChar.toString).head)))
      taken <- queue.take
      UserOperations(userId, ops) = taken
      _ <- ZIO.foreach(ops) {
        op => for {
          curText <- testText.get
          updText <- ZIO.fromEither(applyOp(op)(curText))
            .mapError(errorMsg => new IllegalArgumentException(errorMsg))
          _ <- testText.set(updText)
        } yield ()
      }
      // _ <- notifyClients(userId, clients, ops)
      updText <- testText.get
    } yield updText

  override val run: ZIO[Any, Throwable, Unit] = for {
    queue: Queue[UserOperations] <- Queue.bounded(queueSize)
    clients: RefClients <- Ref.make(List.empty)
    _ <- Server.serve(routes(queue, clients)).provide(Server.default).fork
    _ <- opProcess(queue, clients)
  } yield ()
}

def applyOp(op: Operation)(text: String): Either[OpError, String] =
  op match
    case Insert(index, str) => 
      if index >= text.length || index < 0 then Left("Failed to apply Insert operation")
      else Right(text.take(index) + str + text.drop(index))
    case Delete(index, len) => 
      if len > text.length || index < 0 || index >= text.length then Left("Failed to apply Delete operation")
      else Right(text.take(index) + text.drop(index + len))

