package io.github.latencyscope.core

import android.content.Context
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

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

    /*
     * Watchers of session start/stop. Copy-on-write because the list is read
     * on every transition and written once in a blue moon, and because a
     * listener that unregisters itself while being notified must not throw.
     */
    private val sessionListeners = CopyOnWriteArrayList<SessionListener>()

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
            // The provider is built first so the device snapshot can record
            // which transport the session started on, and at what advertised
            // link speed.
            val network = NetworkTypeProvider(context)
            this.networkTypeProvider = network
            this.device = DeviceInfoCollector.collect(context, network)
            this.initialized = true
        }
        if (config.autoStart) start()
    }

    /** Overrides the time source. Visible for tests only. */
    @JvmStatic
    public fun setClockForTesting(clock: Clock) {
        synchronized(lock) { this.clock = clock }
    }

    /**
     * Configures without a [Context]; device info stays unknown. For tests.
     *
     * Honours [LatencyConfig.autoStart] exactly as [init] does. It used not
     * to, which made the flag untestable without a Context and silently
     * changed behaviour between a consumer's tests and their app.
     */
    @JvmStatic
    @JvmOverloads
    public fun initForTesting(config: LatencyConfig = LatencyConfig()) {
        synchronized(lock) {
            this.config = config
            this.device = DeviceInfo.UNKNOWN
            this.networkTypeProvider = null
            this.initialized = true
        }
        if (config.autoStart) start()
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
    }.also(::notifyStarted)

    /** Stops the active session and returns it, or null if none was running. */
    @JvmStatic
    public fun stop(): LatencySession? {
        val session = synchronized(lock) {
            val recorder = active ?: return null
            active = null
            recorder.snapshot(clock.epochMs(), clock.elapsedMs()).also { lastSession = it }
        }
        notifyStopped(session)
        return session
    }

    /** Stops only if [handle] is the session currently recording. */
    @JvmStatic
    public fun stopSession(handle: SessionHandle): LatencySession? {
        // The identity check is under the lock; stop() is not, so listeners
        // are never notified while this thread holds it.
        synchronized(lock) {
            if (active?.id != handle.id) return null
        }
        return stop()
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
    public fun reset() {
        val wasRecording = synchronized(lock) {
            val recording = active != null
            active = null
            lastSession = null
            config = LatencyConfig()
            device = DeviceInfo.UNKNOWN
            networkTypeProvider = null
            clock = Clock.SYSTEM
            initialized = false
            recording
        }
        // A watcher that is not told would be left showing a session that no
        // longer exists — a notification stuck on screen, for instance.
        if (wasRecording) notifyStopped(null)

        // Then drop them: this claims to tear everything down, and a listener
        // that outlives it keeps whatever it captured alive — an Application,
        // a notification, a whole previous test's state.
        sessionListeners.clear()
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
            ratingScale = config.ratingScale,
            generatedAtEpochMs = clock.epochMs(),
        )
    }

    // ---------------------------------------------------------------- watching

    /**
     * Notified when a session starts or stops, however it was triggered —
     * [start], [stop], [LatencyConfig.autoStart] or a [SessionHandle].
     *
     * This exists so a watcher can be event-driven instead of polling
     * [isRecording] on a timer. Callbacks run on the thread that started or
     * stopped the session, outside the lock, so a slow listener delays that
     * caller and nothing else — and can safely call back into LatencyScope.
     */
    public fun interface SessionListener {
        /** [session] is null when recording stopped. */
        public fun onSessionChanged(session: LatencySession?)
    }

    /** Registers [listener]. Safe to call twice; it is only added once. */
    @JvmStatic
    public fun addSessionListener(listener: SessionListener) {
        sessionListeners.addIfAbsent(listener)
    }

    @JvmStatic
    public fun removeSessionListener(listener: SessionListener) {
        sessionListeners.remove(listener)
    }

    /**
     * Calls recorded in the session so far, or 0 when none is running.
     *
     * O(1) and allocation-free, so a live display can read it as often as it
     * likes. [currentSession] answers the same question by snapshotting and
     * sorting every record, which is the wrong tool for a counter.
     */
    @JvmStatic
    public fun recordedCount(): Int = active?.recordedCount ?: 0

    /** Failed calls in the session so far. Counted on the way in, so also O(1). */
    @JvmStatic
    public fun errorCount(): Int = active?.errorCount ?: 0

    private fun notifyStarted(handle: SessionHandle) {
        if (sessionListeners.isEmpty()) return
        val snapshot = currentSession()
        sessionListeners.forEach { listener ->
            // One misbehaving listener must not stop the others, and must
            // never break the app's networking.
            runCatching { listener.onSessionChanged(snapshot) }
        }
    }

    private fun notifyStopped(session: LatencySession?) {
        sessionListeners.forEach { listener ->
            runCatching { listener.onSessionChanged(null) }
        }
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
