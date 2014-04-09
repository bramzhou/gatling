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
/**
 * Copyright 2011-2012 eBusiness Information, Groupe Excilys (www.excilys.com)
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

trait WsCheckSupport extends WsCheckDSL {

  implicit def wsDSLStep42Check(step: Step4): WsCheck = step.message.find.exists.build
}

trait WsCheckDSL {

  val wsListen = new Step2(false)

  val wsAwait = new Step2(true)

  class Step2(await: Boolean) {

    def within(timeout: FiniteDuration) = new Step3(await, timeout)
  }

  class Step3(await: Boolean, timeout: FiniteDuration) {

    def until(count: Int) = new Step4(await, timeout, ExpectedCount(count), waitForTimeout = false)

    def expect(count: Int) = new Step4(await, timeout, ExpectedCount(count), waitForTimeout = true)

    def expect(range: Range) = new Step4(await, timeout, ExpectedRange(range), waitForTimeout = true)
  }

  class Step4(await: Boolean, timeout: FiniteDuration, expectation: Expectation, waitForTimeout: Boolean) {

    def regex(expression: Expression[String]) = WebSocketRegexCheckBuilder.regex(expression, WsCheckBuilders.checkFactory(await, timeout, expectation, waitForTimeout))

    def jsonPath(path: Expression[String]) = WebSocketJsonPathCheckBuilder.jsonPath(path, WsCheckBuilders.checkFactory(await, timeout, expectation, waitForTimeout))

    def jsonpJsonPath(path: Expression[String]) = WebSocketJsonpJsonPathCheckBuilder.jsonpJsonPath(path, WsCheckBuilders.checkFactory(await, timeout, expectation, waitForTimeout))

    val message = WebSocketPlainCheckBuilder.message(WsCheckBuilders.checkFactory(await, timeout, expectation, waitForTimeout))
  }
}
