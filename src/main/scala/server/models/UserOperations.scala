package server.models

import io.circe.*
import io.circe.generic.semiauto.*

import quote.ot.Operation

case class UserOperations(
                        userId: String,
                        operations: List[Operation]
                        ) derives Codec.AsObject