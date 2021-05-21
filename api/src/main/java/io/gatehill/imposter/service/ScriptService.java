package io.gatehill.imposter.service;

import io.gatehill.imposter.plugin.config.PluginConfig;
import io.gatehill.imposter.plugin.config.resource.ResponseConfigHolder;
import io.gatehill.imposter.script.RuntimeContext;
import io.gatehill.imposter.script.ReadWriteResponseBehaviour;

/**
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */
public interface ScriptService {
    /**
     * Execute the script and read response behaviour.
     *
     * @param pluginConfig   the plugin configuration
     * @param resourceConfig the resource configuration
     * @param runtimeContext the script engine runtime context
     * @return the response behaviour
     */
    ReadWriteResponseBehaviour executeScript(PluginConfig pluginConfig, ResponseConfigHolder resourceConfig, RuntimeContext runtimeContext);
}
