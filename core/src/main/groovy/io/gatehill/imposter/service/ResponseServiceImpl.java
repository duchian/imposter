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

package io.gatehill.imposter.service;

import com.google.common.base.Strings;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Injector;
import io.gatehill.imposter.exception.ResponseException;
import io.gatehill.imposter.http.ResponseBehaviourFactory;
import io.gatehill.imposter.http.StatusCodeFactory;
import io.gatehill.imposter.lifecycle.EngineLifecycleHooks;
import io.gatehill.imposter.plugin.config.ContentTypedConfig;
import io.gatehill.imposter.plugin.config.PluginConfig;
import io.gatehill.imposter.plugin.config.ResourcesHolder;
import io.gatehill.imposter.plugin.config.resource.ResourceConfig;
import io.gatehill.imposter.plugin.config.resource.ResponseConfig;
import io.gatehill.imposter.plugin.config.resource.ResponseConfigHolder;
import io.gatehill.imposter.script.PerformanceSimulationConfig;
import io.gatehill.imposter.script.ReadWriteResponseBehaviour;
import io.gatehill.imposter.script.ResponseBehaviour;
import io.gatehill.imposter.script.ResponseBehaviourType;
import io.gatehill.imposter.util.EnvVars;
import io.gatehill.imposter.util.HttpUtil;
import io.gatehill.imposter.util.LogUtil;
import io.gatehill.imposter.util.MetricsUtil;
import io.micrometer.core.instrument.Gauge;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.MimeMapping;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.RoutingContext;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.Objects.nonNull;
import static java.util.Optional.ofNullable;

/**
 * @author Pete Cornish
 */
public class ResponseServiceImpl implements ResponseService {
    private static final Logger LOGGER = LogManager.getLogger(ResponseServiceImpl.class);
    private static final String ENV_RESPONSE_FILE_CACHE_ENTRIES = "IMPOSTER_RESPONSE_FILE_CACHE_ENTRIES";
    private static final int DEFAULT_RESPONSE_FILE_CACHE_ENTRIES = 20;
    private static final String METRIC_RESPONSE_FILE_CACHE_ENTRIES = "response.file.cache.entries";

    @Inject
    private EngineLifecycleHooks engineLifecycle;

    @Inject
    private ScriptedResponseService scriptedResponseService;

    @Inject
    private Vertx vertx;

    /**
     * Holds response files, with maximum number of entries determined by the environment
     * variable {@link #ENV_RESPONSE_FILE_CACHE_ENTRIES}.
     */
    private final Cache<Path, String> responseFileCache = CacheBuilder.newBuilder()
            .maximumSize(ofNullable(EnvVars.getEnv(ENV_RESPONSE_FILE_CACHE_ENTRIES)).map(Integer::parseInt).orElse(DEFAULT_RESPONSE_FILE_CACHE_ENTRIES))
            .build();

    @Inject
    public ResponseServiceImpl() {
        MetricsUtil.doIfMetricsEnabled(METRIC_RESPONSE_FILE_CACHE_ENTRIES, registry ->
                Gauge.builder(METRIC_RESPONSE_FILE_CACHE_ENTRIES, responseFileCache::size)
                        .description("The number of cached response files")
                        .register(registry)
        );
    }

