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
package io.gatehill.imposter.util

import org.apache.logging.log4j.LogManager

/**
 * @author Pete Cornish
 */
object FeatureUtil {
    private const val ENV_IMPOSTER_FEATURES = "IMPOSTER_FEATURES"
    const val SYS_PROP_IMPOSTER_FEATURES = "imposter.features"
    private val LOGGER = LogManager.getLogger(FeatureUtil::class.java)

    /**
     * Determines which features are enabled by default.
     */
    private val DEFAULT_FEATURES: Map<String, Boolean> = mapOf(
        MetricsUtil.FEATURE_NAME_METRICS to true,
        "stores" to true
    )

    /**
     * Holds the enabled status of the features, keyed by name.
     */
    private var FEATURES: Map<String, Boolean>? = null

    fun refresh() {
        val overrides: Map<String, Boolean> = listOverrides()

        FEATURES = DEFAULT_FEATURES.entries.associate { (key, value) ->
            key to (overrides[key] ?: value)
        }

        LOGGER.trace("Features: {}", FEATURES)
    }

    @JvmStatic
    fun isFeatureEnabled(featureName: String): Boolean {
        return FEATURES!![featureName] ?: false
    }

    /**
     * Converts a string list from environment variable [.ENV_IMPOSTER_FEATURES] or
     * system property [.SYS_PROP_IMPOSTER_FEATURES] to a [Map].
     *
     *
     * For example:
     * <pre>
     * "foo=true,bar=false"
    </pre> *
     *
     *
     * becomes:
     * <pre>
     * [foo: true, bar: false]
    </pre> *
     *
     * @return a map of feature name to enabled status
     */
    private fun listOverrides(): Map<String, Boolean> {
        val features = System.getProperty(SYS_PROP_IMPOSTER_FEATURES, EnvVars.getEnv(ENV_IMPOSTER_FEATURES))
            ?.split(",") ?: emptyList()

        return features.filter { entry: String -> entry.contains("=") }
            .map { entry: String -> entry.trim { it <= ' ' }.split("=").toTypedArray() }
            .associate { (k, v) -> k to v.toBoolean() }
    }

    @JvmStatic
    fun disableFeature(featureName: String) {
        overrideFeature(featureName, false)
    }

    fun overrideFeature(featureName: String, enabled: Boolean) {
        val overrides = listOverrides().toMutableMap()
        overrides[featureName] = enabled
        System.setProperty(
            SYS_PROP_IMPOSTER_FEATURES, overrides.entries.joinToString(",") { (key, value) -> "$key=$value" }
        )
        LOGGER.debug("Overriding feature: {}={}", featureName, enabled)
        refresh()
    }

    @JvmStatic
    fun clearSystemPropertyOverrides() {
        LOGGER.debug("Clearing system property feature overrides")
        System.clearProperty(SYS_PROP_IMPOSTER_FEATURES)
        refresh()
    }

    init {
        refresh()
    }
}