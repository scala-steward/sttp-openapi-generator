package io.github.ghostbuster91.sttp.client3.example

import _root_.sttp.client3._
import _root_.sttp.model._
import _root_.io.circe.Decoder
import _root_.io.circe.Encoder
import _root_.io.circe.HCursor
import _root_.io.circe.DecodingFailure
import _root_.io.circe.Decoder.Result
import _root_.sttp.client3.circe.SttpCirceApi

trait CirceCodecs extends SttpCirceApi {
  implicit lazy val organizationDecoder: Decoder[Organization] =
    Decoder.const(Organization.apply)
  implicit lazy val organizationEncoder: Encoder[Organization] =
    Encoder.forProduct1("name")(_ => "Organization")
  implicit lazy val personDecoder: Decoder[Person] =
    Decoder.forProduct1("age")(Person.apply)
  implicit lazy val personEncoder: Encoder[Person] =
    Encoder.forProduct2("name", "age")(p => ("john", p.age))
  implicit lazy val entityDecoder: Decoder[Entity] = new Decoder[Entity]() {
    override def apply(c: HCursor): Result[Entity] = c
      .downField("name")
      .as[String]
      .flatMap {
        case "Organization" =>
          Decoder[Organization].apply(c)
        case "john" =>
          Decoder[Person].apply(c)
        case other =>
          Left(DecodingFailure("Unexpected value for coproduct:" + other, Nil))
      }
  }
  implicit lazy val entityEncoder: Encoder[Entity] = Encoder.instance {
    case organization: Organization =>
      Encoder[Organization].apply(organization)
    case person: Person =>
      Encoder[Person].apply(person)
  }
}
object CirceCodecs extends CirceCodecs

sealed trait Entity
case class Organization() extends Entity()
case class Person(age: Int) extends Entity()

class DefaultApi(baseUrl: String, circeCodecs: CirceCodecs = CirceCodecs) {
  import circeCodecs._

  def getRoot(): Request[Entity, Any] = basicRequest
    .get(uri"$baseUrl")
    .response(
      fromMetadata(
        asJson[Entity].getRight,
        ConditionalResponseAs(
          _.code == StatusCode.unsafeApply(200),
          asJson[Entity].getRight
        )
      )
    )
}
