package server

import zio.http.*
import zio.*

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

import java.util.UUID

import quote.ot.*
import pieceTable.*

import models.*

type OpError = String
type RefClients = Ref[List[(UUID, WebSocketChannel)]]
type ClientOps = Queue[ClientOperations]
type Revision = Int
type Document = PieceTable
type ServerState = (Revision, Document, List[List[Operation]])
type Env = ClientOps & RefClients & Ref[ServerState]

object WebSocketServer extends ZIOAppDefault:
  private final val queueSize = 1000

  private def socketApp(queue: ClientOps, clients: RefClients): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      for
        clientId <- Random.nextUUID
        _ <- clients.update((clientId, channel) :: _)

        _ <- channel.receiveAll {
          case ChannelEvent.Read(WebSocketFrame.Text(jsonString)) =>
            for
              input <- ZIO.fromEither(decode[ClientInput](jsonString))
              _ <- queue.offer(input.toClientOperations(clientId))
            yield ()

          case ChannelEvent.Unregistered | ChannelEvent.Read(WebSocketFrame.Close(_, _)) =>
            clients.update(_.filterNot(_._1 == clientId))

          case _ => ZIO.unit
        }
      yield ()
    }

  private def notifyClients(currentId: UUID, ops: List[Operation]): ZIO[Env, Throwable, Unit] =
    for
      queue <- ZIO.service[ClientOps]
      clients <- ZIO.service[RefClients].flatMap(_.get)
      filteredClients = clients.filter { (id, _) => id != currentId }
      _ <- ZIO.foreach(filteredClients) {
        (id, channel) => channel.send(
          ChannelEvent.Read(WebSocketFrame.Text(ops.asJson.noSpaces))
        )
      }
    yield ()

  private def routes(queue: ClientOps, clients: RefClients): Routes[Any, Nothing] =
    Routes(
      Method.GET / "updates" -> handler(socketApp(queue, clients).toResponse)
    )

  private val opProcess: ZIO[Env, Throwable, Unit] =
    for
      serverState <- ZIO.service[Ref[ServerState]]
      queue <- ZIO.service[ClientOps]
      clientOpRequest <- queue.take
      curServerState <- serverState.get
      (rev, doc, ops) = curServerState
      ClientOperations(clientId, clientRev, clientsOps) = clientOpRequest
      concurrentOps <-
        if clientRev > rev || rev - clientRev > ops.size
          then ZIO.fail(new Throwable("Invalid document revision"))
          else ZIO.succeed(ops.take(rev - clientRev))
      transformedClientOps =
        concurrentOps.foldLeft(clientsOps) {
          (acc, xs) => OperationalTransformation.transform(xs, acc)._2
        }
      newDoc <- ZIO.foldLeft(transformedClientOps)(doc) {
        (doc, op) => ZIO.fromEither(applyOp(op, doc)).mapError(new Throwable(_))
      }
      _ <- notifyClients(clientId, clientsOps)
      _ <- serverState.set(rev + 1, newDoc, transformedClientOps :: ops)
    yield ()

  override val run: ZIO[ZIOAppArgs & Scope, Nothing, ExitCode] = for
    queue <- Queue.bounded[ClientOperations](queueSize)
    clients <- Ref.make(List.empty[(UUID, WebSocketChannel)])
    serverState <- Ref.make[ServerState](0, PieceTable(""), Nil) // I think we need init document fetching from db or smth

    queueLayer = ZLayer.succeed(queue)
    clientsLayer = ZLayer.succeed(clients)
    serverStateLayer = ZLayer.succeed(serverState)

    appLayer = serverStateLayer ++ queueLayer ++ clientsLayer
    _ <- opProcess // compiler complained on "Suspicious forward reference", so I've moved run function to the end of app
      .provideLayer(appLayer)
      .forever // whale suggested to wrap this in some exception-catcher (catchAll)
      .fork
    exitCode <- Server
      .serve(routes(queue, clients))
      .provide(Server.defaultWith(_.binding("127.0.0.1", 8000)))
      .exitCode
  yield exitCode

def applyOp(op: Operation, text: Document): Either[OpError, Document] = op match
  case Insert(index, str) => if index > text.length || index < 0
    then Left("Failed to apply Insert operation")
    else Right({ text.insert(index, str); text })
  case Delete(index, len) => if len - index > text.length || index < 0 || index > text.length
    then Left("Failed to apply Delete operation")
    else Right({ text.delete(index, len); text })

