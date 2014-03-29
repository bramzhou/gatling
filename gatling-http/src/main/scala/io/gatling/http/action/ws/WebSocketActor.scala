/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.excilys.com)
 * Copyright 2012 Gilt Groupe, Inc. (www.gilt.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.action.ws

import scala.collection.mutable

import com.ning.http.client.websocket.WebSocket

import io.gatling.core.akka.BaseActor
import io.gatling.core.result.message.{ KO, OK, Status }
import io.gatling.core.result.writer.DataWriterClient
import io.gatling.core.session.Session
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.ahc.{ HttpEngine, WebSocketTx }

object WebSocketActor {

  val MarkAsFailed: Session => Session = _.markAsFailed
}

class WebSocketActor(wsName: String) extends BaseActor with DataWriterClient {

  def receive = opening(mutable.Queue.empty)

  def opening(pendingActions: mutable.Queue[WebSocketAction]): Receive = {
    case OnOpen(tx, webSocket, started, ended) =>
      import tx._
      logRequest(session, requestName, OK, started, ended)
      next ! session.set(wsName, self)
      context.become(openState(webSocket, tx))

      // send all pending actions
      pendingActions.foreach {
        self ! _
      }

    case OnFailedOpen(tx, message, started, ended) =>
      import tx._
      logger.info(s"Websocket '$wsName' failed to open: $message")
      logRequest(session, requestName, KO, started, ended, Some(message))
      next ! session.markAsFailed
      context.stop(self)

    case action: WebSocketAction =>
      pendingActions += action
  }

  private def logRequest(session: Session, requestName: String, status: Status, started: Long, ended: Long, errorMessage: Option[String] = None) {
    writeRequestData(
      session,
      requestName,
      started,
      ended,
      ended,
      ended,
      status,
      errorMessage)
  }

  def openState(webSocket: WebSocket, tx: WebSocketTx): Receive = {

      def handleCrash(message: String) {
        if (tx.check.isDefined) {
          // FIXME store somewhere
          val started = nowMillis
          logRequest(tx.session, tx.requestName, KO, started, nowMillis, Some(message))
        }

        context.become(pendingCrashState(message))
      }

      // FIXME started
      def listenFail(message: String, started: Long) {
        tx.check match {
          case Some(check) =>
            logRequest(tx.session, tx.requestName, KO, started, nowMillis, Some(message))
            val newTx = tx.copy(check = None, updates = WebSocketActor.MarkAsFailed :: tx.updates)
            context.become(openState(webSocket, newTx))

          case _ => // ignore, this timeout is outdated
        }
      }

    {
      case SendMessage(requestName, message, next, session) =>
        val started = nowMillis
        message match {
          case Left(text)   => webSocket.sendTextMessage(text)
          case Right(bytes) => webSocket.sendMessage(bytes)
        }
        logRequest(session, requestName, OK, started, nowMillis)

        next ! session

      case Listen(requestName, check, timeout, next, session) =>

        val newTx = tx.copy(requestName = requestName, check = Some(check))

        context.become(openState(webSocket, newTx))

        scheduler.scheduleOnce(timeout) {
          self ! ListenTimeout(requestName, nowMillis)
        }

        next ! session

      case ListenTimeout(requestName, started) =>
        if (tx.requestName == requestName)
          listenFail("Timeout", started)

      case OnMessage(message) =>
        // TODO
        logger.debug(s"Received message on websocket '$wsName':$message")

      case Reconciliate(requestName, next, session) =>
        val newSession = tx.updates.reduceLeft(_ andThen _)(session)
        next ! newSession
        val newTx = tx.copy(updates = Nil)
        context.become(openState(webSocket, newTx))

      case Close(requestName, next, session) =>
        val started = nowMillis
        webSocket.close()
        listenFail("Closing before succeeding", started)
        logRequest(session, requestName, OK, started, nowMillis)
        next ! session.remove(wsName)
        context.become(closingState)

      case OnUnexpectedClose | OnClose =>
        if (tx.protocol.wsPart.reconnect)
          if (tx.protocol.wsPart.maxReconnects.map(_ > tx.reconnectCount).getOrElse(true))
            disconnectedState(mutable.Queue.empty, tx)
          else
            handleCrash(s"Websocket '$wsName' was unexpectedly closed and max reconnect reached")

        else
          handleCrash(s"Websocket '$wsName' was unexpectedly closed")

      case OnError(t) =>
        if (webSocket.isOpen)
          webSocket.close()
        handleCrash(s"Websocket '$wsName' gave an error: '${t.getMessage}'")
    }
  }

  val closingState: Receive = {
    case OnClose => context.stop(self)
    case _       => // discard
  }

  def disconnectedState(pendingActions: mutable.Queue[WebSocketAction], tx: WebSocketTx): Receive = {

    case action: WebSocketAction =>
      // reconnect on first client message tentative
      HttpEngine.instance.startWebSocketTransaction(tx.copy(reconnectCount = tx.reconnectCount + 1), self)

      context.become(reconnectingState(pendingActions += action))

    case _ => // discard
  }

  // FIXME don't lose checks
  def reconnectingState(pendingActions: mutable.Queue[WebSocketAction]): Receive = {

    case OnOpen(tx, webSocket, started, ended) =>
      // send all pending events
      pendingActions.foreach(self ! _)

      context.become(openState(webSocket, tx))

    case OnFailedOpen(tx, message, _, _) =>

      // send all pending events
      pendingActions.foreach(self ! _)

      context.become(pendingCrashState(s"Websocket '$wsName' failed to reconnect: $message"))

    case action: WebSocketAction =>
      pendingActions += action

    case _ => // discard
  }

  def pendingCrashState(error: String): Receive = {

    case action: WebSocketAction =>
      import action._
      val now = nowMillis
      logRequest(session, requestName, KO, now, now, Some(error))
      next ! session.markAsFailed.remove(wsName)
      context.stop(self)

    case _ => // discard
  }
}
