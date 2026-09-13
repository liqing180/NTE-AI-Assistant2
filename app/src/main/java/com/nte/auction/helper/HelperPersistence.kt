package com.nte.auction.helper

import android.content.Context
import com.nte.auction.helper.HelperMemoryGroup
import com.nte.auction.helper.HelperMemoryRecord
import com.nte.auction.helper.HelperMemorySnapshot
import org.json.JSONArray
import org.json.JSONObject

internal class HelperPersistence(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadMemory(): HelperMemorySnapshot {
        val raw = prefs.getString(KEY_MEMORY, null) ?: return HelperMemorySnapshot()
        return runCatching { decodeMemory(JSONObject(raw)) }.getOrElse { HelperMemorySnapshot() }
    }

    fun saveMemory(snapshot: HelperMemorySnapshot) {
        prefs.edit().putString(KEY_MEMORY, encodeMemory(snapshot).toString()).apply()
    }

    fun loadCustomBids(): List<Long> {
        val raw = prefs.getString(KEY_CUSTOM_BIDS, null) ?: return NteHelperCatalog.defaultCustomBids
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val value = array.optLong(index, -1L)
                    if (value > 0L && value !in this) add(value)
                }
            }
        }.getOrElse { NteHelperCatalog.defaultCustomBids }
    }

    fun saveCustomBids(values: List<Long>) {
        val normalized = values.filter { it > 0L }.distinct()
        prefs.edit().putString(KEY_CUSTOM_BIDS, JSONArray(normalized).toString()).apply()
    }

    private fun encodeMemory(snapshot: HelperMemorySnapshot): JSONObject = JSONObject().apply {
        put("schema_version", 2)
        put("current_group", snapshot.currentGroup)
        put("active_groups", JSONArray(snapshot.activeGroups.toList()))
        put("groups", JSONArray().apply {
            snapshot.groups.forEach { group ->
                put(JSONObject().apply {
                    put("name", group.name)
                    put("created", group.created)
                    put("weight", group.weight)
                    group.priceWeights?.let { put("price_weights", JSONArray(it)) }
                    put("records", JSONArray().apply {
                        group.records.forEach { record ->
                            put(JSONObject().apply {
                                put("prices", JSONArray(record.prices))
                                put("timestamp", record.timestamp)
                                record.source?.let { put("source", it) }
                            })
                        }
                    })
                })
            }
        })
    }

    private fun decodeMemory(root: JSONObject): HelperMemorySnapshot {
        val groupsJson = root.optJSONArray("groups") ?: JSONArray()
        val groups = buildList {
            for (groupIndex in 0 until groupsJson.length()) {
                val json = groupsJson.optJSONObject(groupIndex) ?: continue
                val name = json.optString("name").trim()
                if (name.isEmpty()) continue
                val recordsJson = json.optJSONArray("records") ?: JSONArray()
                val records = buildList {
                    for (recordIndex in 0 until recordsJson.length()) {
                        val recordJson = recordsJson.optJSONObject(recordIndex) ?: continue
                        val priceArray = recordJson.optJSONArray("prices") ?: JSONArray()
                        val prices = buildList {
                            for (priceIndex in 0 until priceArray.length()) {
                                val price = priceArray.optLong(priceIndex, -1L)
                                if (price >= 0L) add(price)
                            }
                        }
                        if (prices.isNotEmpty()) {
                            add(
                                HelperMemoryRecord(
                                    prices = prices,
                                    timestamp = recordJson.optString("timestamp"),
                                    source = recordJson.optString("source").takeIf { it.isNotBlank() },
                                )
                            )
                        }
                    }
                }
                val weightsJson = json.optJSONArray("price_weights")
                val weights = weightsJson?.let { array ->
                    buildList {
                        for (index in 0 until array.length()) add(array.optDouble(index, 0.0).coerceAtLeast(0.0))
                    }
                }
                add(
                    HelperMemoryGroup(
                        name = name,
                        created = json.optString("created"),
                        records = records,
                        weight = json.optDouble("weight", 1.0).coerceIn(0.0, 10.0),
                        priceWeights = weights,
                    )
                )
            }
        }.ifEmpty { listOf(HelperMemoryGroup(HelperMemorySnapshot.DEFAULT_GROUP)) }

        val activeJson = root.optJSONArray("active_groups") ?: JSONArray()
        val active = buildSet {
            for (index in 0 until activeJson.length()) {
                val name = activeJson.optString(index).trim()
                if (groups.any { it.name == name }) add(name)
            }
        }.ifEmpty { setOf(groups.first().name) }
        val current = root.optString("current_group").takeIf { name -> groups.any { it.name == name } }
            ?: active.firstOrNull()
            ?: groups.first().name
        return HelperMemorySnapshot(groups = groups, activeGroups = active, currentGroup = current)
    }

    private companion object {
        const val PREFS = "nte_helper"
        const val KEY_MEMORY = "memory_v2"
        const val KEY_CUSTOM_BIDS = "custom_bids_v1"
    }
}
