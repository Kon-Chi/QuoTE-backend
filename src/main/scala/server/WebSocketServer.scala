package server

import zio.http.*
import zio.*
import zio.redis.*

import io.circe.*
import io.circe.parser.*
import io.circe.syntax.*

import java.util.UUID

import models.*
import quote.ot.*
//import server.ProtobufCodecSupplier
import zio.redis.*
import zio.schema.*
import zio.schema.codec.*


object StringCodecSupplier extends CodecSupplier {
  def get[A: Schema]: BinaryCodec[A] = new BinaryCodec[A] {
    def encode(value: A): Chunk[Byte] = Chunk.fromArray(value.toString.getBytes("UTF-8"))

    def decode(chunk: Chunk[Byte]): Either[DecodeError, A] = {
      try {
        val str =new String(chunk.toArray, "UTF-8")
        Right(str.asInstanceOf[A])
      } catch {
        case e: Exception => Left(DecodeError.ReadError(zio.Cause.empty, e.getMessage))
      }
    }
  }
}


implicit val stringSchema: Schema[String] = Schema.primitive[String]

type OpError = String
type RefClients = Ref[List[(UUID, WebSocketChannel)]]
type ClientOps = Queue[ClientOperations]
type Revision = Int
type Document = String
type ServerState = (Revision, Document, List[List[Operation]])
type Env = ClientOps & RefClients & Ref[ServerState] & Redis

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
      _ <- redis.set("doc", doc.toString)
    yield ()

  override val run: ZIO[ZIOAppArgs & Scope, Nothing, ExitCode] = for
    queue <- Queue.bounded[ClientOperations](queueSize)
    clients <- Ref.make(List.empty[(UUID, WebSocketChannel)])
    serverState <- Ref.make[ServerState](0, " ", Nil) // I think we need init document fetching from db or smth

    queueLayer = ZLayer.succeed(queue)
    clientsLayer = ZLayer.succeed(clients)
    serverStateLayer = ZLayer.succeed(serverState)
    codecLayer = ZLayer.succeed[CodecSupplier](StringCodecSupplier)
    redisLayer = Redis.local/pr

    appLayer = serverStateLayer ++ queueLayer ++ clientsLayer ++ codecLayer ++ redisLayer
    _ <- opProcess
      .provideLayer(appLayer)
      .forever
      .fork
    exitCode <- Server
      .serve(routes(queue, clients))
      .provide(Server.defaultWith(_.binding("127.0.0.1", 8000)))
      .exitCode
  yield exitCode


def applyOp(op: Operation, text: Document): Either[OpError, Document] = op match
  case Insert(index, str) => if index >= text.length || index < 0
  then Left("Failed to apply Insert operation")
  else Right(text.take(index) + str + text.drop(index))
  case Delete(index, len) => if len > text.length || index < 0 || index >= text.length
  then Left("Failed to apply Delete operation")
  else Right(text.take(index) + text.drop(index + len))

