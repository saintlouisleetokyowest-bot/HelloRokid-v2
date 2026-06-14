package com.example.hellorokid.mobile.data

import com.example.hellorokid.shared.data.BusinessCard
import kotlinx.coroutines.flow.Flow

class CardRepository(private val dao: BusinessCardDao) {

    val cards: Flow<List<BusinessCardEntity>> = dao.observeAll()
    val cardCount: Flow<Int> = dao.observeCount()

    suspend fun getAll(): List<BusinessCardEntity> = dao.getAll()

    suspend fun getById(id: Long): BusinessCardEntity? = dao.getById(id)

    suspend fun insert(card: BusinessCard): Long {
        return dao.insert(BusinessCardEntity.from(card))
    }

    suspend fun updateFromBusinessCard(id: Long, card: BusinessCard) {
        val existing = dao.getById(id) ?: return
        dao.update(
            existing.copy(
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
                sourceLanguage = card.sourceLanguage.ifBlank { existing.sourceLanguage },
                nameReading = card.nameReading.ifBlank { existing.nameReading },
                companyNameEn = card.companyNameEn.ifBlank { existing.companyNameEn },
                titleLocalized = card.titleLocalized.ifBlank { existing.titleLocalized },
                departmentLocalized = card.departmentLocalized.ifBlank { existing.departmentLocalized },
                addressEn = card.addressEn.ifBlank { existing.addressEn },
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
        )
    }

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}
