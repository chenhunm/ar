package com.huanban.ar

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.github.sceneview.SceneView
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class RenderHost(
    val context: Context,
    val ar: Boolean = false,
    val transparent: Boolean = false,
    val report: (String) -> Unit = {},
) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = registry

    private val prefs = context.getSharedPreferences("settings", 0)
    val fpsLimit = prefs.getInt("fps", 30).coerceIn(15, 60)
    private var lastDraw = 0L
    private val sharedRenderEngine = RenderEngine.acquire()
    val view: SceneView =
        if (ar)
            object :
                ARSceneView(
                    context,
                    sharedActivity = context as? ComponentActivity,
                    sharedEngine = sharedRenderEngine,
                    sharedLifecycle = registry,
                ) {
                override fun performClick(): Boolean {
                    super.performClick()
                    return true
                }

                override fun onFrame(frameTimeNanos: Long) {
                    if (frameTimeNanos - lastDraw >= 1_000_000_000L / fpsLimit - 1_000_000) {
                        lastDraw = frameTimeNanos
                        super.onFrame(frameTimeNanos)
                    }
                }
            }
        else
            object :
                SceneView(
                    context,
                    isOpaque = !transparent,
                    sharedLifecycle = registry,
                    sharedActivity = context as? ComponentActivity,
                    sharedEngine = sharedRenderEngine,
                ) {
                override fun performClick(): Boolean {
                    super.performClick()
                    return true
                }

                override fun onFrame(frameTimeNanos: Long) {
                    if (frameTimeNanos - lastDraw >= 1_000_000_000L / fpsLimit - 1_000_000) {
                        lastDraw = frameTimeNanos
                        super.onFrame(frameTimeNanos)
                    }
                }
            }
    private val executor = Executors.newSingleThreadExecutor()
    var node: ModelNode? = null
        private set

    var role: Role? = null
        private set

    private var generation = 0
    private var disposed = false
    private var floor: ModelNode? = null
    private var current = 0
    private var previous = -1
    private var previousTime = 0f
    private var timeline = 0f
    private var transition = 1f
    private var last = 0L
    var playing = true
    var looping = true
    var speed = 1f
    var yaw = 0f
    var zoom = 1f
    private var x = 0f
    private var y = 0f
    private var frameCount = 0
    private var statsStart = SystemClock.elapsedRealtime()
    var onStats: ((String) -> Unit)? = null

    init {
        registry.currentState = Lifecycle.State.CREATED
        if (transparent) {
            view.setZOrderMediaOverlay(true)
            view.view.dithering=com.google.android.filament.View.Dithering.NONE
        }
        if (!ar) {
            view.cameraNode.position = Position(0f, 0f, 3f)
            view.cameraNode.lookAt(Position(0f))
            view.cameraManipulator = null
        }
        val high = prefs.getString("quality", "balanced") == "high"
        view.view.setShadowingEnabled(high)
        if (!ar && !transparent) {
            floor =
                ModelNode(
                        view.modelLoader.createModelInstance("models/ground.glb"),
                        autoAnimate = false,
                    )
                    .also {
                        it.position = Position(0f, -.93f, 0f)
                        it.isShadowReceiver = true
                        view.addChildNode(it)
                    }
            view.mainLightNode?.let { light ->
                val manager = view.engine.lightManager
                manager.setShadowCaster(manager.getInstance(light.entity), high)
            }
        }
        view.view.ambientOcclusionOptions =
            view.view.ambientOcclusionOptions.apply { enabled = high }
        view.onFrame = { time ->
            val dt = if (last == 0L) 0f else ((time - last) / 1e9f).coerceIn(0f, .1f)
            last = time
            val n = node
            if (n != null && n.animationCount > 0) {
                val a = n.animator
                val duration = a.getAnimationDuration(current).coerceAtLeast(.001f)
                if (playing) {
                    timeline += dt * speed
                    if (looping) timeline %= duration
                    else if (timeline >= duration) {
                        timeline = duration
                        playing = false
                    }
                }
                a.applyAnimation(current, timeline)
                if (previous >= 0 && transition < 1f) {
                    transition = (transition + dt / .3f).coerceAtMost(1f)
                    a.applyCrossFade(previous, previousTime, transition)
                }
                a.updateBoneMatrices()
            }
            frameCount++
            val now = SystemClock.elapsedRealtime()
            if (now - statsStart >= 2000) {
                val mem =
                    Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / 1048576 }
                onStats?.invoke("${frameCount*1000/(now-statsStart)} FPS · Java ${mem} MB")
                android.util.Log.i(
                    "HuanBanPerf",
                    "mode=${if(ar) "ar" else if(transparent) "overlay" else "stage"} fps=${frameCount*1000/(now-statsStart)} javaMB=$mem nativeMB=${android.os.Debug.getNativeHeapAllocatedSize()/1048576}",
                )
                frameCount = 0
                statsStart = now
            }
        }
    }

    fun start() {
        if (!disposed) registry.currentState = Lifecycle.State.RESUMED
    }

    fun pause() {
        if (!disposed) {
            registry.currentState = Lifecycle.State.CREATED
            last = 0L
        }
    }

    fun load(r: Role, onReady: () -> Unit = {}) {
        val ticket = ++generation
        role = r
        report("正在加载 ${r.name}…")
        executor.execute {
            if (disposed || ticket != generation) return@execute
            val start = SystemClock.elapsedRealtime()
            val result = runCatching {
                val bytes = RoleStore(context).file(r).readBytes()
                require(bytes.size <= ModelCodec.MAX_BYTES + 2 * 1024 * 1024)
                val direct = ByteBuffer.allocateDirect(bytes.size)
                direct.put(bytes).flip()
                direct
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (disposed || ticket != generation) return@post
                try {
                    val buffer = result.getOrThrow()
                    // Release the previous model before allocating GPU resources for the next one.
                    clearModel()
                    val model = view.modelLoader.createModelInstance(buffer)
                    node =
                        ModelNode(
                                model,
                                autoAnimate = false,
                                scaleToUnits = if (ar) .6f else 1.8f,
                                centerOrigin = Position(0f),
                            )
                            .also { n ->
                                n.isShadowCaster = true
                                n.isShadowReceiver = true
                                view.addChildNode(n)
                            }
                    zoom = r.scale
                    yaw = 0f
                    x = 0f
                    y = 0f
                    updateTransform()
                    current =
                        r.action.coerceIn(0, ((node?.animationCount ?: 0) - 1).coerceAtLeast(0))
                    previous = -1
                    timeline = 0f
                    last = 0L
                    report(
                        "${r.name} · ${SystemClock.elapsedRealtime()-start} ms${if(r.warning.isNotEmpty()) "\n${r.warning}" else ""}"
                    )
                    onReady()
                } catch (e: OutOfMemoryError) {
                    report("内存不足，已停止加载；请使用更小模型")
                    clearModel()
                } catch (e: Exception) {
                    android.util.Log.e("RenderHost", "模型加载失败", e)
                    report("加载失败：${e.message}")
                    clearModel()
                }
            }
        }
    }

    fun action(index: Int) {
        val n = node ?: return
        if (index !in 0 until n.animationCount) return
        previous = current
        previousTime = timeline
        current = index
        timeline = 0f
        transition = 0f
        playing = true
    }

    fun interact() {
        val r = role ?: return
        val i = r.animations.indexOfFirst { it.contains("挥手") }
        if (i >= 0) action(i)
    }

    fun expression(happy: Boolean) {
        val r = role ?: return
        val key = if (happy) "开心" else "眨眼"
        val i = r.animations.indexOfFirst { it.contains(key) }
        if (i >= 0) action(i) else report("此角色没有可用表情映射")
    }

    fun move(dx: Float, dy: Float) {
        x = (x + dx).coerceIn(-1.5f, 1.5f)
        y = (y + dy).coerceIn(-1.5f, 1.5f)
        updateTransform()
    }

    fun updateTransform() {
        node?.let { n ->
            val base = if (ar) .6f else 1.8f
            n.scaleToUnitCube(base * zoom.coerceIn(.1f, 3f))
            n.rotation = Rotation(0f, yaw, 0f)
            val c = n.center
            val angle = Math.toRadians(yaw.toDouble())
            val cx = c.x * n.scale.x
            val cz = c.z * n.scale.z
            n.position =
                Position(
                    x - (cx * kotlin.math.cos(angle) + cz * kotlin.math.sin(angle)).toFloat(),
                    y + (if (ar && n.parent != null) n.halfExtent.y * n.scale.y else 0f) -
                        c.y * n.scale.y,
                    (if (ar && n.parent == null) -1f else 0f) -
                        (-cx * kotlin.math.sin(angle) + cz * kotlin.math.cos(angle)).toFloat(),
                )
        }
    }

    private fun clearModel() {
        node?.let { n ->
            view.removeChildNode(n)
            val model = n.model
            n.destroy()
            view.modelLoader.destroyModel(model)
        }
        node = null
    }

    fun destroy() {
        if (disposed) return
        disposed = true
        generation++
        executor.shutdownNow()
        clearModel()
        floor?.let {
            view.removeChildNode(it)
            val model = it.model
            it.destroy()
            view.modelLoader.destroyModel(model)
        }
        floor = null
        registry.currentState = Lifecycle.State.DESTROYED
        view.destroy()
        RenderEngine.release()
    }
}
