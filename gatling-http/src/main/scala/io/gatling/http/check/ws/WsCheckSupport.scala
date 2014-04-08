/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.http.check.ws

import scala.concurrent.duration.FiniteDuration

import io.gatling.core.session.Expression

trait WebSocketCheckSupport {

  implicit def wsStep32Step3(step: WebSocketCheckDSL4): WsCheck = step.message.find.exists.build

  val ws = WebSocketCheckDSL1
}

object WebSocketCheckDSL1 {

  def listen = new WebSocketCheckDSL2(false)

  def await = new WebSocketCheckDSL2(true)
}

class WebSocketCheckDSL2(await: Boolean) {

  def within(timeout: FiniteDuration) = new WebSocketCheckDSL3(await, timeout)
}

class WebSocketCheckDSL3(await: Boolean, timeout: FiniteDuration) {

  def until(count: Int) = new WebSocketCheckDSL4(await, timeout, ExpectedCount(count), false)

  def expect(count: Int) = new WebSocketCheckDSL4(await, timeout, ExpectedCount(count), true)

  def expect(range: Range) = new WebSocketCheckDSL4(await, timeout, ExpectedRange(range), true)
}

class WebSocketCheckDSL4(await: Boolean, timeout: FiniteDuration, expectation: Expectation, waitForTimeout: Boolean) {

  def regex(expression: Expression[String]) = WebSocketRegexCheckBuilder.regex(expression, WsCheckBuilders.checkFactory(timeout, expectation, await))

  def jsonPath(path: Expression[String]) = WebSocketJsonPathCheckBuilder.jsonPath(path, WsCheckBuilders.checkFactory(timeout, expectation, await))

  def jsonpJsonPath(path: Expression[String]) = WebSocketJsonpJsonPathCheckBuilder.jsonpJsonPath(path, WsCheckBuilders.checkFactory(timeout, expectation, await))

  val message = WebSocketPlainCheckBuilder.message(WsCheckBuilders.checkFactory(timeout, expectation, await))
}
