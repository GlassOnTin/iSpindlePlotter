package com.ispindle.plotter.network

import android.util.Log
import com.ispindle.plotter.data.IspindlePayload
import com.ispindle.plotter.data.Repository
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * iSpindel firmware (Generic HTTP) POSTs a single JSON object per wake cycle.
 * It does not enforce a fixed path — the user configures whatever path they
 * set in the config portal — so every POST path funnels into the same handler.
 */
class IspindleHttpServer(
    private val repository: Repository,
    private val port: Int = DEFAULT_PORT
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state

    sealed class State {
        data object Stopped : State()
        data class Running(val port: Int) : State()
        data class Error(val message: String) : State()
    }

    fun start() {
        if (engine != null) return
        try {
            val e = embeddedServer(CIO, host = "0.0.0.0", port = port) { configure() }
            engine = e
            scope.launch { e.start(wait = false) }
            _state.value = State.Running(port)
            Log.i(TAG, "HTTP server listening on :$port")
        } catch (t: Throwable) {
            _state.value = State.Error(t.message ?: t.javaClass.simpleName)
            Log.e(TAG, "Start failed", t)
        }
    }

    fun stop() {
        engine?.stop(500, 1_500)
        engine = null
        _state.value = State.Stopped
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun Application.configure() {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
        install(StatusPages) {
            exception<Throwable> { call, cause ->
                Log.e(TAG, "Request failed", cause)
                call.respondText(
                    "error: ${cause.message}",
                    status = HttpStatusCode.InternalServerError
                )
            }
        }
        routing {
            get("/") { call.respondText("iSpindle Plotter — POST JSON here.") }
            get("/health") { call.respondText("ok") }

            post("/") { ingest(call, repository) }
            post("/ispindel") { ingest(call, repository) }
            post("/api/v1/ispindel") { ingest(call, repository) }
        }
    }

    private suspend fun ingest(call: RoutingCall, repository: Repository) {
        val payload = try {
            call.receive<IspindlePayload>()
        } catch (t: Throwable) {
            Log.w(TAG, "Bad payload: ${t.message}")
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (t.message ?: "bad json")))
            return
        }
        repository.ingest(payload, System.currentTimeMillis())
        call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
    }

    companion object {
        const val DEFAULT_PORT = 9501
        private const val TAG = "IspindleHttpServer"
    }
}
