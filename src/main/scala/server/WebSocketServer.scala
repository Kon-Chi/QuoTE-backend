package server

import zio.http.*
import zio.*

import io.circe.*
import io.circe.syntax.*

import java.util.UUID

import io.circe.parser.*

import models.*
import quote.ot.*

type OpError = String
type RefClients = Ref[List[(UUID, WebSocketChannel)]]
type UserOps = Queue[UserOperations]
type Env = UserOps & RefClients

object WebSocketServer extends ZIOAppDefault {
  private final val queueSize = 1000

  private def socketApp(queue: UserOps, clients: RefClients): WebSocketApp[Any] =
    Handler.webSocket { channel =>
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

  private def notifyClients(currentId: UUID, ops: List[Operation]): ZIO[Env, Throwable, Unit] = {
    for {
      queue <- ZIO.service[UserOps]
      refClients <- ZIO.service[RefClients]
      clients <- refClients.get
      filteredClients = clients.filter { (id, _) => id != currentId }
      _ <- ZIO.foreach(filteredClients)(
        (_, channel) => channel.send(
          ChannelEvent.Read(WebSocketFrame.Text(ops.asJson.noSpaces))
        )
      )
    } yield ()
  }

  private def routes(queue: UserOps, clients: RefClients): Routes[Any, Nothing] =
    Routes(
      Method.GET / "updates" -> handler(socketApp(queue, clients).toResponse)
    )

  override val run: ZIO[ZIOAppArgs & Scope, Nothing, ExitCode] = for {
    queue <- Queue.bounded[UserOperations](queueSize)
    clients <- Ref.make(List.empty[(UUID, WebSocketChannel)])
    queueLayer = ZLayer.succeed(queue)
    clientsLayer = ZLayer.succeed(clients)

    appLayer = queueLayer ++ clientsLayer ++ Server.default
    serverProgram =  Server.serve(routes(queue, clients))
    exitCode <- serverProgram.provideLayer(appLayer).exitCode
  } yield exitCode
}

def applyOp(op: Operation)(text: String): Either[OpError, String] =
  op match
    case Insert(index, str) =>
      if index >= text.length || index < 0 then Left("Failed to apply Insert operation")
      else Right(text.take(index) + str + text.drop(index))
    case Delete(index, len) =>
      if len > text.length || index < 0 || index >= text.length then Left("Failed to apply Delete operation")
      else Right(text.take(index) + text.drop(index + len))

