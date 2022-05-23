/*
 * Copyright (c) 2021-2021.
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

package io.gatehill.imposter.awslambda.impl

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent
import io.gatehill.imposter.http.HttpExchange
import io.gatehill.imposter.http.HttpRoute
import io.gatehill.imposter.http.HttpRouter
import io.gatehill.imposter.server.HttpServer
import io.gatehill.imposter.util.HttpUtil
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import org.apache.logging.log4j.LogManager
import java.util.Collections.synchronizedMap

/**
 * @author Pete Cornish
 */
class LambdaServer(router: HttpRouter) : HttpServer {
    private val logger = LogManager.getLogger(LambdaServer::class.java)
    private val routes: Array<HttpRoute>
    private val errorHandlers: Map<Int, (HttpExchange) -> Unit>

    init {
        routes = router.routes.toTypedArray()
        errorHandlers = synchronizedMap(router.errorHandlers)
    }

    fun dispatch(event: APIGatewayProxyRequestEvent): APIGatewayProxyResponseEvent {
        val request = LambdaHttpRequest(event)
        val response = LambdaHttpResponse()
        var failureCause: Throwable? = null
        try {
            matchRoutes(request, event, response).forEach { route ->
                val handler = route.handler ?: throw IllegalStateException("No route handler set for: $route")
                val exchange = LambdaHttpExchange(request, response, route)
                try {
                    handler(exchange)
                } catch (e: Exception) {
                    throw RuntimeException("Unhandled error in route: $route", e)
                }
                // check for route failure
                exchange.failure()?.let { cause ->
                    throw RuntimeException("Route failed: $route", cause)
                }
            }

        } catch (e: Exception) {
            failureCause = e
        }

        when (val statusCode = response.getStatusCode()) {
            in 400..499 -> {
                errorHandlers[statusCode]?.let { errorHandler ->
                    val exchange = LambdaHttpExchange(request, response, null)
                    exchange.fail(statusCode)
                    errorHandler(exchange)
                } ?: logger.warn("Unhandled client error for: ${describeRequestShort(event)} [status code: $statusCode]")
            }
            in 500..599 -> {
                errorHandlers[statusCode]?.let { errorHandler ->
                    val exchange = LambdaHttpExchange(request, response, null)
                    exchange.fail(statusCode, failureCause)
                    errorHandler(exchange)
                } ?: logger.error("Unhandled server error for: ${describeRequestShort(event)} [status code: $statusCode]", failureCause)
            }
        }

        // read status again in case modified by error handler
        val responseEvent = APIGatewayProxyResponseEvent()
            .withStatusCode(response.getStatusCode())
            .withHeaders(response.headers)

        if (response.bodyLength > 0) {
            // TODO encode to base 64 if request.event.isBase64Encoded == true
            responseEvent
                .withBody(response.bodyBuffer?.toString(Charsets.UTF_8))
                .withIsBase64Encoded(false)
        }

        return responseEvent
    }

    private fun matchRoutes(
        request: LambdaHttpRequest,
        event: APIGatewayProxyRequestEvent,
        response: LambdaHttpResponse
    ): List<HttpRoute> {
        val matchedRoutes = routes.filter { route ->
            route.matches(request.path()) &&
                    (null == route.method || request.method() == route.method)
        }
        if (matchedRoutes.isEmpty() || matchedRoutes.all { it.isCatchAll() }) {
            logger.trace("No explicit routes matched for: ${describeRequestShort(event)}")
            response.setStatusCode(HttpUtil.HTTP_NOT_FOUND)
                .putHeader("Content-Type", "text/plain")
                .end("Resource not found")
            return emptyList()
        }

        if (logger.isTraceEnabled) {
            logger.trace("Routes matched for: ${describeRequestShort(event)}: $matchedRoutes")
        }
        return matchedRoutes
    }

    /**
     * @param event the request event
     * @return a short description of the request
     */
    private fun describeRequestShort(event: APIGatewayProxyRequestEvent): String {
        return event.httpMethod + " " + event.path
    }

    override fun close(onCompletion: Handler<AsyncResult<Void>>) {
        /* no op */
    }
}
