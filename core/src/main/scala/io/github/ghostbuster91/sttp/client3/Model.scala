package io.github.ghostbuster91.sttp.client3

import cats.data.NonEmptyList
import cats.syntax.all._
import io.github.ghostbuster91.sttp.client3.model.{
  ClassName,
  ParameterName,
  ParameterRef
}
import io.github.ghostbuster91.sttp.client3.openapi._
import io.github.ghostbuster91.sttp.client3.ImportRegistry._

import scala.meta._

case class Model(
    schemas: Map[SchemaRef, SafeSchema],
    classNames: Map[SchemaRef, ClassName],
    childToParentRef: Map[SchemaRef, List[SchemaRef]]
) {

  def classNameFor(schemaRef: SchemaRef): ClassName = classNames(schemaRef)
  def schemaFor(schemaRef: SchemaRef): SafeSchema = schemas(schemaRef)

  def schemaToType(
      schema: SafeSchema
  ): IM[Type] = {
    val declType = schemaToParameter(schema)
    declType.map(d => d.tpe)
  }

  def schemaToParameter(
      schema: SafeSchema,
      isRequired: Boolean
  ): IM[ParameterRef] = {
    val declType = schemaToParameter(schema)
    if (isRequired || schema.isArray) {
      declType
    } else {
      declType.map(_.asOption)
    }
  }

  private def schemaToParameter(schema: SafeSchema): IM[ParameterRef] =
    schema match {
      case ss: SafeStringSchema =>
        ParameterRef("String", ss.default.map(Lit.String(_))).pure[IM]
      case si: SafeIntegerSchema =>
        ParameterRef("Int", si.default.map(Lit.Int(_))).pure[IM]
      case sl: SafeLongSchema =>
        ParameterRef("Long", sl.default.map(Lit.Long(_))).pure[IM]
      case sf: SafeFloatSchema =>
        ParameterRef("Float", sf.default.map(Lit.Float(_))).pure[IM]
      case sd: SafeDoubleSchema =>
        ParameterRef("Double", sd.default.map(Lit.Double(_))).pure[IM]
      case sb: SafeBooleanSchema =>
        ParameterRef("Boolean", sb.default.map(Lit.Boolean(_))).pure[IM]
      case s: SafeArraySchema =>
        schemaToParameter(s.items).map { itemTypeRef =>
          ParameterRef(
            t"List[${itemTypeRef.tpe}]",
            ParameterName(itemTypeRef.paramName.v + "List"),
            None
          )
        }
      case ref: SafeRefSchema =>
        ParameterRef(classNames(ref.ref).v, None).pure[IM]
      case _: SafeUUIDSchema =>
        ImportRegistry
          .registerExternalTpe(
            q"import _root_.java.util.UUID"
          )
          .map(uuidTpe => ParameterRef(uuidTpe, ParameterName("uuid"), None))
    }

  def commonAncestor(childs: NonEmptyList[SchemaRef]): List[SchemaRef] =
    childs
      .map(c => childToParentRef.getOrElse(c, List(c)))
      .map(_.toSet)
      .reduce[Set[SchemaRef]](_ intersect _)
      .toList
}

object Model {
  def apply(
      schemas: Map[String, SafeSchema],
      requestBodies: Map[String, SafeSchema]
  ): Model = {
    val adjSchemas = schemas.map { case (k, v) => SchemaRef.schema(k) -> v }
    val adjReqBodies = requestBodies.map { case (k, v) =>
      SchemaRef.requestBody(k) -> v
    }

    val refToSchema = adjSchemas ++ adjReqBodies
    val modelClassNames =
      refToSchema.keys
        .map(key => key -> ClassName(snakeToCamelCase(key.key)))
        .toMap
    val childToParentRef = calculateChildToParent(refToSchema)
    new Model(
      refToSchema,
      modelClassNames,
      childToParentRef
    )
  }

  private def calculateChildToParent(refToSchema: Map[SchemaRef, SafeSchema]) =
    refToSchema
      .collect { case (key, composed: SafeComposedSchema) =>
        composed.oneOf.map(c => c.ref -> key)
      }
      .flatten
      .groupBy(_._1)
      .mapValues(e => e.map(_._2).toList)

  private def snakeToCamelCase(snake: String) =
    snake.split('_').toList.map(_.capitalize).mkString
}
