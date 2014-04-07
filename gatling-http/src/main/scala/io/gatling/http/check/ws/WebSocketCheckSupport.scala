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

  val ws = WebSocketCheckDSL1
}

object WebSocketCheckDSL1 {

  def within(timeout: FiniteDuration) = new WebSocketCheckDSL2(timeout)
}

class WebSocketCheckDSL2(timeout: FiniteDuration) {

  def expect(count: Int) = new WebSocketCheckDSL3(timeout, count, await = false)

  def await(count: Int) = new WebSocketCheckDSL3(timeout, count, await = true)
}

class WebSocketCheckDSL3(timeout: FiniteDuration, expectedCount: Int, await: Boolean) {

  def regex(expression: Expression[String]) = WebSocketRegexCheckBuilder.regex(expression, WebSocketCheckBuilders.checkFactory(timeout, expectedCount, await))

  def jsonPath(path: Expression[String]) = WebSocketJsonPathCheckBuilder.jsonPath(path, WebSocketCheckBuilders.checkFactory(timeout, expectedCount, await))

  def jsonpJsonPath(path: Expression[String]) = WebSocketJsonpJsonPathCheckBuilder.jsonpJsonPath(path, WebSocketCheckBuilders.checkFactory(timeout, expectedCount, await))

  val message = WebSocketPlainCheckBuilder.message(WebSocketCheckBuilders.checkFactory(timeout, expectedCount, await))
}
