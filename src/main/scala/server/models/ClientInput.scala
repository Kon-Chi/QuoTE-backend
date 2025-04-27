package server.models

import java.util.UUID

import io.circe.*
import io.circe.generic.semiauto.*

import quote.ot.Operation

case class ClientInput(
  revision: Int,
  operations: List[Operation]
) derives Codec.AsObject:
  def toClientOperations(clientId: UUID): ClientOperations =
    ClientOperations(clientId, revision, operations)
