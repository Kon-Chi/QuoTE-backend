package protocol

sealed trait Operation

case class AddString(
                 str: String,
                 index: Int
               ) extends Operation
object AddString {
  final val name = "addString"
}

case class RemoveNCharacters(
                          n: Int, // number of characters to remove
                          index: Int
                        ) extends Operation
object RemoveNCharacters {
  final val name = "removeNCharacters"
}

case class Undo() extends Operation
object Undo {
  final val name = "undo"
}

case class Redo() extends Operation
object Redo {
  final val name = "redo"
}