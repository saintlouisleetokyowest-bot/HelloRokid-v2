package com.example.hellorokid.mobile

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.hellorokid.mobile.data.BusinessCardEntity
import com.example.hellorokid.mobile.export.CardExportHelper
import com.example.hellorokid.mobile.ui.CardDetailActivity
import com.example.hellorokid.mobile.ui.CardListAdapter
import com.example.hellorokid.mobile.ui.ConnectionState
import com.example.hellorokid.mobile.ui.MainViewModel
import com.example.hellorokid.mobile.ui.SettingsActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels {
        val app = application as HelloRokidApplication
        MainViewModel.Factory(app, app.cardRepository)
    }

    private lateinit var toolbar: MaterialToolbar
    private lateinit var connectionIndicator: View
    private lateinit var connectionStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var cardCountBadge: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var scanBtn: MaterialButton
    private lateinit var importImageBtn: MaterialButton
    private lateinit var cardRecyclerView: RecyclerView
    private lateinit var emptyView: View

    private lateinit var cardAdapter: CardListAdapter

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.startBleScan()
        } else {
            showSnackbar(getString(R.string.permission_bluetooth_required))
        }
    }

    private val imagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            pickImageLauncher.launch("image/*")
        } else {
            showSnackbar(getString(R.string.permission_image_required))
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { analyzeImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()
        setupToolbar()
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun bindViews() {
        toolbar = findViewById(R.id.toolbar)
        connectionIndicator = findViewById(R.id.connectionIndicator)
        connectionStatusText = findViewById(R.id.connectionStatusText)
        statusText = findViewById(R.id.statusText)
        cardCountBadge = findViewById(R.id.cardCountBadge)
        progressBar = findViewById(R.id.progressBar)
        scanBtn = findViewById(R.id.scanBtn)
        importImageBtn = findViewById(R.id.importImageBtn)
        cardRecyclerView = findViewById(R.id.cardRecyclerView)
        emptyView = findViewById(R.id.emptyView)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
    }

    private fun setupRecyclerView() {
        cardAdapter = CardListAdapter(
            onItemClick = { card ->
                startActivity(CardDetailActivity.intent(this, card.id))
            },
            onItemLongClick = { card ->
                confirmDeleteCard(card)
                true
            }
        )
        cardRecyclerView.layoutManager = LinearLayoutManager(this)
        cardRecyclerView.adapter = cardAdapter
    }

    private fun setupListeners() {
        scanBtn.setOnClickListener {
            when (viewModel.connectionState.value) {
                ConnectionState.CONNECTED,
                ConnectionState.RECEIVING,
                ConnectionState.ANALYZING -> viewModel.disconnect()
                else -> checkBluetoothPermissionsAndScan()
            }
        }
        importImageBtn.setOnClickListener { checkImagePermissionAndPick() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.cards.collect { cards ->
                        cardAdapter.submitList(cards)
                        emptyView.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
                        cardRecyclerView.visibility = if (cards.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.cardCount.collect { count ->
                        cardCountBadge.text = getString(R.string.card_count_badge, count)
                    }
                }
                launch {
                    viewModel.connectionState.collect { state ->
                        updateConnectionUi(state)
                    }
                }
                launch {
                    viewModel.message.collect { message ->
                        message?.let {
                            showSnackbar(it)
                            viewModel.clearMessage()
                        }
                    }
                }
            }
        }
    }

    private fun updateConnectionUi(state: ConnectionState) {
        val isBusy = state == ConnectionState.SCANNING ||
            state == ConnectionState.RECEIVING ||
            state == ConnectionState.ANALYZING
        // 收图/分析用静态文字即可；无限进度条在红米上会刷 gralloc4 系统日志
        progressBar.visibility = if (state == ConnectionState.SCANNING) View.VISIBLE else View.GONE
        importImageBtn.isEnabled = !isBusy

        when (state) {
            ConnectionState.DISCONNECTED -> {
                connectionIndicator.setBackgroundResource(R.drawable.bg_status_dot_disconnected)
                connectionStatusText.setText(R.string.status_disconnected)
                statusText.setText(R.string.status_hint_disconnected)
                scanBtn.text = getString(R.string.action_connect_glass)
                scanBtn.isEnabled = true
            }
            ConnectionState.SCANNING -> {
                connectionIndicator.setBackgroundResource(R.drawable.bg_status_dot_scanning)
                connectionStatusText.setText(R.string.status_scanning)
                statusText.text = getString(R.string.status_scanning)
                scanBtn.isEnabled = false
            }
            ConnectionState.CONNECTED -> {
                connectionIndicator.setBackgroundResource(R.drawable.bg_status_dot_connected)
                connectionStatusText.setText(R.string.status_connected)
                statusText.setText(R.string.status_hint_connected)
                scanBtn.text = getString(R.string.action_disconnect)
                scanBtn.isEnabled = true
            }
            ConnectionState.RECEIVING -> {
                connectionIndicator.setBackgroundResource(R.drawable.bg_status_dot_scanning)
                connectionStatusText.setText(R.string.status_receiving)
                statusText.setText(R.string.status_hint_receiving)
                scanBtn.text = getString(R.string.action_disconnect)
                scanBtn.isEnabled = true
            }
            ConnectionState.ANALYZING -> {
                connectionIndicator.setBackgroundResource(R.drawable.bg_status_dot_scanning)
                connectionStatusText.setText(R.string.status_analyzing)
                statusText.setText(R.string.status_hint_analyzing)
                scanBtn.text = getString(R.string.action_disconnect)
                scanBtn.isEnabled = true
            }
        }
    }

    private fun checkBluetoothPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isEmpty()) {
            viewModel.startBleScan()
        } else {
            bluetoothPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun checkImagePermissionAndPick() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                needed.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        if (needed.isEmpty()) {
            pickImageLauncher.launch("image/*")
        } else {
            imagePermissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun analyzeImageFromUri(uri: Uri) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            if (bitmap != null) {
                viewModel.analyzeImage(bitmap)
            } else {
                showSnackbar(getString(R.string.error_read_image))
            }
        } catch (e: Exception) {
            showSnackbar(getString(R.string.error_read_image_detail, e.message ?: ""))
        }
    }

    private fun confirmDeleteCard(card: BusinessCardEntity) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_card_title)
            .setMessage(getString(R.string.delete_card_confirm, card.name.ifBlank { getString(R.string.card_unnamed) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    (application as HelloRokidApplication).cardRepository.deleteById(card.id)
                    showSnackbar(getString(R.string.card_deleted))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val connected = viewModel.connectionState.value == ConnectionState.CONNECTED
            || viewModel.connectionState.value == ConnectionState.RECEIVING
            || viewModel.connectionState.value == ConnectionState.ANALYZING
        menu.findItem(R.id.action_disconnect).isVisible = connected
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_export_vcard -> {
                exportVCard()
                true
            }
            R.id.action_export_csv -> {
                exportCsv()
                true
            }
            R.id.action_disconnect -> {
                viewModel.disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun exportVCard() {
        viewModel.exportVCard { result ->
            result.fold(
                onSuccess = { content ->
                    shareExport(content, "text/vcard", "vcf")
                },
                onFailure = { showSnackbar(it.message ?: getString(R.string.export_failed)) }
            )
        }
    }

    private fun exportCsv() {
        viewModel.exportCsv { result ->
            result.fold(
                onSuccess = { content ->
                    shareExport(content, "text/csv", "csv")
                },
                onFailure = { showSnackbar(it.message ?: getString(R.string.export_failed)) }
            )
        }
    }

    private fun shareExport(content: String, mimeType: String, extension: String) {
        try {
            val fileName = CardExportHelper.timestampedFileName("rokid_cards", extension)
            val uri = CardExportHelper.saveAndShare(this, content, fileName, mimeType)
            val shareIntent = CardExportHelper.createShareIntent(uri, mimeType)
            startActivity(Intent.createChooser(shareIntent, getString(R.string.export_share_title)))
            showSnackbar(getString(R.string.export_success))
        } catch (e: Exception) {
            showSnackbar(getString(R.string.export_failed) + ": ${e.message}")
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(findViewById(R.id.main), message, Snackbar.LENGTH_SHORT).show()
    }
}
