package xyz.zarazaex.olc.handler

import android.content.Context
import android.content.res.AssetManager
import android.text.TextUtils
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.AppConfig.ANG_PACKAGE
import xyz.zarazaex.olc.AppConfig.DEFAULT_SUBSCRIPTION_ID
import xyz.zarazaex.olc.AppConfig.GEOIP_PRIVATE
import xyz.zarazaex.olc.AppConfig.GEOSITE_PRIVATE
import xyz.zarazaex.olc.AppConfig.TAG_DIRECT
import xyz.zarazaex.olc.AppConfig.VPN
import xyz.zarazaex.olc.dto.ProfileItem
import xyz.zarazaex.olc.dto.RulesetItem
import xyz.zarazaex.olc.dto.SubscriptionItem
import xyz.zarazaex.olc.dto.V2rayConfig
import xyz.zarazaex.olc.enums.EConfigType
import xyz.zarazaex.olc.enums.Language
import xyz.zarazaex.olc.enums.RoutingType
import xyz.zarazaex.olc.enums.VpnInterfaceAddressConfig
import xyz.zarazaex.olc.handler.MmkvManager.decodeAllServerList
import xyz.zarazaex.olc.handler.MmkvManager.decodeServerConfig
import xyz.zarazaex.olc.handler.MmkvManager.decodeSubsList
import xyz.zarazaex.olc.handler.MmkvManager.decodeSubscription
import xyz.zarazaex.olc.handler.MmkvManager.encodeSubscription
import xyz.zarazaex.olc.handler.MmkvManager.removeSubscription
import xyz.zarazaex.olc.util.JsonUtil
import xyz.zarazaex.olc.util.Utils
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.Locale

object SettingsManager {

    fun initApp(context: Context) {
        ensureDefaultSettings()
        initRoutingRulesets(context)
        migrateServerListToSubscriptions()
        migrateHysteria2PinSHA256()
        migrateAutoSort()
        migrateDelayTestUrl()
    }

    private fun initRoutingRulesets(context: Context) {
        val exist = MmkvManager.decodeRoutingRulesets()
        if (exist.isNullOrEmpty()) {
            val rulesetList = getPresetRoutingRulesets(context)
            MmkvManager.encodeRoutingRulesets(rulesetList)
        }
    }

    private fun getPresetRoutingRulesets(context: Context, index: Int = 0): MutableList<RulesetItem>? {
        val fileName = RoutingType.fromIndex(index).fileName
        val assets = Utils.readTextFromAssets(context, fileName)
        if (TextUtils.isEmpty(assets)) return null
        return JsonUtil.fromJson(assets, Array<RulesetItem>::class.java)?.toMutableList()
    }

    fun resetRoutingRulesetsFromPresets(context: Context, index: Int) {
        val rulesetList = getPresetRoutingRulesets(context, index) ?: return
        resetRoutingRulesetsCommon(rulesetList)
    }

    fun resetRoutingRulesets(content: String?): Boolean {
        if (content.isNullOrEmpty()) return false
        try {
            val rulesetList = JsonUtil.fromJson(content, Array<RulesetItem>::class.java)?.toMutableList()
            if (rulesetList.isNullOrEmpty()) return false
            resetRoutingRulesetsCommon(rulesetList)
            return true
        } catch (e: Exception) {
            Log.e(ANG_PACKAGE, "Failed to reset routing rulesets", e)
            return false
        }
    }

    private fun resetRoutingRulesetsCommon(rulesetList: MutableList<RulesetItem>) {
        val rulesetNew: MutableList<RulesetItem> = mutableListOf()
        MmkvManager.decodeRoutingRulesets()?.forEach { key ->
            if (key.locked == true) rulesetNew.add(key)
        }
        rulesetNew.addAll(rulesetList)
        MmkvManager.encodeRoutingRulesets(rulesetNew)
    }

    fun getRoutingRuleset(index: Int): RulesetItem? {
        if (index < 0) return null
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return null
        return rulesetList[index]
    }

    fun saveRoutingRuleset(index: Int, ruleset: RulesetItem?) {
        if (ruleset == null) return
        var rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) rulesetList = mutableListOf()
        if (index < 0 || index >= rulesetList.count()) {
            rulesetList.add(0, ruleset)
        } else {
            rulesetList[index] = ruleset
        }
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    fun removeRoutingRuleset(index: Int) {
        if (index < 0) return
        val rulesetList = MmkvManager.decodeRoutingRulesets()
        if (rulesetList.isNullOrEmpty()) return
        rulesetList.removeAt(index)
        MmkvManager.encodeRoutingRulesets(rulesetList)
    }

    fun routingRulesetsBypassLan(): Boolean {
        val vpnBypassLan = MmkvManager.decodeSettingsString(AppConfig.PREF_VPN_BYPASS_LAN) ?: "1"
        if (vpnBypassLan == "1") return true
        if (vpnBypassLan == "2") return false

        val guid = MmkvManager.getSelectServer() ?: return false
        val config = decodeServerConfig(guid) ?: return false
        if (config.configType == EConfigType.CUSTOM) {
            val raw = MmkvManager.decodeServerRaw(guid) ?: return false
            val v2rayConfig = JsonUtil.fromJson(raw, V2ray
