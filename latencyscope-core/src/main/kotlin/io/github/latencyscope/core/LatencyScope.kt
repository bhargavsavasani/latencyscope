package io.github.latencyscope.core

import android.content.Context
import java.util.UUID

/**
 * Entry point of the library.
 *
 * Typical use:
 * ```
 * LatencyScope.init(context)          // once, in Application.onCreate
 * LatencyScope.start("checkout flow") // when you begin the flow
 * // ... use the app ...
 * val session = LatencyScope.stop()
 * val report  = LatencyScope.report(session)
 * ```
 *
 * ### Threading
 * [submit] and [record] are called from arbitrary network threads. The first
 * thing either does is read one volatile field, so the cost when not recording
 * is a single memory read — that is what makes it safe to leave the
 * instrumentation installed.
 */
public object LatencyScope {

    /**
     * Read first on every recording call. Volatile rather than synchronized so
     * a non-recording app pays a memory read and nothing else.
     */
    @Volatile
    private var active: Recorder? = null

    @Volatile
    private var config: LatencyConfig = LatencyConfig()

    @Volatile
    private var device: DeviceInfo = DeviceInfo.UNKNOWN

    @Volatile
    private var networkTypeProvider: NetworkTypeProvider? = null

    @Volatile
    private var clock: Clock = Clock.SYSTEM

    @Volatile
    private var initialized: Boolean = false

    /** Last finished session, so a UI can show a report after the fact. */
    @Volatile
    private var lastSession: LatencySession? = null

    private val lock = Any()

    // ---------------------------------------------------------------- lifecycle

    /**
     * Installs the library. Safe to call more than once; the last config wins.
     *
     * Not required before [submit] — an un-initialised LatencyScope simply
     * records nothing, so an app that forgets this call in a release build
     * degrades to a no-op rather than crashing.
     */
    @JvmStatic
    @JvmOverloads
    public fun init(context: Context, config: LatencyConfig = LatencyConfig()) {
        synchronized(lock) {
            this.config = config
            this.device = DeviceInfoCollector.collect(context)
            this.networkTypeProvider = NetworkTypeProvider(context)
            this.initialized = true
        }
        if (config.autoStart) start()
    }

    /** Overrides the time source. Visible for tests only. */
    @JvmStatic
    public fun setClockForTesting(clock: Clock) {
        synchronized(lock) { this.clock = clock }
    }

    /** Configures without a [Context]; device info stays unknown. For tests. */
    @JvmStatic
    @JvmOverloads
    public fun initForTesting(config: LatencyConfig = LatencyConfig()) {
        synchronized(lock) {
            this.config = config
            this.device = DeviceInfo.UNKNOWN
            this.networkTypeProvider = null
            this.initialized = true
        }
    }

