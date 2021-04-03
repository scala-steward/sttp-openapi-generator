package io.github.ghostbuster91.sttp.client3.example

import _root_.sttp.client3._
import _root_.sttp.model._
import _root_.io.circe.Decoder
import _root_.io.circe.Encoder
import _root_.io.circe.generic.AutoDerivation
import _root_.sttp.client3.circe.SttpCirceApi

trait CirceCodecs extends AutoDerivation with SttpCirceApi

case class Person(name: String, age: Int)

class DefaultApi(baseUrl: String) extends CirceCodecs {
  def getRoot(id: Option[String], name: Int): Request[Person, Any] =
    basicRequest
      .get(uri"$baseUrl?id=$id&name=$name")
      .response(asJson[Person].getRight)
}
