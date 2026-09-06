package com.liskovsoft.youtubeapi.app.potokennp2.generators

import android.content.Context
import androidx.annotation.MainThread
import com.caoccao.javet.enums.JSRuntimeType
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.V8ScriptOrigin
import com.caoccao.javet.interop.callback.JavetCallbackContext
import com.caoccao.javet.interop.callback.JavetCallbackType
import com.caoccao.javet.interop.engine.IJavetEngine
import com.caoccao.javet.interop.engine.JavetEngine
import com.caoccao.javet.interop.engine.JavetEngineConfig
import com.caoccao.javet.interop.engine.JavetEnginePool
import com.liskovsoft.sharedutils.mylogger.Log
import com.liskovsoft.sharedutils.okhttp.OkHttpManager
import com.liskovsoft.youtubeapi.app.nsigsolver.common.loadScript
import com.liskovsoft.youtubeapi.common.helpers.AppClient
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenException
import com.liskovsoft.youtubeapi.app.potokennp2.core.PoTokenGenerator
import com.caoccao.javet.interop.callback.IJavetDirectCallable
import com.caoccao.javet.values.V8Value
import com.liskovsoft.youtubeapi.app.potokennp2.core.buildExceptionForJsError
import com.liskovsoft.youtubeapi.app.potokennp2.misc.parseDescrambledChallengeData
import com.liskovsoft.youtubeapi.app.potokennp2.misc.parseIntegrityTokenData
import com.liskovsoft.youtubeapi.app.potokennp2.misc.potLibPrefix
import com.liskovsoft.youtubeapi.app.potokennp2.misc.stringToU8
import com.liskovsoft.youtubeapi.app.potokennp2.misc.u8ToBase64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * WebView-less poToken generator that runs the BotGuard VM inside Node.js
 * (javet-android-node: real timers and microtask pumping, i.e. the environment
 * the VM is proven to work in, see yt-dlp's bgutils-node).
 *
 * NOTE: the Node runtime is bound to the thread that created it, so the whole
 * lifecycle (init + minting) happens on a single dedicated thread.
 */
internal class PoTokenNodeJs private constructor(
    context: Context,
    private var onInitDone: () -> Unit
) : PoTokenGenerator {
    private var engine: IJavetEngine<NodeRuntime>? = null
    private var runtime: NodeRuntime? = null
    private var expirationMs: Long = -1
    private var integrityTokenBase64: String? = null
    private val mintQueue = LinkedBlockingQueue<MintRequest>()
    @Volatile
    private var closed = false
    var initError: Throwable? = null

    init {
        val pool = JavetEnginePool<NodeRuntime>(
            JavetEngineConfig()
                .setJSRuntimeType(JSRuntimeType.Node)
                // NOTE: the BotGuard VM is loaded via `new Function(...)` (code from strings).
                .setAllowEval(true)
        )
        engine = pool.getEngine()
        runtime = engine?.v8Runtime

        registerCallbacks()

        loadScriptAndObtainBotguard()
    }

    private fun registerCallbacks() {
        val rt = runtime ?: return
        val global = rt.globalObject
        val onRunBotguard = JavetCallbackContext(
            "onRunBotguardResult", JavetCallbackType.DirectCallNoThisAndResult,
            IJavetDirectCallable.NoThisAndResult<Exception> { v8Values ->
                val botguardResponse = v8Values?.getOrNull(0)?.toString() ?: ""
                onRunBotguardResult(botguardResponse)
                null
            })
        val onJsInitError = JavetCallbackContext(
            "onJsInitializationError", JavetCallbackType.DirectCallNoThisAndResult,
            IJavetDirectCallable.NoThisAndResult<Exception> { v8Values ->
                val error = v8Values?.getOrNull(0)?.toString() ?: "unknown"
                onJsInitializationError(error)
                null
            })
        global.setProperty("onRunBotguardResult", rt.createV8ValueFunction(onRunBotguard))
        global.setProperty("onJsInitializationError", rt.createV8ValueFunction(onJsInitError))
        global.close()
    }

    private fun executeVoidScript(script: String) {
        runtime?.execute<V8Value>(script, null, V8ScriptOrigin("pot.js"), false)
    }

    private fun executeStringScript(script: String): String? {
        return runtime?.execute<V8Value>(script, null, V8ScriptOrigin("pot.js"), true)?.toString()
    }

    private fun loadScriptAndObtainBotguard() {
        Log.d(TAG, "loadScriptAndObtainBotguard() called")

        // NOTE: the BotGuard VM probes the browser environment while executing
        // the challenge program; missing globals (navigator, screen, Intl...)
        // make the VM record errors and silently skip the webPo signal setup.
        executeVoidScript(
            """
                try {
                    Object.defineProperty(globalThis, 'navigator', {
                        value: {
                            userAgent: 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36',
                            language: 'en-US',
                            languages: ['en-US', 'en'],
                            platform: 'Linux x86_64',
                            hardwareConcurrency: 4,
                            plugins: [],
                            mimeTypes: [],
                            vendor: 'Google Inc.',
                            appVersion: '5.0 (X11; Linux x86_64)'
                        },
                        writable: true, configurable: true
                    });
                } catch (e) {}
                globalThis.screen = {
                    width: 1920, height: 1080,
                    availWidth: 1920, availHeight: 1040,
                    colorDepth: 24, pixelDepth: 24,
                    availLeft: 0, availTop: 0
                };
                globalThis.location = {
                    href: 'https://www.youtube.com/',
                    protocol: 'https:',
                    host: 'www.youtube.com',
                    hostname: 'www.youtube.com',
                    origin: 'https://www.youtube.com',
                    pathname: '/', search: '', hash: ''
                };
                globalThis.history = { length: 1, pushState: function () {}, replaceState: function () {} };
                globalThis.localStorage = { getItem: function () { return null; }, setItem: function () {}, removeItem: function () {}, clear: function () {} };
                globalThis.sessionStorage = { getItem: function () { return null; }, setItem: function () {}, removeItem: function () {}, clear: function () {} };
                globalThis.Intl = {
                    DateTimeFormat: function () {
                        return {
                            resolvedOptions: function () {
                                return { timeZone: 'UTC', locale: 'en-US', calendar: 'gregory', numberingSystem: 'latn' };
                            },
                            format: function (d) { return new Date(d == null ? Date.now() : d).toISOString(); }
                        };
                    },
                    NumberFormat: function () {
                        return {
                            format: function (n) { return String(n); },
                            resolvedOptions: function () { return { locale: 'en-US' }; }
                        };
                    },
                    Collator: function () {
                        return { compare: function (a, b) { return a < b ? -1 : a > b ? 1 : 0; } };
                    }
                };
            """
        )

        // Some bundled scripts (po_token.js) expect a `window` object to exist.
        // NOTE: the timer polyfills are not needed, Node.js has native timers.
        executeVoidScript("globalThis.window = globalThis;")

        // NOTE: the BotGuard VM reads `document.hidden`, `document.readyState`
        // and `document.removeEventListener` (bare reads); when `document` is
        // missing the VM catches a ReferenceError and silently skips the webPo
        // signal setup, so a minimal DOM shim is required.
        executeVoidScript(
            """
                globalThis.document = {
                    readyState: 'complete',
                    hidden: false,
                    visibilityState: 'visible',
                    addEventListener: function () {},
                    removeEventListener: function () {},
                    createElement: function () { return { style: {}, getContext: function () { return null; } }; },
                    getElementsByTagName: function () { return []; },
                    documentElement: { style: {} },
                    body: { appendChild: function () {}, style: {} },
                    head: { appendChild: function () {} }
                };
            """
        )

        // NOTE: the BotGuard VM probes the environment and skips the webPo
        // signal setup when it detects a Node.js-like context (process etc.),
        // so hide the Node tells to make it look like a bare V8 (j2v8-like).
        executeVoidScript(
            """
                try { delete globalThis.process; } catch (e) {}
                try { delete globalThis.Buffer; } catch (e) {}
                try { delete globalThis.global; } catch (e) {}
                try { delete globalThis.setImmediate; } catch (e) {}
                try { delete globalThis.clearImmediate; } catch (e) {}
            """
        )

        // Environment probe: what the BotGuard VM will see in this runtime.
        executeVoidScript(
            """
                globalThis.__envProbe = 'WA=' + (typeof WebAssembly) +
                    '; WAvalidate=' + (typeof WebAssembly !== 'undefined' ? WebAssembly.validate(new Uint8Array([0,97,115,109,1,0,0,0])) : 'n/a') +
                    '; perf=' + (typeof performance) +
                    '; btoa=' + (typeof btoa) +
                    '; doc=' + (typeof document) +
                    '; crypto=' + (typeof crypto) +
                    '; Intl=' + (typeof Intl) +
                    '; Uint8Array=' + (typeof Uint8Array);
            """
        )
        run {
            val probe: com.caoccao.javet.values.primitive.V8ValueString? =
                engine?.v8Runtime?.globalObject?.getProperty("__envProbe")
            Log.i(TAG, "envProbe: $probe")
            probe?.close()
        }

        executeVoidScript(loadScript(listOf("${potLibPrefix}v8/po_token.js")))
        downloadAndRunBotguard()
    }

    private fun downloadAndRunBotguard() {
        Log.d(TAG, "downloadAndRunBotguard() called")

        val client = AppClient.WEB

        val responseBody = makeBotguardServiceRequest(
            "https://www.youtube.com/youtubei/v1/att/get?prettyPrint=false",
            """
                {
                            context: {
                                client: {
                                    clientName: "${client.clientName}",
                                    clientVersion: "${client.clientVersion}",
                                },
                            },
                            engagementType: "ENGAGEMENT_TYPE_UNBOUND",
             }
            """,
            mapOf(
                "Content-Type" to "application/json"
            )
        ) ?: return

        val parsedChallengeData = parseDescrambledChallengeData(responseBody)

        executeVoidScript(
            """
                try {
                    var data = $parsedChallengeData;
                    runBotGuard(data).then(function (result) {
                        globalThis.webPoSignalOutput = result.webPoSignalOutput;
                        onRunBotguardResult(result.botguardResponse);
                    }, function (error) {
                        onJsInitializationError(error + "\n" + error.stack);
                    });
                } catch (error) {
                    onJsInitializationError(error + "\n" + error.stack);
                }
            """
        )
    }

    private fun onJsInitializationError(error: String) {
        val msg = "onJsInitializationError: $error"
        Log.e(TAG, msg)
        initError = buildExceptionForJsError(msg)
        onInitDone()
    }

    private fun onRunBotguardResult(botguardResponse: String) {
        Log.d(TAG, "botguardResponse: $botguardResponse")

        val responseBody = makeBotguardServiceRequest(
            "$BASE_URL/\$rpc/google.internal.waa.v1.Waa/GenerateIT",
            "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]",
        ) ?: return

        Log.d(TAG, "GenerateIT response: $responseBody")
        val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)

        expirationMs = System.currentTimeMillis() + ((expirationTimeInSeconds - 600) * 1_000)

        integrityTokenBase64 = integrityToken

        Log.d(TAG, "initialization finished, expiration=${expirationTimeInSeconds}s")
        onInitDone()
    }

    private fun makeBotguardServiceRequest(
        url: String,
        data: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        val response = OkHttpManager.instance().doPostRequest(
            url,
            mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json",
                "Content-Type" to "application/json+protobuf",
                "x-goog-api-key" to GOOGLE_API_KEY,
                "x-user-agent" to "grpc-web-javascript/0.1",
            ) + headers,
            data,
            null
        )

        val httpCode = response.code()

        if (httpCode != 200) {
            initError = PoTokenException("Invalid response code: $httpCode")
        }

        if (response.body() == null) {
            initError = PoTokenException("Response body is empty. Response code: $httpCode")
        }

        return response.body()?.string()
    }

    // The mint itself is executed on the runtime owner thread (see [MintRequest]).
    fun mintBlocking(identifier: String): String {
        val request = MintRequest(identifier)
        mintQueue.put(request)

        if (!request.latch.await(MINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw PoTokenException("Can't mint poToken: timeout")
        }

        request.error?.let { throw it }

        return request.result ?: throw PoTokenException("Can't mint poToken: empty result")
    }

    private fun doMint(identifier: String): String {
        val integrityToken = integrityTokenBase64
            ?: throw PoTokenException("integrityToken is not ready")
        val u8Identifier = stringToU8(identifier)

        val poTokenU8String = executeStringScript(
            """
                try {
                    poTokenU8 = obtainPoToken(webPoSignalOutput, "$integrityToken", $u8Identifier);
                    poTokenU8String = "";
                    for (i = 0; i < poTokenU8.length; i++) {
                        if (i != 0) poTokenU8String += ",";
                        poTokenU8String += poTokenU8[i];
                    }
                    poTokenU8String;
                } catch (error) {
                    "ERROR: " + error + "\n" + error.stack;
                }
            """.trimIndent()
        ) ?: throw PoTokenException("V8 runtime error: empty response")

        if (poTokenU8String.startsWith("ERROR: ")) {
            val msg = "onObtainPoTokenError: identifier=$identifier error=$poTokenU8String"
            Log.e(TAG, msg)
            throw buildExceptionForJsError(msg)
        }

        Log.d(TAG, "Generated poToken: identifier=$identifier poTokenU8=$poTokenU8String")
        return u8ToBase64(poTokenU8String)
    }

    override fun generatePoToken(identifier: String): String {
        Log.d(TAG, "generatePoToken() called with identifier $identifier")

        initError?.let { throw it }

        return mintBlocking(identifier)
    }

    override fun isExpired(): Boolean {
        return System.currentTimeMillis() > expirationMs
    }

    @MainThread
    override fun close() {
        closed = true
        try {
            engine?.close()
        } catch (_: Throwable) {
        }
        engine = null
        runtime = null
    }

    private class MintRequest(val identifier: String) {
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: Throwable? = null
    }

    companion object : PoTokenGenerator.Factory {
        private val TAG = PoTokenNodeJs::class.simpleName
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw" // NOSONAR
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36(KHTML, like Gecko)"
        private const val BASE_URL = "https://jnn-pa.googleapis.com"
        private const val INIT_TIMEOUT_MS = 30_000L
        private const val MINT_TIMEOUT_MS = 15_000L

        override fun newPoTokenGenerator(context: Context): PoTokenGenerator {
            val initLatch = CountDownLatch(1)
            var generator: PoTokenNodeJs? = null
            var threadError: Throwable? = null

            val thread = Thread {
                try {
                    val gen = PoTokenNodeJs(context) { initLatch.countDown() }
                    generator = gen

                    gen.initError?.let { throw it }

                    // Wait for the init chain (BotGuard + GenerateIT) while pumping
                    // the Node event loop.
                    while (!initLatch.await(50, TimeUnit.MILLISECONDS)) {
                        try {
                            gen.runtime?.await()
                        } catch (_: Throwable) {
                        }
                    }

                    gen.initError?.let { throw it }

                    // The owner thread keeps pumping the Node event loop and serves
                    // mint requests, so that all runtime access stays on this thread.
                    while (!gen.closed) {
                        val request = gen.mintQueue.poll(50, TimeUnit.MILLISECONDS)
                        if (request != null) {
                            try {
                                request.result = gen.doMint(request.identifier)
                            } catch (t: Throwable) {
                                request.error = t
                            } finally {
                                request.latch.countDown()
                            }
                        } else {
                            try {
                                gen.runtime?.await()
                            } catch (_: Throwable) {
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Node.js thread failed", t)
                    threadError = t
                    initLatch.countDown()
                }
            }
            thread.isDaemon = true
            thread.name = "pot-nodejs"
            thread.start()

            initLatch.await(INIT_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)

            threadError?.let { throw it }

            val gen = generator ?: throw PoTokenException("Node.js runtime not initialized")

            gen.initError?.let { throw it }

            return gen
        }
    }
}