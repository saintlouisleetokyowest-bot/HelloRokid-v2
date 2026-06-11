package com.example.hellorokid.mobile.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.hellorokid.mobile.R
import com.example.hellorokid.mobile.data.BusinessCardEntity
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CardListAdapter(
    private val onItemClick: (BusinessCardEntity) -> Unit,
    private val onItemLongClick: (BusinessCardEntity) -> Boolean
) : ListAdapter<BusinessCardEntity, CardListAdapter.CardViewHolder>(DiffCallback) {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_business_card, parent, false)
        return CardViewHolder(view)
    }

    override fun onBindViewHolder(holder: CardViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView as MaterialCardView
        private val nameText: TextView = itemView.findViewById(R.id.cardNameText)
        private val titleText: TextView = itemView.findViewById(R.id.cardTitleText)
        private val companyText: TextView = itemView.findViewById(R.id.cardCompanyText)
        private val metaText: TextView = itemView.findViewById(R.id.cardMetaText)
        private val industryChip: TextView = itemView.findViewById(R.id.cardIndustryText)

        fun bind(card: BusinessCardEntity) {
            nameText.text = card.name.ifBlank { "未识别姓名" }
            titleText.text = card.title.ifBlank { "职位未知" }
            companyText.text = card.company.ifBlank { "公司未知" }
            metaText.text = dateFormat.format(Date(card.scannedAt))

            if (card.industry.isNotBlank()) {
                industryChip.visibility = View.VISIBLE
                industryChip.text = card.industry
            } else {
                industryChip.visibility = View.GONE
            }

            cardView.setOnClickListener { onItemClick(card) }
            cardView.setOnLongClickListener { onItemLongClick(card) }
        }
    }

    companion object {
        private val DiffCallback = object : DiffUtil.ItemCallback<BusinessCardEntity>() {
            override fun areItemsTheSame(oldItem: BusinessCardEntity, newItem: BusinessCardEntity): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(oldItem: BusinessCardEntity, newItem: BusinessCardEntity): Boolean {
                return oldItem == newItem
            }
        }
    }
}
