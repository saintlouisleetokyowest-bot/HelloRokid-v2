package com.example.hellorokid.mobile.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.hellorokid.mobile.HelloRokidApplication
import com.example.hellorokid.mobile.R
import com.example.hellorokid.mobile.data.BusinessCardEntity
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CardDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CARD_ID = "extra_card_id"

        fun intent(context: android.content.Context, cardId: Long): Intent {
            return Intent(context, CardDetailActivity::class.java).apply {
                putExtra(EXTRA_CARD_ID, cardId)
            }
        }
    }

    private val repository by lazy {
        (application as HelloRokidApplication).cardRepository
    }

    private var cardId: Long = -1
    private var currentCard: BusinessCardEntity? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_card_detail)

        cardId = intent.getLongExtra(EXTRA_CARD_ID, -1)
        if (cardId < 0) {
            finish()
            return
        }

        val toolbar = findViewById<MaterialToolbar>(R.id.detailToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        loadCard()
    }

    private fun loadCard() {
        lifecycleScope.launch {
            val card = repository.getById(cardId)
            if (card == null) {
                Toast.makeText(this@CardDetailActivity, R.string.error_card_not_found, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            currentCard = card
            bindCard(card)
        }
    }

    private fun bindCard(card: BusinessCardEntity) {
        supportActionBar?.title = card.name.ifBlank { getString(R.string.card_detail_title) }
        findViewById<TextView>(R.id.detailScannedAt).text =
            getString(R.string.detail_scanned_at, dateFormat.format(Date(card.scannedAt)))

        setField(R.id.detailName, CardDisplayHelper.withSecondary(card.name, card.nameReading))
        setField(R.id.detailTitle, CardDisplayHelper.withSecondary(card.title, card.titleLocalized))
        setField(R.id.detailDepartment, CardDisplayHelper.withSecondary(card.department, card.departmentLocalized))
        setField(R.id.detailCompany, CardDisplayHelper.withSecondary(card.company, card.companyNameEn))
        setField(R.id.detailPhone, card.phone)
        setField(R.id.detailMobile, card.mobile)
        setField(R.id.detailFax, card.fax)
        setField(R.id.detailEmail, card.email)
        setField(R.id.detailAddress, CardDisplayHelper.withSecondary(card.address, card.addressEn))
        setField(R.id.detailWebsite, card.website)
        setField(R.id.detailIndustry, card.industry)
        setField(R.id.detailCompanySize, card.companySize)
        setField(R.id.detailRevenue, card.revenue)
        setField(R.id.detailCoreBusiness, card.coreBusiness)
        setField(R.id.detailMarkets, card.markets)
        setField(R.id.detailPartners, card.partners)
        setField(R.id.detailOpportunities, card.opportunities)
        setField(R.id.detailInvestment, card.investmentReadiness)
        setField(R.id.detailTiming, card.timing)
    }

    private fun setField(viewId: Int, value: String) {
        findViewById<TextView>(viewId).text = value.ifBlank { getString(R.string.field_empty) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.detail_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_call -> {
                dialPhone()
                true
            }
            R.id.action_email -> {
                sendEmail()
                true
            }
            R.id.action_delete -> {
                confirmDelete()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun dialPhone() {
        val card = currentCard ?: return
        val number = card.mobile.trim().ifBlank { card.phone.trim() }
        if (number.isBlank()) {
            Toast.makeText(this, R.string.no_phone, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    private fun sendEmail() {
        val email = currentCard?.email?.trim().orEmpty()
        if (email.isBlank()) {
            Toast.makeText(this, R.string.no_email, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_card_title)
            .setMessage(R.string.delete_card_message)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteById(cardId)
                    Toast.makeText(this@CardDetailActivity, R.string.card_deleted, Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
