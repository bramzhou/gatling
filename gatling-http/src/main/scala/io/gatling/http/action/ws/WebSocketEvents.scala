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

import scala.concurrent.duration.FiniteDuration

import com.ning.http.client.websocket.WebSocket

import akka.actor.ActorRef
import io.gatling.http.check.ws.WebSocketCheck
import io.gatling.http.ahc.WebSocketTx
import io.gatling.core.session.Session

sealed trait WebSocketEvent
case class OnOpen(tx: WebSocketTx, webSocket: WebSocket, started: Long, ended: Long) extends WebSocketEvent
case class OnFailedOpen(tx: WebSocketTx, message: String, started: Long, ended: Long) extends WebSocketEvent
case class OnMessage(message: String) extends WebSocketEvent
case object OnClose extends WebSocketEvent
case object OnUnexpectedClose extends WebSocketEvent
case class OnError(t: Throwable) extends WebSocketEvent
case class ListenTimeout(requestName: String, started: Long) extends WebSocketEvent

sealed trait WebSocketAction extends WebSocketEvent {
  def requestName: String
  def next: ActorRef
  def session: Session
}
case class SendMessage(requestName: String, message: Either[String, Array[Byte]], next: ActorRef, session: Session) extends WebSocketAction
case class Listen(requestName: String, check: WebSocketCheck, timeout: FiniteDuration, next: ActorRef, session: Session) extends WebSocketAction
case class Close(requestName: String, next: ActorRef, session: Session) extends WebSocketAction
case class Reconciliate(requestName: String, next: ActorRef, session: Session) extends WebSocketAction
