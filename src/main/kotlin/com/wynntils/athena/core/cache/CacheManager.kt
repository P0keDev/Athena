package com.wynntils.athena.core.cache

import com.wynntils.athena.cacheDatabase
import com.wynntils.athena.core.asSimpleJson
import com.wynntils.athena.core.cache.annotations.CacheInfo
import com.wynntils.athena.core.cache.data.CacheContainer
import com.wynntils.athena.core.cache.interfaces.DataCache
import com.wynntils.athena.core.currentTimeMillis
import com.wynntils.athena.core.enums.Hash
import com.wynntils.athena.core.getOrCreate
import com.wynntils.athena.core.runAsync
import com.wynntils.athena.core.utils.JSONOrderedObject
import com.wynntils.athena.core.utils.Logger
import com.wynntils.athena.errorLog
import org.json.simple.JSONAware
import org.json.simple.JSONObject
import java.nio.charset.StandardCharsets
import kotlin.reflect.full.findAnnotation

object CacheManager {

    private val loadedCaches = HashMap<String, CacheContainer>()
    private val cacheTable = cacheDatabase.getOrCreateTable("data")
    private val cacheLog = Logger("cache")

    /**
     * Refreshes and registers cache class
     * Cache classes needs to implement DataCache and have the annotation CacheInfo on it's header
     */
    fun refreshCache(cache: DataCache) {
        val info = cache.javaClass.kotlin.findAnnotation<CacheInfo>() ?: return

        fun registerResult(result: JSONAware) {
            val resultString = result.toJSONString()
            val resultHash = Hash.MD5.hash(resultString.toByteArray(StandardCharsets.UTF_8))
            val nextRefresh = if (info.refreshRate == 0) 0L else {
                    currentTimeMillis() + (1000 * info.refreshRate)
            }

            cacheTable.insertFile("${info.name}.json", resultString.toByteArray(StandardCharsets.UTF_8), true)
            loadedCaches[info.name] = CacheContainer(result, resultHash, nextRefresh, cache)
        }

        fun runExecutor() {
            try {
                val ms = currentTimeMillis()
                val generated = cache.generateCache()

                // applying the request stuff
                if (generated is JSONObject) {
                    val request = generated.getOrCreate<JSONObject>("request")
                    request["lastRefresh"] = ms
                } else if (generated is JSONOrderedObject) {
                    val request = generated.getOrCreate<JSONOrderedObject>("request")
                    request["lastRefresh"] = ms
                }

                registerResult(generated)
                cacheLog.info("Successfully Refreshed ${info.name} cache in ${currentTimeMillis() - ms}ms.")
            } catch (ex: Exception) {
                errorLog.exception("Caught an error while trying to refresh cache ${info.name}", ex)

                if (cacheTable.hasFile("${info.name}.json"))
                    registerResult(cacheTable.getFile("${info.name}.json")!!.asString().asSimpleJson())
            }
        }

        if (info.async) {
            runAsync { runExecutor() }
            return
        }

        runExecutor()
    }

    /**
     * @return the set of available caches
     */
    fun getCaches(): MutableSet<MutableMap.MutableEntry<String, CacheContainer>> {
        return loadedCaches.entries
    }

    /**
     * @param name the cache name
     * @return the cache container if available
     */
    fun getCache(name: String): CacheContainer? {
        val cache = loadedCaches[name] ?: return null

        if (cache.nextRefresh != 0L && currentTimeMillis() >= cache.nextRefresh) {
            cache.nextRefresh = 0 // avoids that an async thread executes the refresh multiple times
            refreshCache(cache.holderReference)
        }

        if (cache.value is JSONOrderedObject)
            (cache.value["request"] as JSONOrderedObject)["timeStamp"] = currentTimeMillis()

        return loadedCaches[name]
    }

    /**
     * @param name the cache name
     * @return if the provided cache is available
     */
    fun isCached(name: String): Boolean {
        return loadedCaches.containsKey(name)
    }

}