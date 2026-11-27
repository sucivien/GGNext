package com.littlecodesauce.ggnext.mcp

import android.content.Context
import com.littlecodesauce.ggnext.data.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class McpCallLogEntry(
    val time: String,
    val method: String,
    val toolName: String,
    val ok: Boolean,
    val durationMs: Long
)

data class McpServerState(
    val running: Boolean = false,
    val bindHost: String = "127.0.0.1",
    val displayHost: String = "127.0.0.1",
    val port: Int = 27080,
    val allowLan: Boolean = false,
    val authEnabled: Boolean = false,
    val authToken: String = "",
    val activeSseSessions: Int = 0,
    val totalRequests: Long = 0L,
    val lastError: String = "",
    val recentLogs: List<McpCallLogEntry> = emptyList()
) {
    val mcpEndpointUrl: String get() = "http://$displayHost:$port/mcp"
    val sseEndpointUrl: String get() = "http://$displayHost:$port/sse"
}

/**
 * 零外部依赖的本地 MCP HTTP/1.1 服务器：
 * - 支持现代 MCP Streamable HTTP (`POST /mcp` + `GET /mcp` + `DELETE /mcp`)
 * - 兼容传统 MCP SSE 传输 (`GET /sse` + `POST /messages?sessionId=...`)
 * - 支持可选 Bearer Token 鉴权、CORS 预检与健康探针 (`GET /health`)
 */
object McpHttpServer {

    private const val PROTOCOL_VERSION = "2025-03-26"
    private const val SERVER_NAME = "ggnext-mcp"
    private const val SERVER_VERSION = "0.10.0"

    private val _state = MutableStateFlow(McpServerState())
    val state: StateFlow<McpServerState> = _state.asStateFlow()

    private val runningFlag = AtomicBoolean(false)
    private val requestCounter = AtomicLong(0L)
    private val sseSessions = ConcurrentHashMap<String, SseClient>()
    private val streamableSessions = ConcurrentHashMap<String, Long>()

    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var acceptThread: Thread? = null
    @Volatile
    private var workerPool: ExecutorService? = null
    @Volatile
    private var appContext: Context? = null

    private class SseClient(
        val sessionId: String,
        val socket: Socket,
        val output: OutputStream
    ) {
        val lock = Any()
        @Volatile
        var open: Boolean = true

        fun sendEvent(event: String, data: String): Boolean {
            if (!open || socket.isClosed) return false
            return try {
                synchronized(lock) {
                    val sb = StringBuilder()
                    sb.append("event: ").append(event).append("\n")
                    data.split('\n').forEach { line ->
                        sb.append("data: ").append(line).append("\n")
                    }
                    sb.append("\n")
                    output.write(sb.toString().toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                true
            } catch (_: Throwable) {
                open = false
                false
            }
        }

        fun closeQuietly() {
            open = false
            runCatching { socket.close() }
        }
    }

    fun generateToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return buildString(bytes.size * 2) {
            for (b in bytes) {
                val v = b.toInt() and 0xFF
                append("0123456789abcdef"[v ushr 4])
                append("0123456789abcdef"[v and 0x0F])
            }
        }
    }

    fun resolveLanIpv4(): String {
        return try {
            val ifaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (ni in ifaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (addr in ni.inetAddresses.toList()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress
                        if (!ip.isNullOrBlank()) return ip
                    }
                }
            }
            "127.0.0.1"
        } catch (_: Throwable) {
            "127.0.0.1"
        }
    }

