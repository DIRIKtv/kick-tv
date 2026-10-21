package com.kicktv.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.kicktv.app.R
import com.kicktv.app.data.Follows
import com.kicktv.app.data.KickApi
import com.kicktv.app.data.PairingServer
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Signs in through kick.com itself, two ways:
 *  - scan the QR code and type the login on a phone; the TV fills it into kick.com's form
 *  - or drive the page directly with a D-pad pointer.
 * Once kick.com sets a session cookie we keep it.
 */
class LoginActivity : FragmentActivity() {
    private lateinit var web: WebView
    private lateinit var cursor: ImageView
    private lateinit var statusText: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var checking = false
    private var lastTried: String? = null
    private var autoOpened = false
    private var signedIn = false

    private var server: PairingServer? = null
    private var pendingLogin: Pair<String, String>? = null
    private var formReady = false
    private var workingSince = 0L

    companion object {
        private const val OPEN_LOGIN_JS = """
            (function(){var n=0;var t=setInterval(function(){
              var f=document.querySelector('input[name="emailOrUsername"]');
              if((f&&f.offsetParent!==null)||++n>90){clearInterval(t);return;}
              var b=document.querySelector('[data-testid="login"]');
              if(b&&b.offsetParent!==null){b.click();}
            },1000);})();"""

        private const val FIRST_FIELD_JS = """
            (function(){var i=document.querySelector('input[name="emailOrUsername"]');
              if(!i||i.offsetParent===null)return '';var r=i.getBoundingClientRect();
              return (r.left+Math.min(r.width/2,120))+','+(r.top+r.height/2)+','+window.devicePixelRatio;})();"""

        /** Reports what the page is showing: the login form, a verification code prompt, or an error. */
        private const val PROBE_JS = """
            (function(){function vis(e){return e&&e.offsetParent!==null}
              var f=document.querySelector('input[name="emailOrUsername"]');
              var ins=[].slice.call(document.querySelectorAll('input')).filter(function(i){
                return vis(i)&&['hidden','checkbox','password','search'].indexOf(i.type)<0&&i.name!=='emailOrUsername';});
              var code=ins.filter(function(i){return /code|otp|verif/i.test(i.name+' '+i.id+' '+(i.autocomplete||'')+' '+(i.placeholder||''))
                ||i.inputMode==='numeric'||i.maxLength===1||i.maxLength===6;});
              var err=[].slice.call(document.querySelectorAll('[role=alert],[class*="error"],[class*="danger"],[class*="destructive"]'))
                .filter(vis).map(function(e){return e.textContent.trim()}).filter(function(t){return t&&t.length<200})[0]||'';
              if(window.__kerr){err=window.__kerr;window.__kerr='';}
              return JSON.stringify({form:vis(f),code:code.length>0,error:err});})();"""

        /** Sets a React-controlled input's value so the page notices it. */
        private const val SET_VALUE_FN = """
            function __kset(el,v){var s=Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set;
              s.call(el,v);el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}
            if(!window.__kwatch){window.__kwatch=1;window.__kerr='';new MutationObserver(function(ms){ms.forEach(function(m){
              m.addedNodes.forEach(function(n){var t=(n.textContent||'').trim();
                if(t&&t.length<200&&/attempt|invalid|incorrect|wrong|error|failed|try again|not found|match/i.test(t))window.__kerr=t;});});})
              .observe(document.body,{childList:true,subtree:true});}
            function __ksubmit(el){window.__kerr='';var f=el.form;var b=f&&f.querySelector('button[type=submit]');
              if(!b){b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return x.offsetParent!==null&&x.getAttribute('data-testid')!=='login'
                &&/log in|verify|continue|submit|confirm/i.test(x.textContent)}).pop();}
              if(b)b.click();else if(f&&f.requestSubmit)f.requestSubmit();}
        """
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        statusText = findViewById(R.id.pairStatus)
        val frame = findViewById<FrameLayout>(R.id.webFrame)
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (autoOpened) return
                autoOpened = true
                // Open kick.com's login dialog for the viewer, then park the pointer on the first field.
                view.evaluateJavascript(OPEN_LOGIN_JS, null)
                pointAtFirstField(attempt = 0)
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        cursor = ImageView(this).apply { setImageResource(R.drawable.ic_cursor) }
        frame.addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(cursor, FrameLayout.LayoutParams(dp(28), dp(28)))
        frame.post {
            x = frame.width / 2f
            y = frame.height / 3f
            moveCursor(0f, 0f)
        }

        onBackPressedDispatcher.addCallback(this) {
            // Back leaves sign-in unless the web page itself has somewhere to go back to.
            if (web.canGoBack() && web.url?.trimEnd('/') != "https://kick.com") web.goBack() else finish()
        }
        startPairing()
        web.loadUrl("https://kick.com/")
        ui.postDelayed(poll, 1500)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        server?.stop()
        web.destroy()
        super.onDestroy()
    }

