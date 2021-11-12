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
package io.gatehill.imposter.plugin

import com.google.common.collect.Lists
import com.google.inject.Injector
import com.google.inject.Module
import io.gatehill.imposter.ImposterConfig
import io.gatehill.imposter.plugin.PluginMetadata.getPluginName
import io.gatehill.imposter.plugin.config.ConfigurablePlugin
import io.gatehill.imposter.util.ConfigUtil
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassInfo
import org.apache.logging.log4j.LogManager
import java.io.File
import java.util.*
import java.util.regex.Pattern

/**
 * @author Pete Cornish
 */
class PluginManager {
    private val classpathPlugins: MutableMap<String?, String> = mutableMapOf()
    private val pluginClasses: MutableSet<Class<out Plugin>> = mutableSetOf()
    private val providers: MutableSet<Class<out PluginProvider>> = mutableSetOf()
    private val plugins: MutableMap<String, Plugin> = mutableMapOf()
    private var hasScannedForPlugins = false

    /**
     * Determines the plugin class if it matches its short name, otherwise assumes
     * the plugin is a fully qualified class name.
     *
     * @param plugin the plugin short name or fully qualified class name
     * @return the fully qualified plugin class name
     */
    fun determinePluginClass(plugin: String): String {
        if (!hasScannedForPlugins) {
            synchronized(classpathPlugins) {
                if (!hasScannedForPlugins) { // double-guard
                    classpathPlugins.putAll(discoverClasspathPlugins())
                    hasScannedForPlugins = true
                }
            }
        }
        return classpathPlugins[plugin] ?: replaceLegacyPackage(plugin)
    }

    /**
     * Shim plugin packages from legacy [LEGACY_PACKAGE] to current.
     *
     * @param plugin plugin FQCN
     * @return shimmed plugin FQCN
     */
    private fun replaceLegacyPackage(plugin: String): String {
        return legacyPackagePattern.matcher(plugin).replaceAll("${ConfigUtil.CURRENT_PACKAGE}.")
    }

    /**
     * Finds plugins on the classpath annotated with [PluginInfo].
     *
     * @return a map of plugin short names to full qualified class names
     */
    private fun discoverClasspathPlugins(): Map<String, String> {
        val pluginClasses: MutableMap<String, String> = mutableMapOf()

        ClassGraph().enableClassInfo().enableAnnotationInfo()
            .whitelistPackages(PLUGIN_BASE_PACKAGE).scan().use { result ->
                val pluginClassInfos = result
                    .getClassesImplementing(Plugin::class.qualifiedName)
                    .filter { classInfo: ClassInfo -> classInfo.hasAnnotation(PluginInfo::class.qualifiedName) }

                for (pluginClassInfo in pluginClassInfos) {
                    try {
                        val pluginName = pluginClassInfo.annotationInfo[0].parameterValues[0].value
                        pluginClasses[pluginName as String] = pluginClassInfo.name
                    } catch (e: Exception) {
                        LOGGER.warn("Error reading plugin class info for: {}", pluginClassInfo.name, e)
                    }
                }
            }

        if (LOGGER.isTraceEnabled) {
            LOGGER.trace("Annotated plugins on classpath: {}", pluginClasses)
        }
        return pluginClasses
    }

    fun registerClass(plugin: Class<out Plugin>): Boolean {
        return pluginClasses.add(plugin)
    }

    fun getPluginClasses(): Collection<Class<out Plugin>> {
        return Collections.unmodifiableCollection(pluginClasses)
    }

    fun registerInstance(instance: Plugin) {
        plugins[instance.javaClass.canonicalName] = instance
    }

    fun getPlugins(): Collection<Plugin> {
        return Collections.unmodifiableCollection(plugins.values)
    }

    @Suppress("UNCHECKED_CAST")
    fun <P : Plugin?> getPlugin(pluginClassName: String): P? {
        return plugins[pluginClassName] as P?
    }

    fun registerProvider(provider: Class<out PluginProvider>) {
        providers.add(provider)
    }

    fun isProviderRegistered(provider: Class<out PluginProvider>): Boolean {
        return providers.contains(provider)
    }

    /**
     * Registers plugin providers and discovers dependencies from configuration.
     *
     * @param imposterConfig the Imposter engine configuration
     * @param plugins        configured plugins
     * @param pluginConfigs  plugin configurations
     * @return list of dependencies
     */
    fun preparePluginsFromConfig(
        imposterConfig: ImposterConfig,
        plugins: List<String>,
        pluginConfigs: Map<String, MutableList<File>>
    ): List<PluginDependencies> {

        // prepare plugins
        plugins.map { plugin -> determinePluginClass(plugin) }.forEach { className -> registerPluginClass(className) }

        val dependencies: MutableList<PluginDependencies> = Lists.newArrayList()

        dependencies.addAll(getPluginClasses().map { pluginClass -> examinePlugin(pluginClass) })

        findUnregisteredProviders().forEach { providerClass: Class<PluginProvider> ->
            registerProvider(providerClass)
            val pluginProvider = createPluginProvider(providerClass)
            val provided = pluginProvider.providePlugins(imposterConfig, pluginConfigs)
            LOGGER.trace("{} plugin(s) provided by: {}", provided.size, getPluginName(providerClass))

            // recurse for new providers
            if (provided.isNotEmpty()) {
                dependencies.addAll(preparePluginsFromConfig(imposterConfig, provided, pluginConfigs))
            }
        }
        return dependencies
    }

