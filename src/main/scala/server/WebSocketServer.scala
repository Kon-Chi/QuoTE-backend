package server

import zio.*
import zio.http.*
import zio.http.ChannelEvent.*
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
type Clients = List[(UUID, WebSocketChannel)]
type ClientOps = Queue[ClientOperations]
type DocumentId = String
type Document = PieceTable
type Revision = Int
type DocumentState = (Revision, Document, List[List[Operation]])
type Env = ClientOps & Ref[Clients] & Ref[DocumentState] & Redis
type ServerState = Map[DocumentId, DocumentEnv]

case class DocumentEnv(
  state: Ref[DocumentState],
  clients: Ref[Clients],
  queue: ClientOps,
  fiber: Fiber[Throwable, Unit]
)

case class ClientGreeting(text: String, revision: Revision) derives Codec.AsObject

object WebSocketServer extends ZIOAppDefault:
  private final val queueSize = 1000

  private def socketApp(documentEnv: DocumentEnv): WebSocketApp[Any] =
    val DocumentEnv(docState, clients, queue, fiber) = documentEnv
    Handler.webSocket { channel =>
      for
        clientId <- Random.nextUUID
        _ <- clients.update((clientId, channel) :: _)

        _ <- channel.receiveAll {
          case UserEventTriggered(UserEvent.HandshakeComplete) =>
            docState.get.flatMap { (rev, doc, _) =>
              val greeting = ClientGreeting(doc.toString, rev)
              channel.send(Read(WebSocketFrame.Text(greeting.asJson.noSpaces)))
            }

          case Read(WebSocketFrame.Text(jsonString)) =>
            for
              input <- ZIO.fromEither(decode[ClientInput](jsonString)).debug(s"decoding $jsonString")
              _ <- queue.offer(input.toClientOperations(clientId)).debug(s"offering $input")
            yield ()

          case Unregistered | Read(WebSocketFrame.Close(_, _)) =>
            clients.update(_.filterNot(_._1 == clientId))

          case x => ZIO.debug(s"received unexpeced ${x.toString}")
        }
      yield ()
    }

  private def notifyClients(
    clients: Clients,
    currentId: UUID,
    ops: List[Operation],
  ): Task[Unit] = for
    _ <- ZIO.debug(s"notifying $currentId")
    filteredClients = clients.filter { (id, _) => id != currentId }
    _ <- ZIO.foreach(filteredClients) {
      (id, channel) => channel
        .send(Read(WebSocketFrame.Text(ops.asJson.noSpaces)))
        .debug(s"sent operations to $id")
    }.debug("sending ops to clients")
    _ <- clients
      .find { (id, _) => id == currentId }
      .map { (id, channel) => channel
        .send(Read(WebSocketFrame.Text("ack")))
        .debug(s"sent ack to $id")
      }
      match
        case None    => ZIO.unit
        case Some(x) => x
  yield ()

  private def initialDocumentEnv(): UIO[DocumentEnv] = for
    state     <- Ref.make[DocumentState](0, PieceTable(""), List())
    queue     <- Ref.make[Clients](List())
    clientOps <- Queue.bounded[ClientOperations](queueSize)
    fiber     <- opProcess(state, queue, clientOps)
      .forever
      .forkDaemon
      .onInterrupt(ZIO.debug("OP PROCESS INTERRUPTED!"))
  yield DocumentEnv(state, queue, clientOps, fiber)

  private def getOrCreateDocumentEnv(
    docId: DocumentId,
    refServerState: Ref[ServerState]
  ): UIO[DocumentEnv] = for
    serverState <- refServerState.get
    docEnv      <- serverState.get(docId) match
      case Some(a) => ZIO.succeed(a)
      case None =>
        for
          maxDown <- initialDocumentEnv()
          _ <- refServerState.update(_.updated(docId, maxDown))
        yield maxDown
  yield docEnv

  private val config: CorsConfig =
    CorsConfig(allowedOrigin = _ => Some(AccessControlAllowOrigin.All))

  private def routes(serverState: Ref[ServerState]) =
    Routes(
      Method.GET / "updates" / string("docId") -> handler { (docId: DocumentId, _: Request) =>
        for
          state <- getOrCreateDocumentEnv(docId, serverState)
          app <- socketApp(state).toResponse
        yield app
      }
    )

  private def opProcess(
    documentState: Ref[DocumentState],
    refClients: Ref[Clients],
    queue: ClientOps,
  ): Task[Unit] =
    for
      // redis <- ZIO.service[Redis]
      _ <- ZIO.debug(s"taking... from ${queue}")
      clientOpRequest <- queue.take.onInterrupt(ZIO.debug("INTERRUPTED WHILE WAITING!"))
      _ <- ZIO.debug(s"taken $clientOpRequest !")
      curDocumentState <- documentState.get
      (rev, doc, ops) = curDocumentState
      ClientOperations(clientId, clientRev, clientsOps) = clientOpRequest
      concurrentOps <- (
        if clientRev > rev || rev - clientRev > ops.size
        then ZIO.debug(s"Invalid document revision: $rev client: $clientRev" ) *>
             ZIO.fail(new Throwable("Invalid document revision"))
        else ZIO.succeed(ops.take(rev - clientRev))
      ).debug("concurrentOps")
      transformedClientOps =
        concurrentOps.foldLeft(clientsOps) {
          (acc, xs) => OperationalTransformation.transform(xs, acc)._2
        }
      newDoc <- ZIO.foldLeft(transformedClientOps)(doc) {
        (doc, op) => ZIO.fromEither(applyOp(op, doc)).mapError(new Throwable(_)).debug(s"applied $op to $doc")
      }.debug("newDoc")
      clients <- refClients.get
      _ <- notifyClients(clients, clientId, clientsOps).debug("notifing")
      _ <- documentState.set(rev + 1, newDoc, transformedClientOps :: ops)
    yield ()

  override val run = for
    // codecLayer = ZLayer.succeed[CodecSupplier](ProtobufCodecSupplier)
    // redisLayer = Redis.local

    serverState <- Ref.make[ServerState](Map())

    _ <- ZIO.never.forkDaemon

    _ <- Server
      .serve(routes(serverState))
      .provide(Server.defaultWith(_.binding("127.0.0.1", 8080)))
      .zipPar(ZIO.never)
  yield ()

def applyOp(op: Operation, text: Document): Either[OpError, Document] = op match
  case Insert(index, str) => if index > text.length || index < 0
    then Left("Failed to apply Insert operation")
    else Right({ text.insert(index, str); text })
  case Delete(index, str) => if str.length - index > text.length || index < 0 || index > text.length
    then Left("Failed to apply Delete operation")
    else Right({ text.delete(index, str.length); text })

