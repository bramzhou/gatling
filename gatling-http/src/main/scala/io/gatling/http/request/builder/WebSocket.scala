/**
 * Copyright 2011-2012 eBusiness Information, Groupe Excilys (www.excilys.com)
 * Copyright 2012 Gilt Groupe, Inc. (www.gilt.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.request.builder

import scala.concurrent.duration.FiniteDuration
import io.gatling.core.session.{ Expression, ExpressionWrapper, SessionPrivateAttributes }
import io.gatling.http.action.ws._
import io.gatling.http.request.builder.WebSocket.defaultWebSocketName
import io.gatling.http.check.ws.WebSocketCheck

object WebSocket {

  val defaultWebSocketName = SessionPrivateAttributes.privateAttributePrefix + "http.webSocket"
}

/**
 * @param requestName The name of this request
 */
class WebSocket(requestName: Expression[String]) {

  /**
   * Opens a web socket and stores it in the session.
   *
   * @param url The socket URL
   * @param wsName The name of the session attribute used to store the websocket
   */
  def open(url: Expression[String], wsName: String = defaultWebSocketName) =
    new OpenWebSocketRequestBuilder(CommonAttributes(requestName, "GET", Left(url)), wsName)

  /**
   * Sends a binary message on the given websocket.
   *
   * @param message The message
   * @param wsName The name of the session attribute storing the socket
   */
  def sendBinaryMessage(message: Expression[Array[Byte]], wsName: String = defaultWebSocketName) =
    new SendWebSocketBinaryMessageActionBuilder(requestName, wsName, message)

  /**
   * Sends a text message on the given websocket.
   *
   * @param message The message
   * @param wsName The name of the session attribute storing the socket
   */
  def sendTextMessage(message: Expression[String], wsName: String = defaultWebSocketName) =
    new SendWebSocketTextMessageActionBuilder(requestName, wsName, message)

  /**
   * Listens to incoming messages on the given websocket.
   *
   * @param timeout The maximum duration to listen
   * @param wsName The name of the session attribute storing the websocket
   */
  def listen(timeout: FiniteDuration, wsName: String = defaultWebSocketName): WebSocketListenBuilder = listen(timeout.expression, wsName)

  /**
   * Listens to incoming messages on the given websocket.
   *
   * @param timeout The maximum duration to listen
   * @param wsName The name of the session attribute storing the websocket
   */
  def listen(timeout: Expression[FiniteDuration], wsName: String): WebSocketListenBuilder = new WebSocketListenBuilder(requestName, timeout: Expression[FiniteDuration], wsName: String)

  class WebSocketListenBuilder(requestName: Expression[String], timeout: Expression[FiniteDuration], wsName: String) {

    /**
     * Defines the condition for stopping listening to the websocket.
     * @param check The condition
     */
    def until(check: WebSocketCheck) = new ListenWebSocketActionBuilder(requestName: Expression[String], wsName: String, check, timeout)
  }

  def reconciliate(wsName: String = defaultWebSocketName) =
    new ReconciliateWebSocketActionBuilder(wsName)

  /**
   * Closes a websocket.
   *
   * @param wsName The name of the session attribute storing the websocket
   */
  def close(wsName: String = defaultWebSocketName) =
    new CloseWebSocketActionBuilder(requestName, wsName)
}
