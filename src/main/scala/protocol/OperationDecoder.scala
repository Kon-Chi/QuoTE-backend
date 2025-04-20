package protocol

import io.circe.*
import io.circe.generic.semiauto.*

import protocol.*


implicit val decoder: Decoder[Operation] = Decoder.instance { cursor =>
  cursor.downField("operation").as[String].flatMap {
    case RemoveNCharacters.name =>
      cursor.downField("args").as[RemoveNCharacters]
    case AddString.name =>
      cursor.downField("args").as[AddString]
    case Undo.name => Right(Undo())
    case Redo.name => Right(Redo())
    case other =>
      Left(DecodingFailure(s"Unknown operation: $other", cursor.history))
  }
}

implicit val removeNCharsDecoder: Decoder[RemoveNCharacters] = deriveDecoder[RemoveNCharacters]
implicit val addStringDecoder: Decoder[AddString] = deriveDecoder[AddString]