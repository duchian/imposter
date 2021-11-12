/*
 * Copyright (c) 2016-2021.
 *
 * This file is part of Imposter.
 *
 * "Commons Clause" License Condition v1.0
 *
 * The Software is provided to you by the Licensor under the License, as
 * defined below, subject to the following condition.
 *
 * Without limiting other conditions in the License, the grant of rights
 * under the License will not include, and the License does not grant to
 * you, the right to Sell the Software.
 *
 * For purposes of the foregoing, "Sell" means practicing any or all of
 * the rights granted to you under the License to provide to third parties,
 * for a fee or other consideration (including without limitation fees for
 * hosting or consulting/support services related to the Software), a
 * product or service whose value derives, entirely or substantially, from
 * the functionality of the Software. Any license notice or attribution
 * required by the License must also include this Commons Clause License
 * Condition notice.
 *
 * Software: Imposter
 *
 * License: GNU Lesser General Public License version 3
 *
 * Licensor: Peter Cornish
 *
 * Imposter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Imposter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Imposter.  If not, see <https://www.gnu.org/licenses/>.
 */

package io.gatehill.imposter.scripting.common

import io.gatehill.imposter.script.MutableResponseBehaviour
import io.gatehill.imposter.script.impl.RunnableResponseBehaviourImpl
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.nio.file.Files
import java.nio.file.Path

/**
 * @author Pete Cornish
 */
object JavaScriptUtil {
    private val LOGGER: Logger = LogManager.getLogger(JavaScriptUtil::class)
    private val DSL_FUNCTIONS: String = buildDslFunctions()

    /**
     * Expose superclass methods as DSL functions.
     *
     * @return the JavaScript function variables
     */
    private fun buildDslFunctions(): String {
        val dslMethods = MutableResponseBehaviour::class.java.declaredMethods.map { it.name }

        return dslMethods.distinct().joinToString("\r\n") { methodName ->
            "var $methodName = Java.super(responseBehaviour).${methodName};"
        }
    }

    @JvmStatic
    fun wrapScript(scriptFile: Path): String {
        // wrap mock script
        val mockScript = String(Files.readAllBytes(scriptFile))
        val wrappedScript = buildWrappedScript(mockScript)

        LOGGER.trace("Wrapped script: $wrappedScript")
        return wrappedScript
    }

    private fun buildWrappedScript(mockScript: String): String = """
var RunnableResponseBehaviourImpl = Java.type('${RunnableResponseBehaviourImpl::class.java.canonicalName}');

var responseBehaviour = new RunnableResponseBehaviourImpl() {
    run: function() {

/* ------------------------------------------------------------------------- */
/* Exposed DSL functions                                                     */
/* ------------------------------------------------------------------------- */
$DSL_FUNCTIONS
/* ------------------------------------------------------------------------- */
/* Shim for '__imposter_types' module exports                                */
/* ------------------------------------------------------------------------- */
var __imposter_types = {
    env: (function() { return env })(),
    context: (function() { return context })(),
    logger: (function() { return logger })(),
    respond: respond,
    stores: (function() { try { return stores } catch(e) { return undefined } })()
};
/* ------------------------------------------------------------------------- */
/* Shim for 'require()'                                                      */
/* ------------------------------------------------------------------------- */
function require(moduleName) {
  if ("@imposter-js/types" !== moduleName){
    throw new Error('require() only supports "@imposter-js/types"');
  }
  return __imposter_types;
}
/* ------------------------------------------------------------------------- */
/* Mock script                                                               */
/* ------------------------------------------------------------------------- */
$mockScript
/* ------------------------------------------------------------------------- */

    }
}

responseBehaviour.run();

/* return the configured behaviour */
responseBehaviour;
"""
}
