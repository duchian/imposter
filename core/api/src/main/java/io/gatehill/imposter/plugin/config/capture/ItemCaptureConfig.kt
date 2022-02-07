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
package io.gatehill.imposter.plugin.config.capture

import com.fasterxml.jackson.annotation.JsonProperty
import io.gatehill.imposter.plugin.config.flex.FlexibleTypeUtil.lazyParse
import io.gatehill.imposter.plugin.config.store.PersistencePhase

/**
 * A capture configuration for values, that allows the key and store to be overridden.
 *
 * @author Pete Cornish
 */
class ItemCaptureConfig(
    pathParam: String? = null,
    queryParam: String? = null,
    requestHeader: String? = null,
    jsonPath: String? = null,
    expression: String? = null,
    constValue: String? = null,

    /**
     * Can be a [String] or a [CaptureConfig]. Use [key] instead.
     */
    @field:JsonProperty("key")
    private val _key: Any? = null,

    /**
     * Can be a [String] or a [CaptureConfig]. Use [store] instead.
     */
    @field:JsonProperty("store")
    private val _store: Any? = null,

    @field:JsonProperty("enabled")
    val enabled: Boolean = true,

    @JsonProperty("phase")
    val phase: PersistencePhase = PersistencePhase.REQUEST_RECEIVED,
) : CaptureConfig(
    pathParam,
    queryParam,
    requestHeader,
    jsonPath,
    expression,
    constValue,
) {
    val key: CaptureConfig? by lazyParse(_key, CaptureConfig)
    val store: CaptureConfig? by lazyParse(_store, CaptureConfig)
}
