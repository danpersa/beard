package de.zalando.beard.renderer

import de.zalando.beard.ast._
import rx.lang.scala.{Subject, Observable}
import rx.lang.scala.subjects.ReplaySubject

import scala.Predef
import scala.collection.immutable._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * @author dpersa
 */
class BeardTemplateRenderer(templateCompiler: TemplateCompiler) {

  def render(template: BeardTemplate, context: Map[String, Any] = Map.empty): Observable[String] = {
    val output = ReplaySubject[Observable[String]]()

    renderInternal(template, context, output)

    output.concat
  }

  private def renderInternal(template: BeardTemplate,
                             context: Map[String, Any] = Map.empty,
                             output: Subject[Observable[String]]): Unit = {

    template.parts.map(renderStatement(_, context, output))
    output.onCompleted()
  }

  private def onNext(output: Subject[Observable[String]], string: String) = {
    output.onNext(Observable.just(string))
  }

  private def renderStatement(statement: Statement, context: Map[String, Any], output: Subject[Observable[String]]): Unit = {
    statement match {
      case Text(text) => onNext(output, text)
      case IdInterpolation(identifier) => {
        onNext(output, ContextResolver.resolve(identifier, context).toString())
      }
      case RenderStatement(template, localValues) =>

        val localContext = localValues.map {
          case attrWithId: AttributeWithIdentifier => attrWithId.key -> ContextResolver.resolve(attrWithId.id, context)
          case attrWitValue: AttributeWithValue => attrWitValue.key -> attrWitValue.value
        }.toMap
        val renderOutput = ReplaySubject[Observable[String]]()
        output.onNext(renderOutput.concat)
        Future {
          renderInternal(templateCompiler.compile(TemplateName(template)).get, localContext, renderOutput)
        }
      case ForStatement(iterator, collection, statements) => {
        val seqFromContext: Seq[Any] = ContextResolver.resolveSeq(collection, context)

        for {
          map <- seqFromContext
          statement <- statements
        } yield {
          renderStatement(statement, context.updated(iterator.identifier, map), output)
        }
      }
      case ExtendsStatement(template) => ()

      case _ => ()
    }
  }
}
