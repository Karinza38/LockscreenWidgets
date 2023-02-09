package tk.zwander.lockscreenwidgets.views

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.lifecycle.setViewTreeLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import tk.zwander.common.util.Event
import tk.zwander.common.util.HandlerRegistry
import tk.zwander.common.util.PrefManager
import tk.zwander.common.util.eventManager
import tk.zwander.common.util.fadeAndScaleIn
import tk.zwander.common.util.fadeAndScaleOut
import tk.zwander.common.util.handler
import tk.zwander.common.util.logUtils
import tk.zwander.common.util.makeEven
import tk.zwander.common.util.prefManager
import tk.zwander.common.util.safeAddView
import tk.zwander.common.util.safeRemoveView
import tk.zwander.common.util.safeUpdateViewLayout
import tk.zwander.common.util.vibrate
import tk.zwander.lockscreenwidgets.compose.IDListLayout
import tk.zwander.lockscreenwidgets.databinding.WidgetFrameBinding
import tk.zwander.lockscreenwidgets.util.*
import kotlin.math.roundToInt

/**
 * The widget frame itself.
 *
 * This contains the widget pager, along with the editing interface. While most of
 * the logic relating to moving, resizing, etc, is handled by the Accessibility service,
 * this View listens for and notifies of the relevant events.
 */
class WidgetFrameView(context: Context, attrs: AttributeSet) : ConstraintLayout(context, attrs) {
    var animationState = AnimationState.STATE_IDLE

    private var maxPointerCount = 0
    private var alreadyIndicatedMoving = false
    private var isProxTooClose = false
        set(value) {
            field = value

            updateProximity(value)
        }

    private var isInEditingMode = false
        set(value) {
            field = value
            binding.editWrapper.isVisible = value
        }