    @Override
    public void handle(
            PluginConfig pluginConfig,
            ResponseConfigHolder resourceConfig,
            RoutingContext routingContext,
            Injector injector,
            Map<String, ?> additionalContext,
            StatusCodeFactory statusCodeFactory,
            ResponseBehaviourFactory responseBehaviourFactory,
            Consumer<ResponseBehaviour> defaultBehaviourHandler
    ) {
        try {
            engineLifecycle.forEach(listener -> listener.beforeBuildingResponse(routingContext, resourceConfig));

            final ResponseBehaviour responseBehaviour = buildResponseBehaviour(
                    routingContext,
                    pluginConfig,
                    resourceConfig,
                    additionalContext,
                    Collections.emptyMap(),
                    statusCodeFactory,
                    responseBehaviourFactory
            );

            if (ResponseBehaviourType.SHORT_CIRCUIT.equals(responseBehaviour.getBehaviourType())) {
                routingContext.response()
                        .setStatusCode(responseBehaviour.getStatusCode())
                        .end();
            } else {
                // default behaviour
                defaultBehaviourHandler.accept(responseBehaviour);
            }

        } catch (Exception e) {
            final String msg = String.format("Error sending mock response for %s", LogUtil.describeRequest(routingContext));
            LOGGER.error(msg, e);
            routingContext.fail(new ResponseException(msg, e));
        }
    }

    private ResponseBehaviour buildResponseBehaviour(
            RoutingContext routingContext,
            PluginConfig pluginConfig,
            ResponseConfigHolder resourceConfig,
            Map<String, ?> additionalContext,
            Map<String, Object> additionalBindings,
            StatusCodeFactory statusCodeFactory,
            ResponseBehaviourFactory responseBehaviourFactory
    ) {
        final ResponseConfig responseConfig = resourceConfig.getResponseConfig();

        checkNotNull(responseConfig, "Response configuration must not be null");
        final int statusCode = statusCodeFactory.calculateStatus(resourceConfig);

        final ReadWriteResponseBehaviour responseBehaviour;
        if (Strings.isNullOrEmpty(responseConfig.getScriptFile())) {
            LOGGER.debug("Using default HTTP {} response behaviour for request: {} {}",
                    statusCode, routingContext.request().method(), routingContext.request().absoluteURI());

            responseBehaviour = responseBehaviourFactory.build(statusCode, responseConfig);

        } else {
            responseBehaviour = scriptedResponseService.determineResponseFromScript(
                    routingContext,
                    pluginConfig,
                    resourceConfig,
                    additionalContext,
                    additionalBindings
            );

            // use defaults if not set
            if (ResponseBehaviourType.DEFAULT_BEHAVIOUR.equals(responseBehaviour.getBehaviourType())) {
                responseBehaviourFactory.populate(statusCode, responseConfig, responseBehaviour);
            }
        }

        // explicitly check if the root resource should have its response config used as defaults for its child resources
        if (pluginConfig instanceof ResourcesHolder && ((ResourcesHolder<?>) pluginConfig).isDefaultsFromRootResponse()) {
            if (pluginConfig instanceof ResponseConfigHolder) {
                LOGGER.trace("Inheriting root response configuration as defaults");
                responseBehaviourFactory.populate(statusCode, ((ResponseConfigHolder) pluginConfig).getResponseConfig(), responseBehaviour);
            }
        }

        return responseBehaviour;
    }

    @Override
    public boolean sendEmptyResponse(RoutingContext routingContext, ResponseBehaviour responseBehaviour) {
        try {
            LOGGER.info("Response file and data are blank - returning empty response for {}", LogUtil.describeRequest(routingContext));
            routingContext.response().end();
            return true;
        } catch (Exception e) {
            LOGGER.warn("Error sending empty response for " + LogUtil.describeRequest(routingContext), e);
            return false;
        }
    }

    @Override
    public void sendResponse(
            PluginConfig pluginConfig,
            ResourceConfig resourceConfig,
            RoutingContext routingContext,
            ResponseBehaviour responseBehaviour
    ) {
        sendResponse(pluginConfig, resourceConfig, routingContext, responseBehaviour, this::sendEmptyResponse);
    }

    @Override
    public void sendResponse(
            PluginConfig pluginConfig,
            ResourceConfig resourceConfig,
            RoutingContext routingContext,
            ResponseBehaviour responseBehaviour,
            ResponseSender... fallbackSenders
    ) {
        simulatePerformance(responseBehaviour, routingContext.request(), () ->
                sendResponseInternal(pluginConfig, resourceConfig, routingContext, responseBehaviour, fallbackSenders)
        );
    }