    // region phone pairing

    private fun startPairing() {
        val s = PairingServer(onCredentials = { u, p -> onPhoneCredentials(u, p) }, onCode = { onPhoneCode(it) })
        val url = if (s.start()) s.url() else null
        val qr = findViewById<ImageView>(R.id.qr)
        val urlText = findViewById<TextView>(R.id.pairUrl)
        if (url == null) {
            s.stop()
            qr.setImageResource(R.drawable.ic_info)
            urlText.text = "Phone sign-in needs the TV to be on Wi-Fi or Ethernet."
            return
        }
        server = s
        qr.setImageBitmap(qrBitmap(url, dp(200)))
        urlText.text = url.substringBefore("/?")
        setStatus("loading", "Loading kick.com…")
    }

    private fun qrBitmap(text: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { i -> if (matrix[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /** Updates both the phone page and the TV's status line. */
    private fun setStatus(state: String, message: String) {
        server?.status = JSONObject().put("state", state).put("message", message)
        statusText.text = message
        statusText.setTextColor(getColor(if (state == "error") R.color.live_red else R.color.kick_green))
    }

    private fun onPhoneCredentials(user: String, password: String) {
        pendingLogin = user to password
        if (formReady) submitPending() else setStatus("working", "Got it — waiting for kick.com to finish loading…")
    }

    private fun submitPending() {
        val (user, password) = pendingLogin ?: return
        pendingLogin = null
        workingSince = System.currentTimeMillis()
        setStatus("working", "Signing in as $user…")
        web.evaluateJavascript(
            SET_VALUE_FN + """
            (function(){var u=document.querySelector('input[name="emailOrUsername"]');
              var p=document.querySelector('input[type="password"]');
              if(!u||!p)return 'missing';
              __kset(u,${JSONObject.quote(user)});__kset(p,${JSONObject.quote(password)});
              setTimeout(function(){__ksubmit(p)},300);return 'ok';})();""",
        ) { result ->
            if (result.contains("missing")) {
                pendingLogin = user to password
                formReady = false
            }
        }
    }

    private fun onPhoneCode(code: String) {
        workingSince = System.currentTimeMillis()
        setStatus("working", "Checking the code…")
        web.evaluateJavascript(
            SET_VALUE_FN + """
            (function(){function vis(e){return e&&e.offsetParent!==null}
              var c=${JSONObject.quote(code)};
              var ins=[].slice.call(document.querySelectorAll('input')).filter(function(i){
                return vis(i)&&['hidden','checkbox','password','search'].indexOf(i.type)<0&&i.name!=='emailOrUsername';});
              if(!ins.length)return 'missing';
              var boxes=ins.filter(function(i){return i.maxLength===1});
              if(boxes.length>1){boxes.forEach(function(b,n){__kset(b,c.charAt(n)||'')});}else{__kset(ins[0],c);}
              setTimeout(function(){__ksubmit(boxes.length>1?boxes[boxes.length-1]:ins[0])},300);return 'ok';})();""",
            null,
        )
    }

    private val poll = object : Runnable {
        override fun run() {
            checkCookie()
            probePage()
            ui.postDelayed(this, 1500)
        }
    }

    private fun probePage() {
        if (signedIn) return
        web.evaluateJavascript(PROBE_JS) { raw ->
            val json = runCatching { JSONObject(JSONObject("{\"v\":$raw}").getString("v")) }.getOrNull() ?: return@evaluateJavascript
            val state = server?.status?.optString("state")
            val error = json.optString("error")
            when {
                json.optBoolean("code") -> if (state != "code" || System.currentTimeMillis() - workingSince > 4000) {
                    setStatus("code", "Kick sent a verification code to your email. Enter it on your phone.")
                }
                state == "working" && error.isNotBlank() -> setStatus("error", error)
                json.optBoolean("form") -> {
                    formReady = true
                    if (pendingLogin != null) submitPending()
                    else if (state == "loading") setStatus("ready", "Scan the code and sign in on your phone.")
                    else if (state == "working" && System.currentTimeMillis() - workingSince > 20_000) {
                        setStatus("error", "Kick may be asking for a security check on the TV. Use the remote pointer to finish it.")
                    }
                }
            }
        }
    }

    // endregion

    // region pointer

    /** Waits for the login form to show up, then parks the pointer on its first field. */
    private fun pointAtFirstField(attempt: Int) {
        if (attempt > 90 || isFinishing) return
        web.evaluateJavascript(FIRST_FIELD_JS) { result ->
            val parts = result.trim('"').split(',').mapNotNull { it.toFloatOrNull() }
            if (parts.size == 3) {
                x = parts[0] * parts[2]
                y = parts[1] * parts[2]
                moveCursor(0f, 0f)
            } else {
                ui.postDelayed({ pointAtFirstField(attempt + 1) }, 1000)
            }
        }
    }

    private fun moveCursor(dx: Float, dy: Float) {
        val w = web.width.toFloat()
        val h = web.height.toFloat()
        x += dx
        y += dy
        // Pushing past the top/bottom edge scrolls the page.
        if (y < 0) { web.scrollBy(0, y.toInt()); y = 0f }
        if (h > 0 && y > h - 4) { web.scrollBy(0, (y - h + 4).toInt()); y = h - 4 }
        x = x.coerceIn(0f, (w - 4).coerceAtLeast(0f))
        cursor.translationX = x
        cursor.translationY = y
    }

    private fun click() {
        if (!web.hasFocus()) web.requestFocus()
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN to t, MotionEvent.ACTION_UP to t + 60).forEach { (action, time) ->
            val ev = MotionEvent.obtain(t, time, action, x, y, 0)
            ev.source = InputDevice.SOURCE_TOUCHSCREEN
            web.dispatchTouchEvent(ev)
            ev.recycle()
        }
    }

    // Overriding Activity.dispatchKeyEvent is standard; lint trips on AndroidX's restricted override of it.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val step = dp(14) * (1 + (event.repeatCount / 3).coerceAtMost(4)).toFloat()
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (event.action == KeyEvent.ACTION_DOWN) moveCursor(-step, 0f); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (event.action == KeyEvent.ACTION_DOWN) moveCursor(step, 0f); true }
            KeyEvent.KEYCODE_DPAD_UP -> { if (event.action == KeyEvent.ACTION_DOWN) moveCursor(0f, -step); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (event.action == KeyEvent.ACTION_DOWN) moveCursor(0f, step); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) click()
                true
            }
            else -> false
        }
        return handled || super.dispatchKeyEvent(event)
    }

    // endregion

    private fun checkCookie() {
        if (checking || signedIn) return
        val cookies = CookieManager.getInstance().getCookie("https://kick.com") ?: return
        val raw = cookies.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("session_token=") }?.substringAfter('=') ?: return
        val token = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        if (token.isBlank() || token == lastTried) return
        checking = true
        lifecycleScope.launch {
            val me = runCatching { KickApi.me(token) }.getOrNull()
            checking = false
            lastTried = token
            if (me != null) {
                signedIn = true
                Follows.signIn(this@LoginActivity, token, me.first, me.second)
                setStatus("done", "Signed in as ${me.first}. You can put your phone away.")
                Toast.makeText(this@LoginActivity, "Signed in as ${me.first}", Toast.LENGTH_LONG).show()
                setResult(RESULT_OK)
                // Give the phone a moment to see the success message before the server stops.
                ui.postDelayed({ finish() }, 3000)
            }
        }
    }
}
