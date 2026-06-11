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

    suspend fun deleteById(id: Long) {
        dao.deleteById(id)
    }
}