    private void simulatePerformance(ResponseBehaviour responseBehaviour, HttpServerRequest request, Runnable completion) {
        final PerformanceSimulationConfig performance = responseBehaviour.getPerformanceSimulation();
        int delayMs = -1;

        if (nonNull(performance)) {
            if (ofNullable(performance.getExactDelayMs()).orElse(0) > 0) {
                delayMs = performance.getExactDelayMs();
            } else {
                final Integer minDelayMs = ofNullable(performance.getMinDelayMs()).orElse(0);
                final Integer maxDelayMs = ofNullable(performance.getMaxDelayMs()).orElse(0);
                if (minDelayMs > 0 && maxDelayMs >= minDelayMs) {
                    delayMs = ThreadLocalRandom.current().nextInt(maxDelayMs - minDelayMs) + minDelayMs;
                }
            }
        }

        if (delayMs > 0) {
            LOGGER.info("Delaying mock response for {} {} by {}ms", request.method(), request.absoluteURI(), delayMs);
            vertx.setTimer(delayMs, e -> completion.run());
        } else {
            completion.run();
        }
    }

    private void sendResponseInternal(
            PluginConfig pluginConfig,
            ResourceConfig resourceConfig,
            RoutingContext routingContext,
            ResponseBehaviour responseBehaviour,
            ResponseSender[] fallbackSenders
    ) {
        LOGGER.trace("Sending mock response for URI {} with status code {}",
                routingContext.request().absoluteURI(),
                responseBehaviour.getStatusCode());

        try {
            final HttpServerResponse response = routingContext.response();
            response.setStatusCode(responseBehaviour.getStatusCode());

            responseBehaviour.getResponseHeaders().forEach(response::putHeader);

            if (!Strings.isNullOrEmpty(responseBehaviour.getResponseFile())) {
                serveResponseFile(pluginConfig, resourceConfig, routingContext, responseBehaviour);
            } else if (!Strings.isNullOrEmpty(responseBehaviour.getResponseData())) {
                serveResponseData(resourceConfig, routingContext, responseBehaviour);
            } else {
                fallback(routingContext, responseBehaviour, fallbackSenders);
            }

        } catch (Exception e) {
            routingContext.fail(new ResponseException(String.format(
                    "Error sending mock response with status code %s for %s",
                    responseBehaviour.getStatusCode(), LogUtil.describeRequest(routingContext)), e));
        }
    }

    /**
     * Reply with a static response file. Note that the content type is determined
     * by the file being sent.
     *
     * @param pluginConfig      the plugin configuration
     * @param resourceConfig    the resource configuration
     * @param routingContext    the Vert.x routing context
     * @param responseBehaviour the response behaviour
     */
    private void serveResponseFile(PluginConfig pluginConfig,
                                   ResourceConfig resourceConfig,
                                   RoutingContext routingContext,
                                   ResponseBehaviour responseBehaviour) throws ExecutionException {

        final HttpServerResponse response = routingContext.response();

        LOGGER.info("Serving response file {} for URI {} with status code {}",
                responseBehaviour.getResponseFile(),
                routingContext.request().absoluteURI(),
                response.getStatusCode());

        final Path normalisedPath = normalisePath(pluginConfig, responseBehaviour.getResponseFile());

        if (responseBehaviour.isTemplate()) {
            final String responseData = responseFileCache.get(normalisedPath, () ->
                    FileUtils.readFileToString(normalisedPath.toFile(), StandardCharsets.UTF_8)
            );
            writeResponseData(resourceConfig, routingContext, normalisedPath.getFileName().toString(), responseData);
        } else {
            response.sendFile(normalisedPath.toString());
        }
    }