    @Suppress("UNCHECKED_CAST")
    private fun registerPluginClass(className: String) {
        try {
            val clazz = Class.forName(className) as Class<out Plugin>
            if (registerClass(clazz)) {
                val pluginName = getPluginName(clazz)
                LOGGER.trace("Registered plugin: {} with class: {}", pluginName, className)
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to register plugin: $className", e)
        }
    }

    /**
     * Examine the plugin for any required dependencies.
     *
     * @param pluginClass the plugin to examine
     * @return the plugin's dependencies
     */
    private fun examinePlugin(pluginClass: Class<out Plugin>): PluginDependencies {
        val moduleAnnotation = pluginClass.getAnnotation(RequireModules::class.java)
        val requiredModules = if (null != moduleAnnotation && moduleAnnotation.value.isNotEmpty()) {
            instantiateModules(moduleAnnotation)
        } else {
            emptyList()
        }
        return PluginDependencies(requiredModules)
    }

    private fun instantiateModules(moduleAnnotation: RequireModules): List<Module> {
        val modules = mutableListOf<Module>()
        for (moduleClass in moduleAnnotation.value) {
            try {
                modules.add(moduleClass.java.getDeclaredConstructor().newInstance())
            } catch (e: Exception) {
                throw RuntimeException(e)
            }
        }
        return modules
    }

    /**
     * @return any [PluginProvider]s not yet registered with the plugin manager
     */
    private fun findUnregisteredProviders(): List<Class<PluginProvider>> {
        return getPluginClasses().filter { cls ->
            PluginProvider::class.java.isAssignableFrom(cls)
        }.map { pluginClass ->
            @Suppress("UNCHECKED_CAST")
            pluginClass.asSubclass(PluginProvider::class.java) as Class<PluginProvider>
        }.filter { providerClass ->
            !isProviderRegistered(providerClass)
        }
    }

    private fun createPluginProvider(providerClass: Class<PluginProvider>): PluginProvider {
        return try {
            providerClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            throw RuntimeException("Error instantiating plugin provider: ${providerClass.canonicalName}", e)
        }
    }

    /**
     * Instantiate all plugins and register them with the plugin manager.
     *
     * @param injector the injector from which the plugins can be instantiated
     */
    fun registerPlugins(injector: Injector) {
        getPluginClasses().forEach { pluginClass: Class<out Plugin> ->
            try {
                registerInstance(injector.getInstance(pluginClass))
            } catch (e: Exception) {
                throw RuntimeException("Error registering plugin: $pluginClass", e)
            }
        }

        val pluginCount = getPlugins().size
        if (pluginCount > 0) {
            val pluginNames = getPlugins().joinToString(", ", "[", "]") { p: Plugin ->
                getPluginName(p.javaClass)
            }

            LOGGER.trace("Loaded {} plugin(s): {}", pluginCount, pluginNames)
        } else {
            throw IllegalStateException("No plugins were loaded")
        }
    }

    /**
     * Send config to plugins.
     *
     * @param pluginConfigs configurations keyed by plugin
     */
    fun configurePlugins(pluginConfigs: Map<String, MutableList<File>>) {
        getPlugins()
            .filter { plugin: Plugin -> plugin is ConfigurablePlugin<*> }
            .map { plugin: Plugin -> plugin as ConfigurablePlugin<*> }
            .forEach { plugin ->
                try {
                    val configFiles = pluginConfigs[plugin.javaClass.canonicalName] ?: emptyList()
                    plugin.loadConfiguration(configFiles)
                } catch (e: Exception) {
                    throw RuntimeException("Error configuring plugin: ${getPluginName(plugin.javaClass)}", e)
                }
            }
    }

    companion object {
        private val LOGGER = LogManager.getLogger(PluginManager::class.java)

        /**
         * The base package to scan recursively for plugins.
         */
        private const val PLUGIN_BASE_PACKAGE = ConfigUtil.CURRENT_PACKAGE + ".plugin"

        /**
         * Package for legacy plugins.
         */
        private const val LEGACY_PACKAGE = "com.gatehill.imposter"

        private val legacyPackagePattern = Pattern.compile("^$LEGACY_PACKAGE\\.")
    }
}