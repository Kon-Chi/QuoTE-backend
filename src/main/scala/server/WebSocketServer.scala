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

  private def socketApp(
                         //                         queue: Queue[UserOperations],
                         //                         clients: RefClients
                       ): ZIO[Env, Throwable, WebSocketApp[Any]] =
    for {
      queue <- ZIO.service[UserOps]
      clients <- ZIO.service[RefClients]

      handler <- ZIO.succeed(Handler.webSocket { channel =>
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
      })
    } yield handler


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

  private def routes(): ZIO[Env, Throwable, Routes[Any, Nothing]] = for {
    app <- socketApp()
    routes = Routes(
      Method.GET / "updates" -> handler(app.toResponse)
    )
  } yield routes

  override val run: URIO[Any, ExitCode] = {
    val queueLayer = ZLayer.fromZIO(Queue.bounded[UserOperations](queueSize))
    val clientsLayer = ZLayer.fromZIO(Ref.make(List.empty[(UUID, WebSocketChannel)]))

    val appLayer = queueLayer ++ clientsLayer ++ Server.default

    val serverProgram = for {
      appRoutes <- routes()
      _ <- Server.serve(appRoutes)
    } yield ()

    serverProgram.provideLayer(appLayer).exitCode
  }
}

def applyOp(op: Operation)(text: String): Either[OpError, String] =
  op match
    case Insert(index, str) => 
      if index >= text.length || index < 0 then Left("Failed to apply Insert operation")
      else Right(text.take(index) + str + text.drop(index))
    case Delete(index, len) => 
      if len > text.length || index < 0 || index >= text.length then Left("Failed to apply Delete operation")
      else Right(text.take(index) + text.drop(index + len))

