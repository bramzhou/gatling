/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.excilys.com)
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

import com.ning.http.client.websocket.WebSocket

import akka.actor.ActorRef
import io.gatling.core.akka.BaseActor
import io.gatling.core.result.message.{ KO, OK, Status }
import io.gatling.core.result.writer.DataWriterClient
import io.gatling.core.session.Session
import io.gatling.core.util.TimeHelper.nowMillis
import io.gatling.http.ahc.{ HttpEngine, WebSocketTx }

class WebSocketActor(wsName: String) extends BaseActor with DataWriterClient {

  def receive = initialState

  val initialState: Receive = {

    case OnOpen(tx, webSocket, end) =>
      import tx._
      logRequest(session, requestName, OK, start, end)
      next ! session.set(wsName, self)

      context.become(openState(webSocket, tx))

    case OnFailedOpen(tx, message, end) =>
      import tx._
      logger.info(s"Websocket '$wsName' failed to open: $message")
      logRequest(session, requestName, KO, start, end, Some(message))
      next ! session.markAsFailed

      context.stop(self)
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

      def handleClose(status: Int, reason: String, time: Long) {
        if (tx.protocol.wsPart.reconnect)
          if (tx.protocol.wsPart.maxReconnects.map(_ > tx.reconnectCount).getOrElse(true))
            disconnectedState(status, reason, tx)
          else
            handleCrash(s"Websocket '$wsName' was unexpectedly closed with status $status and message $reason and max reconnect was reached", time)

        else
          handleCrash(s"Websocket '$wsName' was unexpectedly closed with status $status and message $reason", time)
      }

      def handleCrash(message: String, time: Long) {
        if (tx.check.isDefined)
          logRequest(tx.session, tx.requestName, KO, tx.start, time, Some(message))

        context.become(crashedState(tx, message))
      }

      def applyAndFlushUpdates(session: Session, updates: List[Session => Session], tx: WebSocketTx, next: ActorRef) {
        // update session and flush updates
        val newSession = session.update(updates)
        val newTx = tx.copy(updates = Nil)
        context.become(openState(webSocket, newTx))

        next ! newSession
      }

      def failCurrentCheck(message: String): List[Session => Session] = {
        tx.check match {
          case None =>
            tx.updates

          case _ =>
            logRequest(tx.session, tx.requestName, KO, tx.start, nowMillis, Some(message))
            Session.MarkAsFailedUpdate :: tx.updates
        }
      }

    {
      case SendMessage(requestName, message, next, session) =>

        val now = nowMillis

        message match {
          case TextMessage(text)    => webSocket.sendTextMessage(text)
          case BinaryMessage(bytes) => webSocket.sendMessage(bytes)
        }

        logRequest(session, requestName, OK, now, now)

        applyAndFlushUpdates(session, tx.updates, tx, next)

      case Listen(requestName, check, next, session) =>

        val updates = failCurrentCheck("Check didn't succeed by the time a new one was set up")

        // schedule timeout
        scheduler.scheduleOnce(check.timeout) {
          self ! ListenTimeout(requestName)
        }

        val newTx = tx.copy(requestName = requestName, start = nowMillis, check = Some(check))
        applyAndFlushUpdates(session, updates, newTx, next)

      case ListenTimeout(requestName) =>
        if (tx.requestName == requestName)
          tx.check match {
            case None => // ignore, this timeout is outdated

            case _ =>
              logRequest(tx.session, tx.requestName, KO, tx.start, nowMillis, Some("Check Timeout"))
              val newTx = tx.copy(check = None, updates = Session.MarkAsFailedUpdate :: tx.updates)
              context.become(openState(webSocket, newTx))
          }

      case OnMessage(message, time) =>
        // TODO
        logger.debug(s"Received message on websocket '$wsName':$message")

      case Reconciliate(requestName, next, session) =>

        val newTx = tx.copy(updates = Nil)
        context.become(openState(webSocket, newTx))

        next ! session.update(tx.updates)

      case Close(requestName, next, session) =>

        val updates = failCurrentCheck("Check didn't succeed by the time the websocket was closed")

        logRequest(session, requestName, OK, tx.start, nowMillis)

        // will trigger OnClose
        webSocket.close()

        context.become(closingState)

        next ! session.update(updates).remove(wsName)

      case OnClose(status, reason, time) =>
        // this close order wasn't triggered by the client, otherwise, we would have received a Close first and state would be closing or stopped
        handleClose(status, reason, time)

      case unexpected =>
        logger.info(s"Discarding unknown message $unexpected while in open state")
    }
  }

  val closingState: Receive = {
    case OnClose =>
      // only stop now so we don't get a dead letter
      context.stop(self)

    case unexpected =>
      logger.info(s"Discarding unknown message $unexpected while in closing state")
  }

  def disconnectedState(status: Int, reason: String, tx: WebSocketTx): Receive = {

    case action: WebSocketAction =>
      // reconnect on first client message tentative
      HttpEngine.instance.startWebSocketTransaction(tx.copy(reconnectCount = tx.reconnectCount + 1), self)

      context.become(reconnectingState(status, reason, action))

    case unexpected =>
      logger.info(s"Discarding unknown message $unexpected while in disconnected state")
  }

  def reconnectingState(status: Int, reason: String, pendingAction: WebSocketAction): Receive = {

    case OnOpen(tx, webSocket, _) =>
      context.become(openState(webSocket, tx))
      self ! pendingAction

    case OnFailedOpen(tx, message, _) =>
      context.become(crashedState(tx, s"Websocket '$wsName' originally crashed with status $status and message $message and failed to reconnect: $message"))
      self ! pendingAction

    case unexpected =>
      logger.info(s"Discarding unknown message $unexpected while in reconnecting state")
  }

  def crashedState(tx: WebSocketTx, error: String): Receive = {

    case action: WebSocketAction =>
      import action._
      val now = nowMillis
      logRequest(session, requestName, KO, now, now, Some(error))
      next ! session.update(tx.updates).markAsFailed.remove(wsName)
      context.stop(self)

    case unexpected =>
      logger.info(s"Discarding unknown message $unexpected while in crashed state")
  }
}
