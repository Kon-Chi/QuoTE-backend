package protocol


enum Responses:
  case Success, Failure

class Response(val response: Responses)