    @Synchronized
    fun start(
        context: Context,
        port: Int,
        allowLan: Boolean,
        authEnabled: Boolean,
        authToken: String
    ): Pair<Boolean, String> {
        val safePort = port.coerceIn(1024, 65535)
        if (runningFlag.get()) {
            stop()
        }
        appContext = context.applicationContext
        val bindHost = if (allowLan) "0.0.0.0" else "127.0.0.1"
        val displayHost = if (allowLan) resolveLanIpv4() else "127.0.0.1"

        return try {
            val ss = ServerSocket(safePort, 32, InetAddress.getByName(bindHost))
            ss.reuseAddress = true
            serverSocket = ss
            workerPool = Executors.newCachedThreadPool { r ->
                Thread(r, "GGNext-MCP-Worker").apply { isDaemon = true }
            }
            runningFlag.set(true)
            requestCounter.set(0L)
            _state.value = McpServerState(
                running = true,
                bindHost = bindHost,
                displayHost = displayHost,
                port = safePort,
                allowLan = allowLan,
                authEnabled = authEnabled,
                authToken = authToken,
                activeSseSessions = 0,
                totalRequests = 0L,
                lastError = "",
                recentLogs = emptyList()
            )
            acceptThread = Thread({ acceptLoop(ss) }, "GGNext-MCP-Accept").apply {
                isDaemon = true
                start()
            }
            AppLog.i("McpHttpServer", "Started MCP server on $bindHost:$safePort (display=$displayHost)")
            true to ""
        } catch (t: Throwable) {
            val msg = t.message ?: t.javaClass.simpleName
            AppLog.e("McpHttpServer", "Failed to start MCP server on port $safePort", t)
            _state.update { it.copy(running = false, lastError = msg) }
            false to msg
        }
    }

    @Synchronized
    fun stop() {
        runningFlag.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        sseSessions.values.forEach { it.closeQuietly() }
        sseSessions.clear()
        streamableSessions.clear()
        runCatching { workerPool?.shutdownNow() }
        workerPool = null
        acceptThread = null
        _state.update { it.copy(running = false, activeSseSessions = 0) }
        AppLog.i("McpHttpServer", "Stopped MCP server")
    }

