package com.noad.bilibili

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.noad.bilibili.databinding.ActivityLoginBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var pollJob: Job? = null
    private var currentQrKey: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnTabQr.isSelected = true
        binding.btnTabQr.setOnClickListener { switchTab(true) }
        binding.btnTabPwd.setOnClickListener { switchTab(false) }
        binding.btnRefreshQr.setOnClickListener { loadQrCode() }
        binding.btnPwdLogin.setOnClickListener { startPasswordLogin() }

        val renderer = PureBiliApp.instance.dlnaRenderer
        if (renderer.isRunning()) {
            binding.tvPwdStatus.text = "投屏接收已开启：手机可搜「${renderer.friendlyName}」直接投屏"
        }

        loadQrCode()
    }

    private fun switchTab(qr: Boolean) {
        binding.btnTabQr.isSelected = qr
        binding.btnTabPwd.isSelected = !qr
        binding.panelQr.visibility = if (qr) View.VISIBLE else View.GONE
        binding.panelPwd.visibility = if (qr) View.GONE else View.VISIBLE
        if (qr) {
            loadQrCode()
        } else {
            pollJob?.cancel()
        }
    }

    private fun loadQrCode() {
        pollJob?.cancel()
        binding.tvQrStatus.text = "正在生成登录二维码..."
        lifecycleScope.launch {
            try {
                val info = PureBiliApp.instance.api.generateQrCode()
                currentQrKey = info.qrcodeKey
                binding.imgQrCode.setImageBitmap(renderQrBitmap(info.url, 480))
                binding.tvQrStatus.text = "请使用手机哔哩哔哩 App 扫码登录"
                startPolling()
            } catch (e: Exception) {
                binding.tvQrStatus.text = "二维码加载失败：${e.message}"
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                try {
                    val res = PureBiliApp.instance.api.pollQrCode(currentQrKey)
                    if (res.loggedIn) {
                        binding.tvQrStatus.text = "登录成功！"
                        goMain()
                        break
                    } else if (res.status == 86090) {
                        binding.tvQrStatus.text = "已扫码，请在手机上点击确认"
                    } else if (res.status == 86038) {
                        binding.tvQrStatus.text = "二维码已过期，请点击刷新"
                        break
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun startPasswordLogin() {
        val username = binding.etUsername.text.toString().trim()
        val password = binding.etPassword.text.toString()
        if (username.isEmpty() || password.isEmpty()) {
            binding.tvPwdStatus.text = "请输入手机号/邮箱与密码"
            return
        }

        binding.btnPwdLogin.isEnabled = false
        binding.tvPwdStatus.text = "正在请求官方极验人机验证..."

        lifecycleScope.launch {
            try {
                val cap = PureBiliApp.instance.api.getPasswordCaptcha()
                showGeetestDialog(username, password, cap)
            } catch (e: Exception) {
                binding.tvPwdStatus.text = "获取人机验证失败：${e.message}"
                binding.btnPwdLogin.isEnabled = true
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showGeetestDialog(username: String, password: String, cap: CaptchaInfo) {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        val dialog = AlertDialog.Builder(this)
            .setTitle("请完成官方安全验证")
            .setView(webView)
            .setNegativeButton("取消") { d, _ ->
                d.dismiss()
                binding.btnPwdLogin.isEnabled = true
            }
            .setOnCancelListener {
                binding.btnPwdLogin.isEnabled = true
            }
            .create()

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVerifySuccess(challenge: String, validate: String, seccode: String) {
                runOnUiThread {
                    dialog.dismiss()
                    submitPasswordLogin(username, password, cap.token, challenge, validate, seccode)
                }
            }

            @JavascriptInterface
            fun onVerifyError(msg: String) {
                runOnUiThread {
                    binding.tvPwdStatus.text = msg
                    binding.btnPwdLogin.isEnabled = true
                }
            }
        }, "PureBiliBridge")

        val html = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1.0"/>
              <script src="https://static.geetest.com/static/js/gt.0.4.9.js"></script>
              <style>body{margin:16px;background:#171A23;color:#fff;font-family:sans-serif;text-align:center;}</style>
            </head>
            <body>
              <div id="box">正在加载安全组件...</div>
              <script>
                if (typeof initGeetest !== 'function') {
                  PureBiliBridge.onVerifyError('极验组件加载失败，建议使用扫码登录');
                } else {
                  initGeetest({
                    gt: '${cap.gt}',
                    challenge: '${cap.challenge}',
                    offline: false,
                    new_captcha: true,
                    product: 'embed',
                    width: '100%'
                  }, function(obj) {
                    document.getElementById('box').innerHTML = '';
                    obj.appendTo('#box');
                    obj.onSuccess(function() {
                      var r = obj.getValidate();
                      PureBiliBridge.onVerifySuccess(r.geetest_challenge, r.geetest_validate, r.geetest_seccode);
                    });
                    obj.onError(function() {
                      PureBiliBridge.onVerifyError('安全验证失败，请重试或使用扫码登录');
                    });
                  });
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://passport.bilibili.com/", html, "text/html", "UTF-8", null)
        dialog.show()
    }

    private fun submitPasswordLogin(
        username: String,
        password: String,
        token: String,
        challenge: String,
        validate: String,
        seccode: String
    ) {
        binding.tvPwdStatus.text = "正在提交登录..."
        lifecycleScope.launch {
            try {
                val res = PureBiliApp.instance.api.passwordLogin(
                    username, password, token, challenge, validate, seccode
                )
                if (res.success) {
                    goMain()
                } else {
                    binding.tvPwdStatus.text = "${res.message}（若触发异地风控，建议直接使用扫码登录）"
                }
            } catch (e: Exception) {
                binding.tvPwdStatus.text = "登录请求失败：${e.message}"
            } finally {
                binding.btnPwdLogin.isEnabled = true
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun renderQrBitmap(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }
}
