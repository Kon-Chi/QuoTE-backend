package server

import zio.http.ChannelEvent.*
import zio.http.*
import zio.*
import zio.redis.*
import zio.schema.*
import zio.schema.codec.*

// cors imports
import zio.http.Header.AccessControlAllowOrigin
import zio.http.Middleware.CorsConfig

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

import java.util.UUID
import quote.ot.*
import pieceTable.*
import models.*

object ProtobufCodecSupplier extends CodecSupplier {
  def get[A: Schema]: BinaryCodec[A] = ProtobufCodec.protobufCodec
}

implicit val stringSchema: Schema[String] = Schema.primitive[String]

type OpError = String
type RefClients = Ref[List[(UUID, WebSocketChannel)]]
type ClientOps = Queue[ClientOperations]
type Revision = Int
type Document = PieceTable
type ServerState = (Revision, Document, List[List[Operation]])
type Env = ClientOps & RefClients & Ref[ServerState] & Redis

object WebSocketServer extends ZIOAppDefault:
  private final val queueSize = 1000

  private def socketApp(
                         queue: ClientOps,
                         clients: RefClients,
                         serverState: Ref[ServerState],
                         docId: String
                       ): WebSocketApp[Any] =
    Handler.webSocket { channel =>
      for
        clientId <- Random.nextUUID
        _ <- clients.update((clientId, channel) :: _)

        _ <- channel.receiveAll {
          case UserEventTriggered(UserEvent.HandshakeComplete) =>
            serverState.get.flatMap { (_, doc, _) =>
              channel.send(ChannelEvent.Read(WebSocketFrame.Text(doc.toString)))
            }

          case Read(WebSocketFrame.Text(jsonString)) =>
            for
              input <- ZIO.fromEither(decode[ClientInput](jsonString))
              _ <- queue.offer(input.toClientOperations(clientId))
            yield ()

          case Unregistered | Read(WebSocketFrame.Close(_, _)) =>
            clients.update(_.filterNot(_._1 == clientId))

          case x => Console.printLine(x.toString)
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
      _ <- (clients
        .find { (id, _) => id == currentId }
        .map { (_, channel) => channel.send(Read(WebSocketFrame.Text("ack"))) })
        match
          case None    => ZIO.unit
          case Some(x) => x
    yield ()

  private val config: CorsConfig =
    CorsConfig(
      allowedOrigin = _ => Some(AccessControlAllowOrigin.All)
    )
  private def routes(queue: ClientOps, clients: RefClients, serverState: Ref[ServerState]) =
    Routes(
      Method.GET / "updates" / string("docId") -> handler (
        (docId: String, _: Request) => socketApp(queue, clients, serverState, docId).toResponse
      )
    )
  private val opProcess: ZIO[Env, Throwable, Unit] =
    for
      redis <- ZIO.service[Redis]
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

  override val run = for
    queue <- Queue.bounded[ClientOperations](queueSize)
    clients <- Ref.make(List.empty[(UUID, WebSocketChannel)])

    serverState <- Ref.make[ServerState](0, PieceTable(""), Nil) // I think we need init document fetching from db or smth

    queueLayer = ZLayer.succeed(queue)
    clientsLayer = ZLayer.succeed(clients)
    serverStateLayer = ZLayer.succeed(serverState)
    codecLayer = ZLayer.succeed[CodecSupplier](ProtobufCodecSupplier)
    redisLayer = Redis.local

    _ <- opProcess.forever.fork.provide(
      codecLayer,
      redisLayer,
      serverStateLayer,
      queueLayer,
      clientsLayer
    )

    exitCode <- Server
      .serve(routes(queue, clients, serverState))
      .provide(Server.defaultWith(_.binding("127.0.0.1", 8080)))
      .exitCode
  yield exitCode

def applyOp(op: Operation, text: Document): Either[OpError, Document] = op match
  case Insert(index, str) => if index > text.length || index < 0
    then Left("Failed to apply Insert operation")
    else Right({ text.insert(index, str); text })
  case Delete(index, len) => if len - index > text.length || index < 0 || index > text.length
    then Left("Failed to apply Delete operation")
    else Right({ text.delete(index, len); text })