    private val sensorManager by lazy { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    private val proximityListener = object : SensorEventListener2 {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onFlushCompleted(sensor: Sensor?) {}

        override fun onSensorChanged(event: SensorEvent) {
            val dist = event.values[0]

            isProxTooClose = dist < 5
        }
    }

    private val sharedPreferencesChangeHandler = HandlerRegistry {
        handler(PrefManager.KEY_TOUCH_PROTECTION) {
            if (isAttachedToWindow) {
                if (context.prefManager.touchProtection) {
                    registerProxListener()
                } else {
                    unregisterProxListener()
                }
            }
        }
        handler(PrefManager.KEY_LOCK_WIDGET_FRAME) {
            isInEditingMode = false
        }
    }

    private val binding by lazy { WidgetFrameBinding.bind(this) }

    private val debugIdItems = MutableStateFlow<Set<String>>(setOf())

    enum class AnimationState {
        STATE_ADDING,
        STATE_REMOVING,
        STATE_IDLE
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onFinishInflate() {
        super.onFinishInflate()

        binding.frameCard.alpha = 0f
        binding.frameCard.scaleX = 0.95f
        binding.frameCard.scaleY = 0.95f

        binding.move.setOnTouchListener(MoveTouchListener())
        binding.centerHorizontally.setOnClickListener {
            context.eventManager.sendEvent(Event.CenterFrameHorizontally)
        }
        binding.centerVertically.setOnClickListener {
            context.eventManager.sendEvent(Event.CenterFrameVertically)
        }

        binding.leftDragger.setOnTouchListener(ExpandTouchListener { velX, _, isUp ->
            context.eventManager.sendEvent(Event.FrameResized(Event.FrameResized.Side.LEFT, velX, isUp))
            true
        })
        binding.rightDragger.setOnTouchListener(ExpandTouchListener { velX, _, isUp ->
            context.eventManager.sendEvent(Event.FrameResized(Event.FrameResized.Side.RIGHT, velX, isUp))
            true
        })
        binding.topDragger.setOnTouchListener(ExpandTouchListener { _, velY, isUp ->
            context.eventManager.sendEvent(Event.FrameResized(Event.FrameResized.Side.TOP, velY, isUp))
            true
        })
        binding.bottomDragger.setOnTouchListener(ExpandTouchListener { _, velY, isUp ->
            context.eventManager.sendEvent(Event.FrameResized(Event.FrameResized.Side.BOTTOM, velY, isUp))
            true
        })
        binding.addWidget.setOnClickListener {
            context.eventManager.sendEvent(Event.LaunchAddWidget)
        }
        binding.tempHideFrame.setOnClickListener {
            context.eventManager.sendEvent(Event.TempHide)
        }

        if (context.prefManager.firstViewing) {
            binding.gestureHintView.root.isVisible = true
        }

        binding.idView.idList.setContent {
            val items by debugIdItems.collectAsState()

            IDListLayout(
                items = items
            )
        }

        updateDebugIdViewVisibility()
        updatePageIndicatorBehavior()
        updateFrameBackground()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        sharedPreferencesChangeHandler.register(context)
        if (context.prefManager.touchProtection) {
            registerProxListener()
        }

        postDelayed({
            binding.frameCard.fadeAndScaleIn {
                context.eventManager.sendEvent(Event.FrameAttachmentState(true))
                animationState = AnimationState.STATE_IDLE
            }
        }, 50)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        sharedPreferencesChangeHandler.unregister(context)
        unregisterProxListener()

        isInEditingMode = false
        context.eventManager.sendEvent(Event.FrameAttachmentState(false))
        animationState = AnimationState.STATE_IDLE
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!isProxTooClose) {
            onTouch(ev)

            maxPointerCount = ev.pointerCount.run {
                var properMax = this

                for (i in 0 until this) {
                    val coords = MotionEvent.PointerCoords().apply { ev.getPointerCoords(i, this) }

                    if (coords.x < 0 || coords.x > width || coords.y < 0 || coords.y > height) {
                        properMax--
                    }
                }

                if (properMax > maxPointerCount) properMax else maxPointerCount
            }

            when (ev.action) {
                MotionEvent.ACTION_UP -> {
                    val max = maxPointerCount
                    maxPointerCount = 0

                    when (max) {
                        2 -> {
                            if (!context.prefManager.lockWidgetFrame) {
                                isInEditingMode = !isInEditingMode
                                if (binding.gestureHintView.root.isVisible) {
                                    val ghv = binding.gestureHintView.root
                                    if (!ghv.stage2) {
                                        ghv.stage2 = true
                                    } else if (ghv.stage2) {
                                        ghv.stage2 = false
                                        ghv.close()
                                        binding.hideHintView.root.isVisible = true
                                    }
                                }
                                return true
                            }
                        }
                        3 -> {
                            if (binding.hideHintView.root.isVisible) {
                                binding.hideHintView.root.close()
                            }
                            context.eventManager.sendEvent(Event.TempHide)
                            return true
                        }
                    }

                    if (ev.buttonState == MotionEvent.BUTTON_SECONDARY
                        || ev.buttonState == MotionEvent.BUTTON_STYLUS_SECONDARY) {
                        isInEditingMode = !isInEditingMode
                        return true
                    }

                    if (ev.buttonState == MotionEvent.BUTTON_TERTIARY) {
                        context.eventManager.sendEvent(Event.TempHide)
                        return true
                    }
                }
            }
        }

        return super.dispatchTouchEvent(ev) && !isProxTooClose
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return (maxPointerCount > 1 || isProxTooClose)
    }

    fun updateFrameBackground() {
        binding.frameCard.setCardBackgroundColor(context.prefManager.backgroundColor)
    }

    fun updatePageIndicatorBehavior() {
        binding.widgetsPager.apply {
            when (context.prefManager.pageIndicatorBehavior) {
                PrefManager.VALUE_PAGE_INDICATOR_BEHAVIOR_HIDDEN -> {
                    isHorizontalScrollBarEnabled = false
                    isScrollbarFadingEnabled = false
                }
                PrefManager.VALUE_PAGE_INDICATOR_BEHAVIOR_AUTO_HIDE -> {
                    isHorizontalScrollBarEnabled = true
                    isScrollbarFadingEnabled = true
                    scrollBarFadeDuration = ViewConfiguration.getScrollBarFadeDuration()
                }
                PrefManager.VALUE_PAGE_INDICATOR_BEHAVIOR_SHOWN -> {
                    isHorizontalScrollBarEnabled = true
                    scrollBarFadeDuration = 0
                    isScrollbarFadingEnabled = false
                }
            }
        }
    }

    fun setNewDebugIdItems(items: List<String>) {
        debugIdItems.value = items.toSet()
    }

    fun updateDebugIdViewVisibility() {
        binding.idView.root.isVisible = context.prefManager.showDebugIdView
    }

    fun addWindow(wm: WindowManager, params: WindowManager.LayoutParams) {
        context.logUtils.debugLog("Trying to add overlay $animationState")

        if (!isAttachedToWindow && animationState != AnimationState.STATE_ADDING) {
            context.logUtils.debugLog("Adding overlay")

            animationState = AnimationState.STATE_ADDING

            if (!wm.safeAddView(this, params)) {
                animationState = AnimationState.STATE_IDLE
            }
        }
    }

    fun updateWindow(wm: WindowManager, params: WindowManager.LayoutParams) {
        if (isAttachedToWindow) {
            wm.safeUpdateViewLayout(this, params)
        }
    }

    fun removeWindow(wm: WindowManager) {
        context.logUtils.debugLog("Trying to remove overlay $animationState")

        if (isAttachedToWindow && animationState != AnimationState.STATE_REMOVING) {
            animationState = AnimationState.STATE_REMOVING

            context.logUtils.debugLog("Pre-animation removal")

            binding.frameCard.fadeAndScaleOut {
                context.logUtils.debugLog("Post-animation removal")

                postDelayed({
                    context.logUtils.debugLog("Posted removal")

                    wm.safeRemoveView(this)
                    animationState = AnimationState.STATE_IDLE
                }, 50)
            }
        } else if (!isAttachedToWindow) {
            wm.safeRemoveView(this)

            animationState = AnimationState.STATE_IDLE
        }
    }

    private fun updateProximity(tooClose: Boolean) {
        binding.touchProtectionView.root.isVisible = tooClose
    }

    private fun registerProxListener() {
        sensorManager.registerListener(
            proximityListener,
            sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY),
            1 * 200 * 1000 /* 200ms */
        )
    }

