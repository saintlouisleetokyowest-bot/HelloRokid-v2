package com.example.hellorokid.shared.data

import org.json.JSONObject

/** BusinessCard ↔ JSON，供 BLE 结果回传使用 */
object BusinessCardJson {

    private const val KEY_ERROR = "_error"

    fun toJson(card: BusinessCard): String {
        return JSONObject()
            .put("name", card.name)
            .put("title", card.title)
            .put("department", card.department)
            .put("company", card.company)
            .put("phone", card.phone)
            .put("mobile", card.mobile)
            .put("fax", card.fax)
            .put("email", card.email)
            .put("address", card.address)
            .put("website", card.website)
            .put("industry", card.industry)
            .put("companySize", card.companySize)
            .put("revenue", card.revenue)
            .put("coreBusiness", card.coreBusiness)
            .put("markets", card.markets)
            .put("partners", card.partners)
            .put("opportunities", card.opportunities)
            .put("investmentReadiness", card.investmentReadiness)
            .put("timing", card.timing)
            .toString()
    }

    fun errorJson(message: String): String {
        return JSONObject().put(KEY_ERROR, message).toString()
    }

    fun parseError(json: String): String? {
        val error = JSONObject(json).optString(KEY_ERROR, "")
        return error.ifBlank { null }
    }

    fun fromJson(json: String): BusinessCard {
        val obj = JSONObject(json)
        return BusinessCard(
            name = obj.optString("name", ""),
            title = obj.optString("title", ""),
            department = obj.optString("department", ""),
            company = obj.optString("company", ""),
            phone = obj.optString("phone", ""),
            mobile = obj.optString("mobile", ""),
            fax = obj.optString("fax", ""),
            email = obj.optString("email", ""),
            address = obj.optString("address", ""),
            website = obj.optString("website", ""),
            industry = obj.optString("industry", ""),
            companySize = obj.optString("companySize", ""),
            revenue = obj.optString("revenue", ""),
            coreBusiness = obj.optString("coreBusiness", ""),
            markets = obj.optString("markets", ""),
            partners = obj.optString("partners", ""),
            opportunities = obj.optString("opportunities", ""),
            investmentReadiness = obj.optString("investmentReadiness", ""),
            timing = obj.optString("timing", "")
        )
    }
}
