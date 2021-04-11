package io.github.ghostbuster91.sttp.client3

import io.github.ghostbuster91.sttp.client3.http.Method
import io.github.ghostbuster91.sttp.client3.model._
import scala.collection.immutable.ListMap
import scala.meta._
import _root_.io.github.ghostbuster91.sttp.client3.http.MediaType

class ApiCallGenerator(modelGenerator: ModelGenerator, ir: ImportRegistry) {

  def generate(
      operations: List[CollectedOperation]
  ): Map[Option[String], List[Defn.Def]] = {
    val taggedOperations: List[(Option[String], CollectedOperation)] =
      operations.flatMap { op =>
        op.operation.tags match {
          case Some(tags) =>
            tags.map(t => Some(t) -> op)
          case None => List(None -> op)
        }
      }
    val apiCallByTag =
      taggedOperations
        .map { case (tag, op) => tag -> createApiCall(op) }
        .groupBy(_._1)
        .mapValues(_.map(_._2))
        .toMap

    ListMap(apiCallByTag.toSeq.sortBy(_._1): _*)
  }

  private def createApiCall(
      co: CollectedOperation
  ): Defn.Def = {
    val CollectedOperation(path, method, operation) = co
    val uri = constructUrl(
      path,
      operation.parameters
    )
    val request = createRequestCall(method, uri)
    createApiCall(operation, request)
  }

  private def createApiCall(
      operation: SafeOperation,
      basicRequestWithMethod: Term
  ): Defn.Def = {
    val operationId = operation.operationId

    val responseClassType = operation.responses
      .collectFirst { case ("200", response) =>
        response.content
          .collectFirst { case (MediaType.ApplicationJson.v, jsonResponse) =>
            modelGenerator
              .schemaToType(
                jsonResponse.schema,
                isRequired = true
              )
              .tpe
          }

      }
      .flatten
      .getOrElse(Type.Name("Unit"))

    val functionName = Term.Name(operationId)
    val fQueries = queryAsFuncParam(operation)
    val fPaths = pathAsFuncParam(operation)
    val fReqBody = reqBodyAsFuncParam(operation)
    val headerParameters = operation.parameters.collect {
      case p: SafeHeaderParameter => p
    }
    val fHeaders = headerParameters.map(headerAsFuncParam)
    val parameters =
      fPaths ++ fQueries ++ fHeaders ++ fReqBody
    val body: Term = Term.Apply(
      Term.Select(
        List(
          applyHeadersToRequest(headerParameters) _,
          applyBodyToRequest(fReqBody) _
        )
          .reduce(_ andThen _)
          .apply(basicRequestWithMethod),
        Term.Name("response")
      ),
      List(q"asJson[$responseClassType].getRight")
    )
    q"def $functionName(..$parameters): Request[$responseClassType, Any] = $body"
  }

  private def applyHeadersToRequest(
      headers: List[SafeHeaderParameter]
  )(request: Term) =
    headers.foldLeft(request) { case (ar, h) =>
      if (h.schema.isArray || !h.required) {
        q"$ar.headers(${Term.Name(h.name)}.map(v => Header(${h.name}, v.toString())).toList:_*)"
      } else {
        q"$ar.header(${h.name}, ${Term.Name(h.name)}.toString())"
      }
    }

  private def applyBodyToRequest(bodyParameter: Option[Term.Param])(
      request: Term
  ) =
    bodyParameter.foldLeft(request)((ar, b) =>
      q"$ar.body(${Term.Name(b.name.value)})"
    )

  private def headerAsFuncParam(headerParam: SafeHeaderParameter) = {
    val paramType = modelGenerator.schemaToType(
      headerParam.schema,
      headerParam.required
    )
    paramType.copy(paramName = headerParam.name).asParam
  }

  private def createRequestCall(method: Method, uri: Term) =
    method match {
      case Method.Put    => q"basicRequest.put($uri)"
      case Method.Get    => q"basicRequest.get($uri)"
      case Method.Post   => q"basicRequest.post($uri)"
      case Method.Delete => q"basicRequest.delete($uri)"
      case Method.Head   => q"basicRequest.head($uri)"
      case Method.Patch  => q"basicRequest.patch($uri)"
    }

  private def constructUrl(path: String, params: List[SafeParameter]) = {
    val pathList = path
      .split('/')
      .toList
      .filter(_.nonEmpty)
      .map { s =>
        if (s.matches("\\{[^/]*\\}")) {
          PathElement.VarPath
        } else {
          PathElement.FixedPath(s)
        }
      }
    val queryParams = params.collect { case q: SafeQueryParameter =>
      Term.Name(q.name)
    }
    val queryList = queryParams
      .foldLeft(List.empty[PathElement]) { (acc, item) =>
        acc match {
          case list if list.nonEmpty =>
            list ++ List(
              PathElement.QuerySegment(s"&$item="),
              PathElement.QueryParam
            )
          case Nil =>
            List(
              PathElement.QuerySegment(s"?$item="),
              PathElement.QueryParam
            )
        }
      }

    val pathParams = params.collect { case p: SafePathParameter =>
      Term.Name(p.name)
    }
    val pathAndQuery =
      (pathList ++ queryList).foldLeft(List("")) { (acc, item) =>
        item match {
          case PathElement.FixedPath(v) =>
            val last = acc.last
            acc.dropRight(1) :+ (last ++ s"/$v")
          case PathElement.QuerySegment(q) =>
            val last = acc.last
            acc.dropRight(1) :+ (last ++ q)
          case PathElement.VarPath =>
            val last = acc.last
            acc.dropRight(1) :+ (last ++ s"/") :+ ""
          case PathElement.QueryParam => acc :+ ""
        }
      }

    Term.Interpolate(
      Term.Name("uri"),
      List(Lit.String("")) ++ pathAndQuery.map(Lit.String(_)),
      List(Term.Name("baseUrl")) ++ pathParams ++ queryParams
    )
  }

  private def pathAsFuncParam(
      operation: SafeOperation
  ): List[Term.Param] =
    operation.parameters
      .collect { case pathParam: SafePathParameter =>
        val paramType = modelGenerator.schemaToType(
          pathParam.schema,
          pathParam.required
        )
        paramType.copy(paramName = pathParam.name).asParam
      }

  private def queryAsFuncParam(
      operation: SafeOperation
  ): List[Term.Param] =
    operation.parameters
      .collect { case queryParam: SafeQueryParameter =>
        val paramType = modelGenerator.schemaToType(
          queryParam.schema,
          queryParam.required
        )
        paramType.copy(paramName = queryParam.name).asParam
      }

  private def reqBodyAsFuncParam(
      operation: SafeOperation
  ): Option[Term.Param] =
    operation.requestBody
      .flatMap { requestBody =>
        requestBody.content
          .collectFirst {
            case (MediaType.ApplicationJson.v, jsonRequest) =>
              modelGenerator.schemaToType(
                jsonRequest.schema,
                requestBody.required
              )

            case (MediaType.ApplicationOctetStream.v, _) =>
              ir.registerImport(q"import _root_.java.io.File")
              ModelGenerator.optionApplication(
                TypeRef("File", None),
                requestBody.required,
                false
              )
          }
          .map(requestBodyType => requestBodyType.asParam)
      }
}

sealed trait PathElement
object PathElement {
  case class FixedPath(v: String) extends PathElement
  case object VarPath extends PathElement
  case class QuerySegment(v: String) extends PathElement
  case object QueryParam extends PathElement
}
