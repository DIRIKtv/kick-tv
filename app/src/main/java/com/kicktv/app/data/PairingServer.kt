package com.kicktv.app.data

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * A tiny web server on the local network so a phone can type the Kick login for the TV.
 * Every request must carry the random [secret] that's only shown in the TV's QR code.
 * Callbacks run on the main thread; [status] is read from the server thread.
 */
class PairingServer(
    private val onCredentials: (user: String, password: String) -> Unit,
    private val onCode: (code: String) -> Unit,
) {
    val secret: String = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }

    /** JSON the phone polls, e.g. {"state":"ready","message":"..."} */
    @Volatile var status: JSONObject = JSONObject().put("state", "loading")

    private val main = Handler(Looper.getMainLooper())
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    var port = 0
        private set

    fun start(): Boolean {
        for (p in listOf(8787, 8788, 8789, 0)) {
            try {
                socket = ServerSocket(p)
                port = socket!!.localPort
                break
            } catch (e: Exception) {
                continue
            }
        }
        val server = socket ?: return false
        pool.execute {
            Thread.currentThread().name = "pairing-server"
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                // A phone dropping mid-request must never take the app down.
                pool.execute { runCatching { handle(client) } }
            }
        }
        return true
    }

    fun stop() {
        runCatching { socket?.close() }
        pool.shutdownNow()
    }

    fun url(): String? = lanAddress()?.let { "http://$it:$port/?k=$secret" }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = 10_000
        val input = s.getInputStream()
        // Read headers byte by byte up to the blank line, then exactly Content-Length bytes of body.
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0 || head.length > 16_000) return
            head.append(b.toChar())
        }
        val lines = head.split("\r\n")
        val parts = lines.first().split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1) ?: "/"
        val length = lines.firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        val bodyBytes = ByteArray(length.coerceIn(0, 10_000))
        var read = 0
        while (read < bodyBytes.size) {
            val n = input.read(bodyBytes, read, bodyBytes.size - read)
            if (n < 0) break
            read += n
        }
        val body = String(bodyBytes, 0, read, Charsets.UTF_8)

        val path = target.substringBefore('?')
        val key = target.substringAfter("k=", "").substringBefore('&')
        if (key != secret) return respond(s, 403, "text/plain", "This link has expired. Scan the QR code on your TV again.")

        when {
            method == "GET" && path == "/" -> respond(s, 200, "text/html; charset=utf-8", PAGE)
            method == "GET" && path == "/status" -> respond(s, 200, "application/json", status.toString())
            method == "POST" && path == "/login" -> {
                val o = runCatching { JSONObject(body) }.getOrNull()
                val user = o?.optString("user").orEmpty().trim()
                val pass = o?.optString("password").orEmpty()
                if (user.isEmpty() || pass.isEmpty()) return respond(s, 400, "application/json", """{"ok":false}""")
                main.post { onCredentials(user, pass) }
                respond(s, 200, "application/json", """{"ok":true}""")
            }
            method == "POST" && path == "/code" -> {
                val code = runCatching { JSONObject(body).optString("code") }.getOrNull().orEmpty().trim()
                if (code.isEmpty()) return respond(s, 400, "application/json", """{"ok":false}""")
                main.post { onCode(code) }
                respond(s, 200, "application/json", """{"ok":true}""")
            }
            else -> respond(s, 404, "text/plain", "Not found")
        }
    }

    private fun respond(s: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        s.getOutputStream().apply { write(head.toByteArray()); write(bytes); flush() }
    }

    companion object {
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kick TV sign in</title>
<style>
:root{--g:#53FC18;--bg:#0B0E0F;--s:#16191C;--s2:#24272C;--t:#EFEFF1;--t2:#A9ADB4}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:16px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:28px 20px}
.wrap{max-width:420px;margin:0 auto}.logo{color:var(--g);font-weight:900;font-size:34px;letter-spacing:.5px}
h1{font-size:22px;margin:18px 0 4px}p{color:var(--t2);margin:0 0 20px}
label{display:block;font-size:13px;color:var(--t2);margin:14px 0 6px}
input{width:100%;padding:14px;border-radius:10px;border:1px solid #33373d;background:var(--s);color:var(--t);font-size:17px;outline:none}
input:focus{border-color:var(--g)}
button{width:100%;margin-top:22px;padding:15px;border:0;border-radius:10px;background:var(--g);color:#000;font-weight:800;font-size:17px}
button:disabled{opacity:.5}.card{background:var(--s);border-radius:14px;padding:18px;margin-top:8px}
.status{display:flex;gap:10px;align-items:center;padding:12px 14px;border-radius:10px;background:var(--s2);margin-bottom:18px;font-size:14px}
.dot{width:9px;height:9px;border-radius:50%;background:var(--g);flex:none}.err{background:#3a1416;color:#ff8a8a}.err .dot{background:#ff5252}
.hide{display:none}.big{font-size:56px;text-align:center;margin:10px 0}.small{font-size:13px;color:var(--t2);margin-top:18px}
</style></head><body><div class="wrap">
<div class="logo">KICK</div>
<div id="v-form"><h1>Sign in on your TV</h1><p>Type your Kick login here and your TV signs in.</p>
 <div id="st" class="status"><span class="dot"></span><span id="stt">Connecting to your TV…</span></div>
 <form id="f"><label>Email or username</label><input id="u" autocomplete="username" autocapitalize="none" required>
 <label>Password</label><input id="p" type="password" autocomplete="current-password" required>
 <button id="b">Sign in on TV</button></form>
 <div class="small">Your login goes straight from this page to your TV over your home network, and from the TV to kick.com.</div></div>
<div id="v-code" class="hide"><h1>Check your email</h1><p id="codemsg">Kick sent you a verification code. Enter it here.</p>
 <form id="cf"><input id="c" inputmode="numeric" autocomplete="one-time-code" placeholder="Code" required><button>Verify</button></form></div>
<div id="v-wait" class="hide"><h1>Signing in…</h1><p id="waitmsg">Hang on while your TV talks to Kick.</p></div>
<div id="v-done" class="hide"><div class="big">✓</div><h1 style="text-align:center">You're signed in</h1><p style="text-align:center" id="donemsg"></p></div>
</div><script>
var k=new URLSearchParams(location.search).get('k'),sent=false,view='';
function show(v){if(v===view)return;view=v;['form','code','wait','done'].forEach(function(n){document.getElementById('v-'+n).classList.toggle('hide',n!==v)});}
function post(p,d){return fetch(p+'?k='+k,{method:'POST',body:JSON.stringify(d)});}
document.getElementById('f').onsubmit=function(e){e.preventDefault();sent=true;show('wait');
 post('/login',{user:document.getElementById('u').value,password:document.getElementById('p').value});};
document.getElementById('cf').onsubmit=function(e){e.preventDefault();show('wait');post('/code',{code:document.getElementById('c').value});document.getElementById('c').value='';};
function poll(){fetch('/status?k='+k).then(function(r){return r.json()}).then(function(s){
 var st=document.getElementById('st'),t=document.getElementById('stt');
 st.classList.toggle('err',s.state==='error');
 if(s.state==='done'){document.getElementById('donemsg').textContent=s.message||'';show('done');return;}
 if(s.state==='code'){if(s.message)document.getElementById('codemsg').textContent=s.message;show('code');}
 else if(s.state==='working'){document.getElementById('waitmsg').textContent=s.message||'Hang on while your TV talks to Kick.';show('wait');}
 else{show('form');t.textContent=s.message||(s.state==='loading'?'Getting kick.com ready on your TV…':'Connected to your TV');}
 setTimeout(poll,1500);}).catch(function(){document.getElementById('stt').textContent='Can\'t reach your TV. Make sure your phone is on the same Wi-Fi.';setTimeout(poll,3000);});}
poll();
</script></body></html>
"""
    }
}
