/*
 * Copyright 2012 Stephane Godbillon
 *
 * This sample is in the public domain.
 */
package controllers

import javax.inject.Inject

import scala.concurrent.{ ExecutionContext, Future }

import play.api.mvc.{ Action, Controller, WebSocket }
import play.api.libs.concurrent.Execution.Implicits.defaultContext
import play.api.libs.json.{ Json, JsObject, JsValue }
import play.api.libs.iteratee.{ Enumerator, Iteratee }

import reactivemongo.api.{ Cursor, QueryOpts }
import play.modules.reactivemongo.{
  MongoController, ReactiveMongoApi, ReactiveMongoComponents
}
import play.modules.reactivemongo.json.collection.JSONCollection

class Application @Inject() (val reactiveMongoApi: ReactiveMongoApi)
    extends Controller with MongoController with ReactiveMongoComponents {

  // BSON-JSON conversions
  import play.modules.reactivemongo.json._, ImplicitBSONHandlers._

  // let's be sure that the collections exists and is capped
  val futureCollection: Future[JSONCollection] = {
    val db = reactiveMongoApi.db
    val collection = db.collection[JSONCollection]("acappedcollection")

    collection.stats().flatMap {
      case stats if !stats.capped =>
        // the collection is not capped, so we convert it
        println("converting to capped")
        collection.convertToCapped(1024 * 1024, None)
      case _ => Future(collection)
    }.recover {
      // the collection does not exist, so we create it
      case _ =>
        println("creating capped collection...")
        collection.createCapped(1024 * 1024, None)
    }.map { _ =>
      println("the capped collection is available")
      collection
    }
  }

  def index = Action { Ok(views.html.index()) }

  implicit val jsObjFrame = WebSocket.FrameFormatter.jsonFrame.
    transform[JsObject]({ obj: JsObject => obj: JsValue }, {
      case obj: JsObject => obj
      case js => sys.error(s"unexpected JSON value: $js")
    })

  def watchCollection = WebSocket.using[JsObject] { request =>
    // Inserts the received messages into the capped collection
    val in = Iteratee.flatten(futureCollection.
      map(collection => Iteratee.foreach[JsObject] { json =>
        println(s"received $json")
        collection.insert(json)
      }))

    // Enumerates the capped collection
    val out = {
      val futureEnumerator = futureCollection.map { collection =>
        // so we are sure that the collection exists and is a capped one
        val cursor: Cursor[JsObject] = collection
          // we want all the documents
          .find(Json.obj())
          // the cursor must be tailable and await data
          .options(QueryOpts().tailable.awaitData)
          .cursor[JsObject]

        // ok, let's enumerate it
        cursor.enumerate()
      }
      Enumerator.flatten(futureEnumerator)
    }

    // We're done!
    (in, out)
  }
}
