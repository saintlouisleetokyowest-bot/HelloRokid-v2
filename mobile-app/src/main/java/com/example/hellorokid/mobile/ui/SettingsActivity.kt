package com.example.hellorokid.mobile.ui

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.example.hellorokid.mobile.R
import com.example.hellorokid.mobile.locale.AppLocaleManager
import com.google.android.material.appbar.MaterialToolbar

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val radioGroup = findViewById<RadioGroup>(R.id.languageRadioGroup)
        val currentTag = AppLocaleManager.getLanguageTag(this)

        val selectedButtonId = when (currentTag) {
            AppLocaleManager.LANG_EN -> R.id.languageEn
            AppLocaleManager.LANG_JA -> R.id.languageJa
            else -> R.id.languageZh
        }
        findViewById<RadioButton>(selectedButtonId).isChecked = true

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.languageZh -> AppLocaleManager.LANG_ZH
                R.id.languageEn -> AppLocaleManager.LANG_EN
                R.id.languageJa -> AppLocaleManager.LANG_JA
                else -> return@setOnCheckedChangeListener
            }
            if (selected == AppLocaleManager.getLanguageTag(this)) return@setOnCheckedChangeListener
            AppLocaleManager.setLanguageTag(this, selected)
            recreate()
        }
    }
}