    /**
     * Reply with the contents of a String. Content type should be provided, but if not
     * JSON is assumed.
     *
     * @param resourceConfig    the resource configuration
     * @param routingContext    the Vert.x routing context
     * @param responseBehaviour the response behaviour
     */
    private void serveResponseData(
            ResourceConfig resourceConfig,
            RoutingContext routingContext,
            ResponseBehaviour responseBehaviour
    ) {
        LOGGER.info("Serving response data ({} bytes) for URI {} with status code {}",
                responseBehaviour.getResponseData().length(),
                routingContext.request().absoluteURI(),
                routingContext.response().getStatusCode());

        writeResponseData(resourceConfig, routingContext, null, responseBehaviour.getResponseData());
    }

    /**
     * Write the response data, optionally resolving placeholders if templating is enabled.
     *
     * @param routingContext the Vert.x routing context
     * @param responseData   the data
     */
    private void writeResponseData(
            ResourceConfig resourceConfig,
            RoutingContext routingContext,
            String filenameHintForContentType,
            String responseData
    ) {
        final HttpServerResponse response = routingContext.response();
        setContentTypeIfAbsent(resourceConfig, response, filenameHintForContentType);

        // listeners may transform response data
        if (!engineLifecycle.isEmpty()) {
            final AtomicReference<String> dataHolder = new AtomicReference<>(responseData);
            engineLifecycle.forEach(listener ->
                    dataHolder.set(listener.beforeTransmittingTemplate(routingContext, dataHolder.get()))
            );
            responseData = dataHolder.get();
        }

        response.end(Buffer.buffer(responseData));
    }

    private void setContentTypeIfAbsent(ResourceConfig resourceConfig, HttpServerResponse response, String filenameHintForContentType) {
        // explicit content type
        if (resourceConfig instanceof ContentTypedConfig) {
            final ContentTypedConfig contentTypedConfig = (ContentTypedConfig) resourceConfig;
            if (!Strings.isNullOrEmpty(contentTypedConfig.getContentType())) {
                response.putHeader(HttpUtil.CONTENT_TYPE, contentTypedConfig.getContentType());
            }
        }

        // infer from filename hint
        if (!response.headers().contains(HttpUtil.CONTENT_TYPE) && !Strings.isNullOrEmpty(filenameHintForContentType)) {
            final String contentType = MimeMapping.getMimeTypeForFilename(filenameHintForContentType);
            if (!Strings.isNullOrEmpty(contentType)) {
                LOGGER.debug("Inferred {} content type", contentType);
                response.putHeader(HttpUtil.CONTENT_TYPE, contentType);
            } else {
                // consider something like Tika to probe content type
                LOGGER.debug("Guessing JSON content type");
                response.putHeader(HttpUtil.CONTENT_TYPE, HttpUtil.CONTENT_TYPE_JSON);
            }
        }
    }

    private void fallback(
            RoutingContext routingContext,
            ResponseBehaviour responseBehaviour,
            ResponseSender[] missingResponseSenders
    ) {
        if (nonNull(missingResponseSenders)) {
            for (ResponseSender sender : missingResponseSenders) {
                try {
                    if (sender.send(routingContext, responseBehaviour)) {
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.warn("Error invoking response sender", e);
                }
            }
        }
        throw new ResponseException("All attempts to send a response failed");
    }

    private Path normalisePath(PluginConfig config, String responseFile) {
        return Paths.get(config.getParentDir().getAbsolutePath(), responseFile);
    }

    @Override
    public JsonArray loadResponseAsJsonArray(PluginConfig config, ResponseBehaviour behaviour) {
        return loadResponseAsJsonArray(config, behaviour.getResponseFile());
    }

    @Override
    public JsonArray loadResponseAsJsonArray(PluginConfig config, String responseFile) {
        if (Strings.isNullOrEmpty(responseFile)) {
            LOGGER.debug("Response file blank - returning empty array");
            return new JsonArray();
        }

        try {
            final File configPath = normalisePath(config, responseFile).toFile();
            return new JsonArray(FileUtils.readFileToString(configPath, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
