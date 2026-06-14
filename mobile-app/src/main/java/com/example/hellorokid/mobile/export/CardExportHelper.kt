package com.example.hellorokid.mobile.export

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.hellorokid.mobile.R
import com.example.hellorokid.mobile.data.BusinessCardEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CardExportHelper {

    fun buildVCardContent(cards: List<BusinessCardEntity>): String {
        return cards.joinToString("\n") { card ->
            buildString {
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:3.0")
                appendLine("FN:${card.name}")
                if (card.nameReading.isNotBlank()) {
                    appendLine("X-PHONETIC-FIRST-NAME:${card.nameReading}")
                }
                appendLine("TITLE:${card.title}")
                val org = if (card.department.isNotBlank()) {
                    "${card.company};${card.department}"
                } else {
                    card.company
                }
                if (org.isNotBlank()) appendLine("ORG:$org")
                if (card.companyNameEn.isNotBlank()) {
                    appendLine("X-ORG-EN:${card.companyNameEn}")
                }
                if (card.phone.isNotBlank()) appendLine("TEL;TYPE=VOICE:${card.phone}")
                if (card.mobile.isNotBlank()) appendLine("TEL;TYPE=CELL:${card.mobile}")
                if (card.fax.isNotBlank()) appendLine("TEL;TYPE=FAX:${card.fax}")
                if (card.email.isNotBlank()) appendLine("EMAIL:${card.email}")
                if (card.address.isNotBlank()) appendLine("ADR:;;${card.address}")
                if (card.addressEn.isNotBlank()) appendLine("X-ADR-EN:${card.addressEn}")
                if (card.website.isNotBlank()) appendLine("URL:${card.website}")
                appendLine("END:VCARD")
            }
        }
    }

    fun buildCsvContent(context: Context, cards: List<BusinessCardEntity>): String {
        val header = context.getString(R.string.csv_header)
        val rows = cards.map { card ->
            listOf(
                card.name, card.nameReading, card.title, card.titleLocalized,
                card.department, card.departmentLocalized, card.company, card.companyNameEn,
                card.phone, card.mobile, card.fax, card.email,
                card.address, card.addressEn, card.website, card.industry, card.companySize, card.revenue,
                card.coreBusiness, card.markets, card.partners, card.opportunities,
                card.investmentReadiness, card.timing, card.scannedAt.toString()
            ).joinToString(",") { "\"${it.replace("\"", "\"\"")}\"" }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    fun saveAndShare(
        context: Context,
        content: String,
        fileName: String,
        mimeType: String
    ): Uri {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, content, fileName, mimeType)
        } else {
            saveViaFileProvider(context, content, fileName)
        }
        return uri
    }

    fun createShareIntent(uri: Uri, mimeType: String): Intent {
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        content: String,
        fileName: String,
        mimeType: String
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Unable to create export file")

        resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun saveViaFileProvider(context: Context, content: String, fileName: String): Uri {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "exports")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    fun timestampedFileName(prefix: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return "${prefix}_$stamp.$extension"
    }
}