    private fun unregisterProxListener() {
        sensorManager.unregisterListener(proximityListener)
        isProxTooClose = false
    }

    private fun onTouch(event: MotionEvent): Boolean {
        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                context.eventManager.sendEvent(Event.FrameIntercept(true))
                false
            }
            MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_UP -> {
                context.eventManager.sendEvent(Event.FrameIntercept(false))
                alreadyIndicatedMoving = false
                false
            }
            else -> false
        }
    }

    inner class ExpandTouchListener(private val listener: ((velX: Int, velY: Int, isUp: Boolean) -> Boolean)?) : OnTouchListener {
        private var prevExpandX = 0f
        private var prevExpandY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    prevExpandX = event.rawX
                    prevExpandY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!alreadyIndicatedMoving) {
                        alreadyIndicatedMoving = true

                        context.vibrate()
                    }
                    val newX = event.rawX
                    val newY = event.rawY

                    val velX = try {
                        (newX - prevExpandX).roundToInt()
                    } catch (e: IllegalArgumentException) {
                        0
                    }
                    val velY = try {
                        (newY - prevExpandY).roundToInt()
                    } catch (e: IllegalArgumentException) {
                        0
                    }

                    prevExpandX = newX
                    prevExpandY = newY

                    //Make sure the velocities are even.
                    //This prevents an issue where the frame moves when resizing,
                    //since its position needs to be compensated by half the velocity.
                    //If the velocity is odd, the frame may over-compensate, causing it
                    //to push the opposite side the opposite direction by a pixel every time
                    //this is invoked.
                    listener?.invoke(velX.makeEven(), velY.makeEven(), false) ?: false
                }
                MotionEvent.ACTION_UP -> {
                    listener?.invoke(0, 0, true)
                    false
                }
                else -> false
            }
        }
    }

    inner class MoveTouchListener : OnTouchListener {
        private var prevX = 0f
        private var prevY = 0f

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            return when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    prevX = event.rawX
                    prevY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!alreadyIndicatedMoving) {
                        alreadyIndicatedMoving = true

                        context.vibrate()
                    }
                    val newX = event.rawX
                    val newY = event.rawY

                    val velX = newX - prevX
                    val velY = newY - prevY

                    prevX = newX
                    prevY = newY

                    context.eventManager.sendEvent(Event.FrameMoved(velX, velY))
                    true
                }
                MotionEvent.ACTION_UP -> {
                    context.eventManager.sendEvent(Event.FrameMoveFinished)
                    true
                }
                else -> false
            }
        }
    }
}