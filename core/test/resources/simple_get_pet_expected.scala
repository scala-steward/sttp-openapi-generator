package io.github.ghostbuster91.sttp.client3.example
import sttp.client3._
class Api(serverUrl: String) {
  val getPerson = basicRequest
    .get(Uri.unsafeApply("https", serverUrl, Seq.empty))
    .response(asJson[Person])
}