    fun buildClientConfigJson(): String {
        val st = _state.value
        val root = JSONObject()
        val servers = JSONObject()
        val entry = JSONObject()
            .put("type", "http")
            .put("url", st.mcpEndpointUrl)
        if (st.authEnabled && st.authToken.isNotBlank()) {
            entry.put("headers", JSONObject().put("Authorization", "Bearer ${st.authToken}"))
        }
        servers.put("ggnext", entry)
        root.put("mcpServers", servers)
        return root.toString(2)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (runningFlag.get() && !ss.isClosed) {
            try {
                val client = ss.accept()
                client.soTimeout = 30_000
                workerPool?.execute { handleClient(client) }
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                if (runningFlag.get()) {
                    AppLog.w("McpHttpServer", "Accept error: ${t.message}")
                }
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun handleClient(socket: Socket) {
        var keepOpenForSse = false
        try {
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 8192)
            val req = readHttpRequest(input) ?: return

            if (req.method == "OPTIONS") {
                writeResponse(output, 204, "No Content", "text/plain", ByteArray(0))
                return
            }

            if (req.method == "GET" && req.path == "/health") {
                val st = _state.value
                val body = JSONObject()
                    .put("status", "ok")
                    .put("server", SERVER_NAME)
                    .put("version", SERVER_VERSION)
                    .put("protocolVersion", PROTOCOL_VERSION)
                    .put("toolsCount", McpToolExecutor.toolSummaries.size)
                    .put("requests", st.totalRequests)
                    .toString()
                writeResponse(output, 200, "OK", "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
                return
            }

            if (!checkAuthorized(req)) {
                val err = JSONObject().put("error", "Unauthorized").toString()
                writeResponse(output, 401, "Unauthorized", "application/json; charset=utf-8", err.toByteArray(Charsets.UTF_8))
                return
            }

            when {
                req.path == "/mcp" && req.method == "POST" -> handleStreamablePost(req, output)
                req.path == "/mcp" && req.method == "GET" -> {
                    keepOpenForSse = true
                    handleSseStream(socket, output, legacyEndpoint = false)
                }
                req.path == "/mcp" && req.method == "DELETE" -> {
                    req.headers["mcp-session-id"]?.let { streamableSessions.remove(it) }
                    writeResponse(output, 200, "OK", "application/json; charset=utf-8", "{\"closed\":true}".toByteArray(Charsets.UTF_8))
                }
                req.path == "/sse" && req.method == "GET" -> {
                    keepOpenForSse = true
                    handleSseStream(socket, output, legacyEndpoint = true)
                }
                req.path == "/messages" && req.method == "POST" -> handleLegacyMessagesPost(req, output)
                else -> {
                    val body = JSONObject()
                        .put("error", "Not Found")
                        .put("endpoints", JSONArray(listOf("/mcp", "/sse", "/messages", "/health")))
                        .toString()
                    writeResponse(output, 404, "Not Found", "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))
                }
            }
        } catch (t: Throwable) {
            if (runningFlag.get()) {
                AppLog.w("McpHttpServer", "Client error: ${t.message}")
            }
        } finally {
            if (!keepOpenForSse) {
                runCatching { socket.close() }
            }
        }
    }

    private fun checkAuthorized(req: HttpRequest): Boolean {
        val st = _state.value
        if (!st.authEnabled || st.authToken.isBlank()) return true
        val authHeader = req.headers["authorization"].orEmpty().trim()
        if (authHeader.startsWith("Bearer ", ignoreCase = true) &&
            authHeader.substring(7).trim() == st.authToken
        ) {
            return true
        }
        return req.query["token"] == st.authToken
    }

    private fun handleStreamablePost(req: HttpRequest, output: OutputStream) {
        val rawBody = String(req.body, Charsets.UTF_8).trim()
        if (rawBody.isEmpty()) {
            val err = rpcError(JSONObject.NULL, -32700, "Empty request body")
            writeResponse(output, 400, "Bad Request", "application/json; charset=utf-8", err.toString().toByteArray(Charsets.UTF_8))
            return
        }
        val sessionId = req.headers["mcp-session-id"]?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { streamableSessions[it] = System.currentTimeMillis() }

        val extraHeaders = mapOf("Mcp-Session-Id" to sessionId)
        val responsePayload = processJsonRpcPayload(rawBody)
        if (responsePayload == null) {
            writeResponse(output, 202, "Accepted", "application/json; charset=utf-8", ByteArray(0), extraHeaders)
        } else {
            writeResponse(
                output,
                200,
                "OK",
                "application/json; charset=utf-8",
                responsePayload.toByteArray(Charsets.UTF_8),
                extraHeaders
            )
        }
    }

    private fun handleLegacyMessagesPost(req: HttpRequest, output: OutputStream) {
        val sessionId = req.query["sessionId"].orEmpty()
        val rawBody = String(req.body, Charsets.UTF_8).trim()
        val responsePayload = processJsonRpcPayload(rawBody)
        val sseClient = if (sessionId.isNotEmpty()) sseSessions[sessionId] else null
        if (sseClient != null && responsePayload != null) {
            sseClient.sendEvent("message", responsePayload)
            writeResponse(output, 202, "Accepted", "application/json; charset=utf-8", "{\"accepted\":true}".toByteArray(Charsets.UTF_8))
        } else if (responsePayload != null) {
            writeResponse(output, 200, "OK", "application/json; charset=utf-8", responsePayload.toByteArray(Charsets.UTF_8))
        } else {
            writeResponse(output, 202, "Accepted", "application/json; charset=utf-8", ByteArray(0))
        }
    }

    private fun handleSseStream(socket: Socket, output: OutputStream, legacyEndpoint: Boolean) {
        val sessionId = UUID.randomUUID().toString()
        socket.soTimeout = 0
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Mcp-Session-Id: ").append(sessionId).append("\r\n\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.flush()

        val client = SseClient(sessionId, socket, output)
        sseSessions[sessionId] = client
        _state.update { it.copy(activeSseSessions = sseSessions.size) }

        try {
            if (legacyEndpoint) {
                val tokenSuffix = if (_state.value.authEnabled && _state.value.authToken.isNotBlank()) {
                    "&token=${_state.value.authToken}"
                } else ""
                client.sendEvent("endpoint", "/messages?sessionId=$sessionId$tokenSuffix")
            }
            while (runningFlag.get() && client.open && !socket.isClosed) {
                Thread.sleep(15_000)
                if (!runningFlag.get() || !client.open) break
                synchronized(client.lock) {
                    output.write(": keepalive\n\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                }
            }
        } catch (_: Throwable) {
        } finally {
            sseSessions.remove(sessionId)
            client.closeQuietly()
            _state.update { it.copy(activeSseSessions = sseSessions.size) }
        }
    }

    private fun processJsonRpcPayload(rawBody: String): String? {
        return try {
            if (rawBody.startsWith("[")) {
                val arr = JSONArray(rawBody)
                val outArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    dispatchSingleRpc(obj)?.let { outArr.put(it) }
                }
                if (outArr.length() == 0) null else outArr.toString()
            } else {
                val obj = JSONObject(rawBody)
                dispatchSingleRpc(obj)?.toString()
            }
        } catch (t: Throwable) {
            rpcError(JSONObject.NULL, -32700, "Parse error: ${t.message}").toString()
        }
    }

    private fun dispatchSingleRpc(req: JSONObject): JSONObject? {
        val hasId = req.has("id") && !req.isNull("id")
        val id = if (hasId) req.get("id") else JSONObject.NULL
        val method = req.optString("method", "")
        val params = req.optJSONObject("params") ?: JSONObject()

        if (method.isEmpty()) {
            return if (hasId) rpcError(id, -32600, "Invalid Request: missing method") else null
        }

        val count = requestCounter.incrementAndGet()
        val startMs = System.currentTimeMillis()
        var toolNameForLog = ""
        var okForLog = true

        val response: JSONObject? = when (method) {
            "initialize" -> {
                val clientProto = params.optString("protocolVersion", PROTOCOL_VERSION).ifBlank { PROTOCOL_VERSION }
                val res = JSONObject()
                    .put("protocolVersion", clientProto)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))
                rpcResult(id, res)
            }
            "notifications/initialized", "notifications/cancelled" -> null
            "ping" -> rpcResult(id, JSONObject())
            "tools/list" -> {
                val res = JSONObject().put("tools", McpToolExecutor.toolDefinitions())
                rpcResult(id, res)
            }
            "tools/call" -> {
                val toolName = params.optString("name", "")
                toolNameForLog = toolName
                val arguments = params.optJSONObject("arguments") ?: JSONObject()
                val ctx = appContext
                if (toolName.isEmpty() || ctx == null) {
                    okForLog = false
                    rpcError(id, -32602, "Invalid params: missing tool name or context")
                } else {
                    val toolRes = McpToolExecutor.executeTool(ctx, toolName, arguments)
                    okForLog = !toolRes.isError
                    val content = JSONArray().put(
                        JSONObject().put("type", "text").put("text", toolRes.text)
                    )
                    val res = JSONObject()
                        .put("content", content)
                        .put("isError", toolRes.isError)
                    rpcResult(id, res)
                }
            }
            else -> {
                okForLog = false
                if (hasId) rpcError(id, -32601, "Method not found: $method") else null
            }
        }

        val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(0L)
        val timeText = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val logEntry = McpCallLogEntry(
            time = timeText,
            method = method,
            toolName = toolNameForLog,
            ok = okForLog,
            durationMs = elapsed
        )
        _state.update { st ->
            st.copy(
                totalRequests = count,
                recentLogs = (listOf(logEntry) + st.recentLogs).take(20)
            )
        }
        return response
    }

    private fun rpcResult(id: Any, result: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("result", result)

    private fun rpcError(id: Any, code: Int, message: String): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message))

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val headerBuf = ByteArrayOutputStream(1024)
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) return null
            headerBuf.write(b)
            if (headerBuf.size() > 64 * 1024) return null
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val headerStr = headerBuf.toString(Charsets.UTF_8.name())
        val lines = headerStr.split("\r\n")
        if (lines.isEmpty()) return null
        val reqParts = lines[0].split(' ')
        if (reqParts.size < 2) return null
        val method = reqParts[0].uppercase(Locale.ROOT)
        val fullTarget = reqParts[1]
        val qIdx = fullTarget.indexOf('?')
        val path = if (qIdx >= 0) fullTarget.substring(0, qIdx) else fullTarget
        val query = HashMap<String, String>()
        if (qIdx >= 0 && qIdx + 1 < fullTarget.length) {
            fullTarget.substring(qIdx + 1).split('&').forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    query[pair.substring(0, eq)] = pair.substring(eq + 1)
                }
            }
        }
        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val col = line.indexOf(':')
            if (col > 0) {
                headers[line.substring(0, col).trim().lowercase(Locale.ROOT)] = line.substring(col + 1).trim()
            }
        }
        val contentLen = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 4 * 1024 * 1024) ?: 0
        val body = if (contentLen > 0) {
            val buf = ByteArray(contentLen)
            var readTotal = 0
            while (readTotal < contentLen) {
                val n = input.read(buf, readTotal, contentLen - readTotal)
                if (n <= 0) break
                readTotal += n
            }
            if (readTotal == contentLen) buf else buf.copyOf(readTotal)
        } else {
            ByteArray(0)
        }
        return HttpRequest(method, path, query, headers, body)
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type, Authorization, Mcp-Session-Id\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        sb.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) ->
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) output.write(body)
        output.flush()
    }
}
