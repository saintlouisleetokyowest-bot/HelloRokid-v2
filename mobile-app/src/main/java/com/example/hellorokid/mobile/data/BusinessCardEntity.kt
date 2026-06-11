package com.example.hellorokid.mobile.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.hellorokid.shared.data.BusinessCard

@Entity(tableName = "business_cards")
data class BusinessCardEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val scannedAt: Long = System.currentTimeMillis(),
    val name: String = "",
    val title: String = "",
    val department: String = "",
    val company: String = "",
    val phone: String = "",
    val mobile: String = "",
    val fax: String = "",
    val email: String = "",
    val address: String = "",
    val website: String = "",
    val industry: String = "",
    val companySize: String = "",
    val revenue: String = "",
    val coreBusiness: String = "",
    val markets: String = "",
    val partners: String = "",
    val opportunities: String = "",
    val investmentReadiness: String = "",
    val timing: String = ""
) {
    fun toBusinessCard(): BusinessCard = BusinessCard(
        name = name,
        title = title,
        department = department,
        company = company,
        phone = phone,
        mobile = mobile,
        fax = fax,
        email = email,
        address = address,
        website = website,
        industry = industry,
        companySize = companySize,
        revenue = revenue,
        coreBusiness = coreBusiness,
        markets = markets,
        partners = partners,
        opportunities = opportunities,
        investmentReadiness = investmentReadiness,
        timing = timing
    )

    companion object {
        fun from(card: BusinessCard, scannedAt: Long = System.currentTimeMillis()): BusinessCardEntity {
            return BusinessCardEntity(
                scannedAt = scannedAt,
                name = card.name,
                title = card.title,
                department = card.department,
                company = card.company,
                phone = card.phone,
                mobile = card.mobile,
                fax = card.fax,
                email = card.email,
                address = card.address,
                website = card.website,
                industry = card.industry,
                companySize = card.companySize,
                revenue = card.revenue,
                coreBusiness = card.coreBusiness,
                markets = card.markets,
                partners = card.partners,
                opportunities = card.opportunities,
                investmentReadiness = card.investmentReadiness,
                timing = card.timing
            )
        }
    }
}
