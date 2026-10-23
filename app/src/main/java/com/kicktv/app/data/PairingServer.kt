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

        // The fields float their label into the outline (Bencho's label input), the button folds
        // into a slim bar while the TV works and opens back out as "Signed in" (pay button), and
        // in-progress status lines carry a sweeping shimmer (shimmer text).
        private val PAGE = """
<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Kick TV sign in</title>
<style>
:root{--g:#53FC18;--bg:#0B0E0F;--s:#16191C;--s2:#24272C;--t:#EFEFF1;--t2:#A9ADB4;--t3:#71767E;--e:#3A3E45}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--t);font:16px/1.4 system-ui,-apple-system,Segoe UI,Roboto,sans-serif;padding:28px 20px}
.wrap{max-width:420px;margin:0 auto}.logo{color:var(--g);font-weight:900;font-size:34px;letter-spacing:.5px}
h1{font-size:22px;margin:18px 0 4px}p{color:var(--t2);margin:0 0 20px}
.status{display:flex;gap:10px;align-items:center;padding:12px 14px;border-radius:10px;background:var(--s2);margin-bottom:6px;font-size:14px}
.dot{width:9px;height:9px;border-radius:50%;background:var(--g);flex:none}.err{background:#3a1416;color:#ff8a8a}.err .dot{background:#ff5252}
.hide{display:none!important}.small{font-size:13px;color:var(--t2);margin-top:22px}
/* shimmer: the words dimmed, and a copy on top painted only where a band of ink passes */
.shm{position:relative;color:var(--t3)}
.shm::before{content:attr(data-text);position:absolute;inset:0;pointer-events:none;background-image:linear-gradient(90deg,transparent 0,transparent 40%,var(--t) 50%,transparent 60%,transparent 100%);background-size:400% 100%;background-repeat:no-repeat;-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent;animation:sweep 2s linear infinite}
@keyframes sweep{from{background-position:100% 0}to{background-position:0 0}}
/* label input: the label is the placeholder until you are in the field, then lifts into the
   top edge with a hop along its letters while the outline parts under it from the middle */
.fld{position:relative;height:54px;margin-top:24px}
.fld input{position:absolute;inset:0;width:100%;height:100%;padding:0 14px;border:1px solid var(--e);border-radius:14px;background:transparent;color:var(--t);font:inherit;font-size:17px;outline:none;transition:border-color .22s,box-shadow .22s}
.fld input:focus{border-color:var(--g);box-shadow:inset 0 0 0 .5px var(--g)}
.fld input:-webkit-autofill{-webkit-text-fill-color:var(--t);transition:background-color 99999s}
.fld.pw input{padding-right:50px}
.fld label{position:absolute;left:14px;top:50%;z-index:1;font-size:17px;line-height:1;white-space:pre;color:var(--t3);pointer-events:none;transform-origin:left center;transform:translateY(-50%);transition:transform .42s cubic-bezier(.22,1,.36,1),color .22s}
.fld label span{display:inline-block}
.fld input:focus~label,.fld input:not(:placeholder-shown)~label{transform:translateY(-50%) translateY(-27px) scale(.78);color:var(--t2)}
.fld input:focus~label{color:var(--g)}
.fld input:focus~label span{animation:hop .46s cubic-bezier(.3,.7,.3,1) both;animation-delay:calc(var(--i)*24ms)}
.gap{position:absolute;top:-1px;left:9px;height:3px;width:calc(var(--lw,60px)*.78 + 10px);background:var(--bg);transform:scaleX(0);transition:transform .42s cubic-bezier(.22,1,.36,1)}
.fld input:focus~.gap,.fld input:not(:placeholder-shown)~.gap{transform:scaleX(1)}
.eye{position:absolute;right:10px;top:50%;width:34px;height:34px;margin-top:-17px;display:grid;place-items:center;padding:0;border:0;border-radius:999px;background:transparent;color:var(--t3);cursor:pointer;z-index:2}
.eye svg{grid-area:1/1;width:18px;height:18px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round;transition:opacity .22s,transform .22s cubic-bezier(.22,1,.36,1),filter .22s}
.eye svg+svg,.eye.on svg:first-child{opacity:0;transform:scale(.7);filter:blur(2px)}
.eye.on svg+svg{opacity:1;transform:none;filter:none}
.flip{animation:flip .26s ease-out}
/* pay button: the fields sink into the button, which draws in to a slim bar with a stroke
   running along it while the TV works, then opens back out a little past "Signed in" */
.fold{display:grid;grid-template-rows:1fr;transition:grid-template-rows .6s cubic-bezier(.24,1.34,.38,1)}
.fold-in{min-height:0;overflow:hidden;transition:opacity .42s ease .16s,transform .6s cubic-bezier(.22,1,.36,1) .16s,filter .42s ease .16s}
.pay:not([data-phase=idle]) .fold{grid-template-rows:0fr;transition:grid-template-rows .6s cubic-bezier(.22,1,.36,1)}
.pay:not([data-phase=idle]) .fold-in{opacity:0;transform:translateY(18px) scale(.86);filter:blur(4px);transition:opacity .22s,transform .42s cubic-bezier(.22,1,.36,1),filter .22s}
.go{position:relative;display:block;width:100%;height:52px;margin:26px auto 0;padding:0;border:0;border-radius:999px;background:var(--g);color:#000;font:inherit;font-weight:800;font-size:17px;overflow:hidden;cursor:pointer;transition:width .6s cubic-bezier(.22,1,.36,1),transform .16s cubic-bezier(.22,1,.36,1);-webkit-tap-highlight-color:transparent}
.go:active{transform:scale(.96)}
.pay[data-phase=busy] .go{width:104px;cursor:progress}
.pay[data-phase=done] .go{transition:width .6s cubic-bezier(.24,1.34,.38,1)}
.l{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);display:inline-flex;align-items:center;gap:8px;white-space:nowrap;opacity:0;filter:blur(3px);transition:opacity .16s,filter .16s}
.pay[data-phase=idle] .l-idle,.pay[data-phase=busy] .l-busy,.pay[data-phase=done] .l-done{opacity:1;filter:none;transition:opacity .22s .22s,filter .22s .22s}
.run{stroke-dasharray:.3 1;stroke-dashoffset:.3}
.pay[data-phase=busy] .run{animation:run 1.1s cubic-bezier(.45,0,.35,1) infinite}
.chk path{stroke-dasharray:1;stroke-dashoffset:1;transition:stroke-dashoffset .16s}
.pay[data-phase=done] .chk path{stroke-dashoffset:0;transition:stroke-dashoffset .42s cubic-bezier(.22,1,.36,1) .42s}
@keyframes hop{0%{transform:translateY(0)}38%{transform:translateY(-3px)}100%{transform:translateY(0)}}
@keyframes flip{from{filter:blur(3px);opacity:.5}}
@keyframes run{from{stroke-dashoffset:.3}to{stroke-dashoffset:-1}}
@media (prefers-reduced-motion:reduce){.shm::before,.fld input:focus~label span,.pay[data-phase=busy] .run{animation:none}.fld label,.gap,.go,.fold{transition-duration:.16s}}
</style></head><body><div class="wrap">
<div class="logo">KICK</div>
<div id="v-form"><h1>Sign in on your TV</h1><p>Type your Kick login here and your TV signs in.</p></div>
<div id="v-code" class="hide"><h1>Check your email</h1><p id="codemsg">Kick sent you a verification code. Enter it here.</p></div>
<div id="st" class="status"><span class="dot"></span><span id="stt" class="shm" data-text="Connecting to your TV…">Connecting to your TV…</span></div>
<form id="f" class="pay" data-phase="idle"><div class="fold"><div class="fold-in">
 <div class="fld"><input id="u" placeholder=" " autocomplete="username" autocapitalize="none" spellcheck="false" required><label for="u">Email or username</label><i class="gap"></i></div>
 <div class="fld pw"><input id="p" type="password" placeholder=" " autocomplete="current-password" required><label for="p">Password</label><i class="gap"></i>
  <button type="button" class="eye" id="eye" aria-label="Show password"><svg viewBox="0 0 24 24"><path d="M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0"/><circle cx="12" cy="12" r="3"/></svg><svg viewBox="0 0 24 24"><path d="M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49"/><path d="M14.084 14.158a3 3 0 0 1-4.242-4.242"/><path d="M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143"/><path d="m2 2 20 20"/></svg></button></div>
</div></div><button class="go"><span class="l l-idle">Sign in on TV</span><span class="l l-busy" aria-hidden="true"><svg width="64" height="4" viewBox="0 0 64 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><line x1="2" y1="2" x2="62" y2="2" opacity=".28"/><line class="run" x1="2" y1="2" x2="62" y2="2" pathLength="1"/></svg></span><span class="l l-done"><svg class="chk" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5" pathLength="1"/></svg>Signed in</span></button></form>
<form id="cf" class="pay hide" data-phase="idle"><div class="fold"><div class="fold-in">
 <div class="fld"><input id="c" inputmode="numeric" autocomplete="one-time-code" placeholder=" " required><label for="c">Code</label><i class="gap"></i></div>
</div></div><button class="go"><span class="l l-idle">Verify</span><span class="l l-busy" aria-hidden="true"><svg width="64" height="4" viewBox="0 0 64 4" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round"><line x1="2" y1="2" x2="62" y2="2" opacity=".28"/><line class="run" x1="2" y1="2" x2="62" y2="2" pathLength="1"/></svg></span><span class="l l-done"><svg class="chk" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5" pathLength="1"/></svg>Signed in</span></button></form>
<div class="small" id="note">Your login goes straight from this page to your TV over your home network, and from the TV to kick.com.</div>
</div><script>
var k=new URLSearchParams(location.search).get('k'),view='form',sentAt=0;
function g(i){return document.getElementById(i)}
// Each label becomes letters for the hop, and the outline's gap is cut to its width.
[].forEach.call(document.querySelectorAll('.fld'),function(f){var l=f.querySelector('label'),t=l.textContent;l.textContent='';
 t.split('').forEach(function(ch,i){var s=document.createElement('span');s.textContent=ch;s.style.setProperty('--i',i);l.appendChild(s);});
 f.style.setProperty('--lw',l.offsetWidth+'px');});
g('eye').onpointerdown=function(e){e.preventDefault()};
g('eye').onclick=function(){var p=g('p'),on=p.type==='password';p.type=on?'text':'password';this.classList.toggle('on',on);
 this.setAttribute('aria-label',on?'Hide password':'Show password');p.classList.remove('flip');void p.offsetWidth;p.classList.add('flip');};
function form(){return view==='code'?g('cf'):g('f')}
function phase(f,p){f.setAttribute('data-phase',p);var b=f.querySelector('.go');
 b.style.width=p==='done'?(f.querySelector('.l-done').offsetWidth+48)+'px':'';}
function show(v){if(v===view)return;view=v;g('v-form').classList.toggle('hide',v!=='form');g('f').classList.toggle('hide',v!=='form');
 g('v-code').classList.toggle('hide',v!=='code');g('cf').classList.toggle('hide',v!=='code');}
function status(text,state){var st=g('st'),t=g('stt');st.classList.toggle('err',state==='error');st.classList.toggle('hide',state==='done');
 t.classList.toggle('shm',state==='loading'||state==='working');t.textContent=text;t.setAttribute('data-text',text);}
// Just after a submit the TV may not have answered yet; don't unfold the form over it.
function settling(){return Date.now()-sentAt<4000}
function post(p,d){sentAt=Date.now();return fetch(p+'?k='+k,{method:'POST',body:JSON.stringify(d)});}
g('f').onsubmit=function(e){e.preventDefault();phase(g('f'),'busy');status('Sending to your TV…','working');
 post('/login',{user:g('u').value,password:g('p').value});};
g('cf').onsubmit=function(e){e.preventDefault();phase(g('cf'),'busy');status('Sending the code…','working');
 post('/code',{code:g('c').value});g('c').value='';};
function poll(){fetch('/status?k='+k).then(function(r){return r.json()}).then(function(s){
 if(s.state==='done'){var h=g(view==='code'?'v-code':'v-form');h.querySelector('h1').textContent="You're signed in";h.querySelector('p').textContent=s.message||'';
  status('','done');phase(form(),'done');g('note').classList.add('hide');return;}
 if(s.state==='code'){if(view!=='code'){show('code');phase(g('cf'),'idle');}else if(!settling())phase(g('cf'),'idle');
  if(s.message)g('codemsg').textContent=s.message;status('Connected to your TV','ready');}
 else if(s.state==='working'){phase(form(),'busy');status(s.message||'Hang on while your TV talks to Kick.','working');}
 else if(s.state==='error'){show('form');phase(g('f'),'idle');status(s.message||'Something went wrong. Try again.','error');}
 else if(!settling()){show('form');phase(g('f'),'idle');
  status(s.message||(s.state==='loading'?'Getting kick.com ready on your TV…':'Connected to your TV'),s.state);}
 setTimeout(poll,1500);}).catch(function(){status('Can\'t reach your TV. Make sure your phone is on the same Wi-Fi.','offline');setTimeout(poll,3000);});}
poll();
</script></body></html>
"""
    }
}
