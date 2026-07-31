package club.volvoswed.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import club.volvoswed.app.databinding.ActivityMainBinding
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var sessionStore: SecureSessionStore
    private lateinit var authClient: MobileAuthClient
    private var fileResult: ValueCallback<Array<Uri>>? = null
    private var pendingDownload: PendingDownload? = null
    private var receivedMainFrameError = false

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = fileResult ?: return@registerForActivityResult
        fileResult = null
        callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val download = pendingDownload
        pendingDownload = null
        if (granted && download != null) enqueueDownload(download) else {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemInsets()

        sessionStore = SecureSessionStore(this)
        authClient = MobileAuthClient(BuildConfig.API_BASE_URL)
        configureWebView()

        binding.statusAction.setOnClickListener { startTelegramLogin() }
        binding.swipeRefresh.setOnRefreshListener { binding.webView.reload() }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) binding.webView.goBack() else finish()
            }
        })

        if (!handleAuthCallback(intent)) restoreSession()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!handleAuthCallback(intent) && isTrustedWebUri(intent.data)) {
            binding.webView.loadUrl(intent.data.toString())
        }
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileResult?.onReceiveValue(null)
        fileResult = null
        binding.webView.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
        authClient.shutdown()
        super.onDestroy()
    }

    private fun applySystemInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() = with(binding.webView) {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = true
            userAgentString = "$userAgentString VolvoClubAndroid/${BuildConfig.VERSION_NAME}"
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(this@with, false)
        }
        webViewClient = ClubWebViewClient()
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, progress: Int) {
                binding.pageProgress.progress = progress
                binding.pageProgress.visibility = if (progress in 0..99) View.VISIBLE else View.GONE
                if (progress == 100) binding.swipeRefresh.isRefreshing = false
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileResult?.onReceiveValue(null)
                fileResult = filePathCallback
                val chooser = runCatching { fileChooserParams?.createIntent() }
                    .getOrNull()
                    ?.apply {
                        action = Intent.ACTION_OPEN_DOCUMENT
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
                    }
                    ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                filePicker.launch(Intent.createChooser(chooser, getString(R.string.file_chooser)))
                return true
            }
        }
        setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            requestDownload(PendingDownload(url, userAgent, contentDisposition, mimeType))
        })
    }

    private fun restoreSession() {
        val tokens = sessionStore.read()
        when {
            tokens == null -> showLogin()
            tokens.accessIsFresh() -> openClub(tokens)
            tokens.canRefresh() -> {
                showLoading()
                authClient.refresh(tokens.refreshToken) { result ->
                    result.onSuccess {
                        sessionStore.save(it)
                        openClub(it)
                    }.onFailure {
                        sessionStore.clear()
                        showLogin(getString(R.string.login_error))
                    }
                }
            }
            else -> {
                sessionStore.clear()
                showLogin()
            }
        }
    }

    private fun startTelegramLogin() {
        if (!hasInternet()) {
            showError(
                getString(R.string.network_error_title),
                getString(R.string.network_error_message)
            )
            return
        }
        showLoading()
        val redirect = "${BuildConfig.LOGIN_REDIRECT_SCHEME}://${BuildConfig.LOGIN_REDIRECT_HOST}"
        val url = "${BuildConfig.API_BASE_URL}/mobile/auth/start?platform=android&redirect_uri=" +
            URLEncoder.encode(redirect, Charsets.UTF_8.name())
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
        }.onFailure {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        }
    }

    private fun handleAuthCallback(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != BuildConfig.LOGIN_REDIRECT_SCHEME || uri.host != BuildConfig.LOGIN_REDIRECT_HOST) {
            return false
        }

        val code = uri.getQueryParameter("code")
        val error = uri.getQueryParameter("error")
        if (code.isNullOrBlank()) {
            showLogin(error ?: getString(R.string.login_error))
            return true
        }

        showLoading()
        authClient.exchangeCode(code) { result ->
            result.onSuccess { tokens ->
                sessionStore.save(tokens)
                openClub(tokens)
            }.onFailure { throwable ->
                showLogin(throwable.message ?: getString(R.string.login_error))
            }
        }
        return true
    }

    private fun openClub(tokens: SessionTokens) {
        receivedMainFrameError = false
        val maxAgeSeconds = ((tokens.accessExpiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        val expires = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).run {
            timeZone = TimeZone.getTimeZone("GMT")
            format(Date(tokens.accessExpiresAt))
        }
        val cookie = buildString {
            append("volvo_mobile_session=")
            append(tokens.accessToken)
            append("; Path=/; Secure; HttpOnly; SameSite=Lax; Max-Age=")
            append(maxAgeSeconds)
            append("; Expires=")
            append(expires)
        }
        val cookieManager = CookieManager.getInstance()
        val cookieTargets = setOf(BuildConfig.WEB_APP_URL, BuildConfig.API_BASE_URL)
        var remaining = cookieTargets.size
        cookieTargets.forEach { targetUrl ->
            cookieManager.setCookie(targetUrl, cookie) {
                remaining -= 1
                if (remaining == 0) {
                    cookieManager.flush()
                    val target = intent?.data?.takeIf(::isTrustedWebUri)?.toString()
                        ?: "${BuildConfig.WEB_APP_URL}/?platform=android"
                    binding.webView.loadUrl(target)
                    binding.statusPanel.visibility = View.GONE
                    binding.swipeRefresh.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun showLoading() {
        binding.swipeRefresh.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
        binding.statusTitle.setText(R.string.loading)
        binding.statusMessage.visibility = View.GONE
        binding.statusProgress.visibility = View.VISIBLE
        binding.statusAction.visibility = View.GONE
    }

    private fun showLogin(message: String? = null) {
        binding.swipeRefresh.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
        binding.statusTitle.setText(R.string.login_title)
        binding.statusMessage.text = message ?: getString(R.string.login_message)
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusProgress.visibility = View.GONE
        binding.statusAction.setText(R.string.login_button)
        binding.statusAction.visibility = View.VISIBLE
    }

    private fun showError(title: String, message: String) {
        binding.swipeRefresh.visibility = View.GONE
        binding.statusPanel.visibility = View.VISIBLE
        binding.statusTitle.text = title
        binding.statusMessage.text = message
        binding.statusMessage.visibility = View.VISIBLE
        binding.statusProgress.visibility = View.GONE
        binding.statusAction.setText(R.string.retry)
        binding.statusAction.visibility = View.VISIBLE
    }

    private fun hasInternet(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isTrustedWebUri(uri: Uri?): Boolean {
        if (uri?.scheme != "https") return false
        val configuredHost = BuildConfig.WEB_APP_URL.toUri().host ?: return false
        val host = uri.host ?: return false
        return host == configuredHost || host.endsWith(".$configuredHost")
    }

    private fun openExternal(uri: Uri): Boolean = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, uri))
        true
    }.getOrElse { false }

    private fun requestDownload(download: PendingDownload) {
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDownload = download
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            enqueueDownload(download)
        }
    }

    private fun enqueueDownload(download: PendingDownload) {
        runCatching {
            val uri = download.url.toUri()
            require(uri.scheme == "https") { "Only HTTPS downloads are allowed" }
            val fileName = URLUtil.guessFileName(
                download.url,
                download.contentDisposition,
                download.mimeType.ifBlank {
                    MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(uri.lastPathSegment?.substringAfterLast('.'))
                        ?: "application/octet-stream"
                }
            )
            val request = DownloadManager.Request(uri)
                .setTitle(fileName)
                .setMimeType(download.mimeType.ifBlank { "application/octet-stream" })
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            CookieManager.getInstance().getCookie(download.url)?.let { request.addRequestHeader("Cookie", it) }
            request.addRequestHeader("User-Agent", download.userAgent)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, R.string.download_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private inner class ClubWebViewClient : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            receivedMainFrameError = false
            binding.pageProgress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.swipeRefresh.isRefreshing = false
            if (!receivedMainFrameError) {
                binding.statusPanel.visibility = View.GONE
                binding.swipeRefresh.visibility = View.VISIBLE
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val uri = request?.url ?: return false
            if (isTrustedWebUri(uri)) return false
            if (uri.scheme == BuildConfig.LOGIN_REDIRECT_SCHEME && uri.host == BuildConfig.LOGIN_REDIRECT_HOST) {
                handleAuthCallback(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
            return openExternal(uri)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame != true) return
            receivedMainFrameError = true
            showError(
                if (hasInternet()) getString(R.string.server_error_title) else getString(R.string.network_error_title),
                if (hasInternet()) getString(R.string.server_error_message) else getString(R.string.network_error_message)
            )
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            if (request?.isForMainFrame != true) return
            if (errorResponse?.statusCode == 401) {
                sessionStore.clear()
                CookieManager.getInstance().removeAllCookies(null)
                showLogin()
            }
        }
    }

    private data class PendingDownload(
        val url: String,
        val userAgent: String,
        val contentDisposition: String,
        val mimeType: String
    )
}
