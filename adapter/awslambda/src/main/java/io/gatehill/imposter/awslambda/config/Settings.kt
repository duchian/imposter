/*
 * Copyright (c) 2021-2023.
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

package io.gatehill.imposter.awslambda.config

import io.gatehill.imposter.config.util.EnvVars
import io.gatehill.imposter.util.splitOnCommaAndTrim

/**
 * @author Pete Cornish
 */
object Settings {
    val configDir: String? get() = EnvVars.getEnv("IMPOSTER_CONFIG_DIR")

    /**
     * FQCN of [io.gatehill.imposter.plugin.PluginDiscoveryStrategy] implementation.
     */
    val pluginDiscoveryStrategyClass: String? get() =
        EnvVars.getEnv("IMPOSTER_PLUGIN_DISCOVERY_STRATEGY")

    val s3ConfigUrl: String get() =
        EnvVars.getEnv("IMPOSTER_S3_CONFIG_URL")
            ?: throw IllegalStateException("Missing S3 configuration URL")

    val metaInfScan: Boolean get() =
        EnvVars.getEnv("IMPOSTER_METAINF_SCAN")?.toBoolean() ?: false

    /**
     * Example:
     *
     *     plugin1=io.gatehill.imposter.plugin.Plugin1,plugin2=io.gatehill.imposter.plugin.Plugin2
     */
    val additionalPlugins: Map<String, String>? get() =
        EnvVars.getEnv("IMPOSTER_STATIC_PLUGINS")?.splitOnCommaAndTrim()?.map {
            val entry = it.split("=")
            return@map entry[0].trim() to entry[1].trim()
        }?.toMap()

    /**
     * Example:
     *
     *     io.gatehill.imposter.plugin.Module1,io.gatehill.imposter.plugin.Module2
     */
    val additionalModules: List<String>? get() =
        EnvVars.getEnv("IMPOSTER_STATIC_MODULES")?.splitOnCommaAndTrim()
}
