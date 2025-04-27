package server.models

import java.util.UUID

import quote.ot.Operation

case class ClientOperations(
  clientId: UUID,
  revision: Int,
  operations: List[Operation]
)
