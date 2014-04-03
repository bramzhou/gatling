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
import io.gatling.http.check.ws.WebSocketCheck

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

        tx.check.foreach { check =>
          logRequest(tx.session, tx.requestName, KO, tx.start, time, Some(message))
        }

        context.become(crashedState(tx, message))
      }

      def failPendingCheck(message: String): WebSocketTx = {
        tx.check match {
          case Some(c) =>
            logRequest(tx.session, tx.requestName, KO, tx.start, nowMillis, Some(message))
            tx.copy(updates = Session.MarkAsFailedUpdate :: tx.updates)

          case _ => tx
        }
      }

      def listen(requestName: String, check: WebSocketCheck, next: ActorRef, session: Session) {

        // schedule timeout
        scheduler.scheduleOnce(check.timeout) {
          self ! ListenTimeout(check)
        }

        val newTx = failPendingCheck("Check didn't succeed by the time a new one was set up")
          .applyUpdates(session)
          .copy(requestName = requestName, start = nowMillis, check = Some(check), next = next)
        context.become(openState(webSocket, newTx))

        if (!check.await)
          next ! newTx.session
      }

      def reconciliate(next: ActorRef, session: Session) {
        val newTx = tx.applyUpdates(session)
        context.become(openState(webSocket, newTx))
        next ! newTx.session
      }

    {
      case SendMessage(requestName, message, check, next, session) =>

        val now = nowMillis

        check match {
          case Some(c) =>
            // do this immediately instead of self sending a Listen message so that other messages don't get a chance to be handled before
            listen(requestName + " Check", c, next, session)
          case _ => reconciliate(next, session)
        }

        message match {
          case TextMessage(text)    => webSocket.sendTextMessage(text)
          case BinaryMessage(bytes) => webSocket.sendMessage(bytes)
        }

        logRequest(session, requestName, OK, now, now)

      case Listen(requestName, check, next, session) =>
        listen(requestName, check, next, session)

      case ListenTimeout(check) =>
        if (tx.check.exists(_ == check)) {
          // else ignore, this timeout is outdated
          val newTx = failPendingCheck("Check Timeout")
          context.become(openState(webSocket, newTx))

          if (check.await)
            newTx.next ! newTx.applyUpdates(newTx.session).session
        }

      case OnMessage(message, time) =>
        logger.debug(s"Received message on websocket '$wsName':$message")
        tx.check.foreach { check =>
          // TODO
        }

      case Reconciliate(requestName, next, session) =>
        reconciliate(next, session)

      case Close(requestName, next, session) =>

        webSocket.close()

        val newTx = failPendingCheck("Check didn't succeed by the time the websocket was asked to closed")
          .applyUpdates(session)
          .copy(requestName = requestName, start = nowMillis, next = next)

        context.become(closingState(newTx))

      case OnClose(status, reason, time) =>
        // this close order wasn't triggered by the client, otherwise, we would have received a Close first and state would be closing or stopped
        handleClose(status, reason, time)

      case unexpected =>
        logger.info(s"Discarding unknown message $unexpected while in open state")
    }
  }

  def closingState(tx: WebSocketTx): Receive = {
    case OnClose =>
      import tx._
      logRequest(session, requestName, OK, start, nowMillis)
      next ! session.remove(wsName)
      context.stop(self)

    case unexpected =>
      logger.info(s"Discarding unknown message $unexpected while in closing state")
  }

  def disconnectedState(status: Int, reason: String, tx: WebSocketTx): Receive = {

    case action: WebSocketAction =>
      // reconnect on first client message tentative
      val newTx = tx.copy(reconnectCount = tx.reconnectCount + 1)
      HttpEngine.instance.startWebSocketTransaction(newTx, self)

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