    /** Starts recording, replacing any session already in progress. */
    @JvmStatic
    @JvmOverloads
    public fun start(name: String? = null): SessionHandle = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        val startedAtEpochMs = clock.epochMs()
        val recorder = Recorder(
            id = id,
            name = name ?: defaultSessionName(startedAtEpochMs),
            config = config,
            device = device,
            clock = clock,
            startedAtEpochMs = startedAtEpochMs,
            startedAtElapsedMs = clock.elapsedMs(),
        )
        active = recorder
        SessionHandle(id, recorder.name, startedAtEpochMs)
    }

    /** Stops the active session and returns it, or null if none was running. */
    @JvmStatic
    public fun stop(): LatencySession? = synchronized(lock) {
        val recorder = active ?: return null
        active = null
        recorder.snapshot(clock.epochMs(), clock.elapsedMs()).also { lastSession = it }
    }

    /** Stops only if [handle] is the session currently recording. */
    @JvmStatic
    public fun stopSession(handle: SessionHandle): LatencySession? = synchronized(lock) {
        if (active?.id != handle.id) return null
        stop()
    }

    @JvmStatic
    public fun isRecording(): Boolean = active != null

    @JvmStatic
    public fun isInitialized(): Boolean = initialized

    /** Live view of the in-progress session, for a UI that updates as you browse. */
    @JvmStatic
    public fun currentSession(): LatencySession? {
        val recorder = active ?: return null
        return recorder.snapshot(endedAtEpochMs = null, nowElapsedMs = clock.elapsedMs())
    }

    /** The most recently stopped session. */
    @JvmStatic
    public fun lastSession(): LatencySession? = lastSession

    /**
     * Drops every recorded call — both from the session in progress and the
     * last finished one — while leaving recording on.
     */
    @JvmStatic
    public fun clear() {
        active?.clear()
        lastSession = null
    }

    /** Tears everything down. Mainly for tests and for `onTerminate`. */
    @JvmStatic
    public fun reset(): Unit = synchronized(lock) {
        active = null
        lastSession = null
        config = LatencyConfig()
        device = DeviceInfo.UNKNOWN
        networkTypeProvider = null
        clock = Clock.SYSTEM
        initialized = false
    }

    @JvmStatic
    public fun config(): LatencyConfig = config

    // ------------------------------------------------------------- recording

    /**
     * Records a finished call. Instrumentation modules call this; apps rarely
     * need to.
     */
    @JvmStatic
    public fun submit(call: RawCall) {
        val recorder = active ?: return
        if (!recorder.accepts(call.url)) return
        val enriched = if (call.networkType == null) {
            call.copy(networkType = networkTypeProvider?.current())
        } else {
            call
        }
        recorder.record(recorder.buildRecord(enriched))
    }

    /** Records an already-built record as-is, bypassing normalization. */
    @JvmStatic
    public fun record(record: ApiCallRecord) {
        active?.record(record)
    }

    /**
     * Times [block] and records it as a [Source.MANUAL] call.
     *
     * The exception path is recorded too and then rethrown — a flow that fails
     * is exactly when the timing matters, and swallowing the throwable would
     * change the caller's behaviour.
     */
    @JvmStatic
    @JvmOverloads
    public fun <T> track(
        url: String,
        method: String = "GET",
        block: () -> T,
    ): T {
        if (active == null) return block()

        val startEpoch = clock.epochMs()
        val startElapsed = clock.elapsedMs()
        try {
            val result = block()
            submit(
                RawCall(
                    source = Source.MANUAL,
                    url = url,
                    method = method,
                    statusCode = HTTP_OK,
                    startedAtEpochMs = startEpoch,
                    startedAtElapsedMs = startElapsed,
                    totalMs = clock.elapsedMs() - startElapsed,
                    threadName = Thread.currentThread().name,
                ),
            )
            return result
        } catch (throwable: Throwable) {
            submit(
                RawCall(
                    source = Source.MANUAL,
                    url = url,
                    method = method,
                    statusCode = null,
                    startedAtEpochMs = startEpoch,
                    startedAtElapsedMs = startElapsed,
                    totalMs = clock.elapsedMs() - startElapsed,
                    threadName = Thread.currentThread().name,
                    error = throwable.describe(),
                ),
            )
            throw throwable
        }
    }

    /** Drops a labelled landmark onto the session timeline. */
    @JvmStatic
    public fun mark(label: String) {
        active?.mark(label)
    }

    // ---------------------------------------------------------------- report

    /** Aggregates [session], defaulting to the last one that finished. */
    @JvmStatic
    @JvmOverloads
    public fun report(session: LatencySession? = null): LatencyReport? {
        val target = session ?: lastSession ?: currentSession() ?: return null
        return Aggregator.aggregate(
            session = target,
            warnThresholdMs = config.warnThresholdMs,
            slowThresholdMs = config.slowThresholdMs,
            generatedAtEpochMs = clock.epochMs(),
        )
    }

    /** Monotonic clock shared with instrumentation modules. */
    @JvmStatic
    public fun elapsedMs(): Long = clock.elapsedMs()

    /** Wall clock shared with instrumentation modules. */
    @JvmStatic
    public fun epochMs(): Long = clock.epochMs()

    private fun defaultSessionName(epochMs: Long): String = "Session $epochMs"

    private const val HTTP_OK = 200
}

/** Short, non-sensitive description of a failure: class name and message. */
public fun Throwable.describe(): String {
    val name = this::class.java.simpleName.ifEmpty { this::class.java.name }
    val message = message?.takeIf { it.isNotBlank() }
    return if (message == null) name else "$name: $message"
}
