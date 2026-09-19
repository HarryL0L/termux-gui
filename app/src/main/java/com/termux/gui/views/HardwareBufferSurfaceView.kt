package com.termux.gui.views

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLObjectHandle
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.termux.gui.hbuffers.HBuffers
import com.termux.gui.protocol.shared.v0.RawInputConnection
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * A SurfaceView that can draw a shared HardwareBuffer to the Surface.
 */
class HardwareBufferSurfaceView(c: Context) : SurfaceView(c), Choreographer.FrameCallback {

    /**
     * Configuration of the View.
     */
    class Config {
        /**
         * Behaviour on buffer dimension mismatch with the Surface.
         */
        enum class OnDimensionMismatch {
            /**
             * Center the buffer on the axis.
             */
            CENTER_AXIS,

            /**
             * Attach the buffer at the top/left side of the Surface.
             */
            STICK_TOPLEFT
        }
        var x: OnDimensionMismatch = OnDimensionMismatch.CENTER_AXIS
        var y: OnDimensionMismatch = OnDimensionMismatch.CENTER_AXIS

        /**
         * Color usedin case the buffer is too small.
         */
        var backgroundColor: Int = 0xff000000.toInt()
    }
    
    
    interface SurfaceChangedListener {
        fun onSurfaceChanged(width: Int, height: Int)
    }

    interface FrameCallbackListener {
        fun onSurfaceFrame(timestamp: Long)
    }

    /** Receives pointer events from trackpad mode, coordinates in buffer pixels. */
    interface PointerListener {
        enum class Action { MOVE, BUTTON_DOWN, BUTTON_UP, SCROLL }
        enum class Button { NONE, LEFT, RIGHT, MIDDLE }
        fun onPointer(action: Action, x: Int, y: Int, button: Button, scrollX: Float, scrollY: Float)
    }

    /** Trackpad mode settings. */
    class Trackpad {
        var enabled = false
        var sensitivity = 1f
        var scrollSensitivity = 1f
    }

    /** Cursor bitmap with hotspot. */
    class CursorImage(val bitmap: Bitmap, val hotspotX: Int, val hotspotY: Int)

    /**
     * EGLImageKHR wrapper.
     */
    @Suppress("EqualsOrHashCode")
    class EGLImageKHR(handle: Long) : EGLObjectHandle(handle) {
        companion object {
            private external fun nativeEglDestroyImageKHR(disp: Long, img: Long): Boolean
            private external fun nativeEGLImageTargetTexture2DOES(img: Long)
            
            
             fun eglImageTargetTexture2DOES(img: EGLImageKHR) {
                 nativeEGLImageTargetTexture2DOES(img.nativeHandle)
             }
            
            fun eglDestroyImageKHR(d: EGLDisplay, img: EGLImageKHR): Boolean {
                return nativeEglDestroyImageKHR(d.nativeHandle, img.nativeHandle)
            }

            
            
            val EGL_NO_IMAGE_KHR: EGLImageKHR = EGLImageKHR(0)
        }

        override fun equals(other: Any?): Boolean {
            if (other is EGLImageKHR && other.nativeHandle == nativeHandle) {
                return true
            }
            return false
        }

    }
    
    
    companion object {

        /** Default arrow cursor. */
        fun defaultCursor(): CursorImage {
            val size = 24
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val path = Path()
            path.moveTo(1f, 1f)
            path.lineTo(1f, 17f)
            path.lineTo(5f, 13.5f)
            path.lineTo(8f, 20f)
            path.lineTo(11f, 18.5f)
            path.lineTo(8f, 12f)
            path.lineTo(13.5f, 12f)
            path.close()
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            fill.style = Paint.Style.FILL
            fill.color = Color.BLACK
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 1.5f
            stroke.color = Color.WHITE
            c.drawPath(path, fill)
            c.drawPath(path, stroke)
            return CursorImage(bmp, 1, 1)
        }

        private var vertexCode: String = ""
        private var fragmentCode: String = ""
        private var cursorFragmentCode: String = ""
        
        private fun checkEGLError(msg: String): Boolean {
            val err = EGL14.eglGetError()
            if (err != EGL14.EGL_SUCCESS) {
                println("%s: %x".format(msg, err))
                return true
            }
            return false
        }
        private fun logGLESError(msg: String) {
            val err = GLES20.glGetError()
            if (err != GLES20.GL_NO_ERROR) {
                println("%s: %x".format(msg, err))
            }
        }
        
    }

    /**
     * Lock for modifying the View, so the main Thread and the connection Thread can draw.
     */
    val RENDER_LOCK = Object()
    
    private var keyListener: OnKeyListener? = null
    var surfaceChangedListener: SurfaceChangedListener? = null
    var frameCallback: FrameCallbackListener? = null
    var config: Config = Config()
    
    private var buffer: HardwareBuffer? = null
    private var bufferImage: EGLImageKHR = EGLImageKHR.EGL_NO_IMAGE_KHR
    private var disp = EGL14.EGL_NO_DISPLAY
    private var eglConfig: EGLConfig? = null
    private var gl: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surface: Surface? = null
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var surfaceChanged = false
    private val posBuffer = ByteBuffer.allocateDirect(8*4)
    private var posI = -1
    private val tposBuffer = ByteBuffer.allocateDirect(8*4)
    private var tposI = -1
    private var prog = -1

    private var cursorProg = -1
    private var cursorPosI = -1
    private var cursorTposI = -1
    private var cursorTex = -1
    private var cursorTexDirty = true
    private val cursorPosBuffer = ByteBuffer.allocateDirect(8*4)
    private val cursorTposBuffer = ByteBuffer.allocateDirect(8*4)

    var pointerListener: PointerListener? = null
    val trackpad = Trackpad()
    @Volatile var cursorVisible = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorImage: CursorImage = defaultCursor()

    /** Null resets to the default arrow. */
    fun setCursorImage(img: CursorImage?) {
        synchronized(RENDER_LOCK) {
            cursorImage = img ?: defaultCursor()
            cursorTexDirty = true
            render()
        }
    }

    fun setCursorPosition(bx: Int, by: Int) {
        synchronized(RENDER_LOCK) {
            val s = bufferToSurface(bx.toFloat(), by.toFloat())
            cursorX = s[0]
            cursorY = s[1]
            clampCursor()
            render()
        }
    }

    fun getCursorPosition(): IntArray {
        synchronized(RENDER_LOCK) {
            val b = surfaceToBuffer(cursorX, cursorY)
            return intArrayOf(b[0].toInt(), b[1].toInt())
        }
    }

    fun requestRender() {
        synchronized(RENDER_LOCK) {
            render()
        }
    }

    private fun clampCursor() {
        if (surfaceWidth > 0) cursorX = cursorX.coerceIn(0f, (surfaceWidth - 1).toFloat())
        if (surfaceHeight > 0) cursorY = cursorY.coerceIn(0f, (surfaceHeight - 1).toFloat())
    }

    /** Buffer pixel = Surface pixel + offset. */
    private fun bufferOffset(): FloatArray {
        val b = buffer
        var ox = 0f
        var oy = 0f
        if (b != null) {
            if (config.x == Config.OnDimensionMismatch.CENTER_AXIS && b.width != surfaceWidth) {
                ox = (b.width - surfaceWidth).toFloat() / 2f
            }
            if (config.y == Config.OnDimensionMismatch.CENTER_AXIS && b.height != surfaceHeight) {
                oy = (b.height - surfaceHeight).toFloat() / 2f
            }
        }
        return floatArrayOf(ox, oy)
    }

    private fun surfaceToBuffer(x: Float, y: Float): FloatArray {
        val o = bufferOffset()
        return floatArrayOf(x + o[0], y + o[1])
    }

    private fun bufferToSurface(x: Float, y: Float): FloatArray {
        val o = bufferOffset()
        return floatArrayOf(x - o[0], y - o[1])
    }

    private fun emitPointer(action: PointerListener.Action, button: PointerListener.Button = PointerListener.Button.NONE, sx: Float = 0f, sy: Float = 0f) {
        val l = pointerListener ?: return
        val b = surfaceToBuffer(cursorX, cursorY)
        l.onPointer(action, b[0].toInt(), b[1].toInt(), button, sx, sy)
    }

    /** Trackpad gestures: drag moves, tap clicks (1/2/3 fingers = left/right/middle), two finger drag scrolls, double tap and drag holds the left button. */

    private val touchSlop = ViewConfiguration.get(c).scaledTouchSlop.toFloat()
    private val tapTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
    private val doubleTapSlop = ViewConfiguration.get(c).scaledDoubleTapSlop.toFloat()

    private var gestureLastX = 0f
    private var gestureLastY = 0f
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureMoved = false
    private var gestureMaxPointers = 0
    private var gestureDownTime = 0L
    private var gestureDragging = false
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var scrollAccX = 0f
    private var scrollAccY = 0f

    private fun focusOf(e: MotionEvent): FloatArray {
        var x = 0f
        var y = 0f
        var n = 0
        val up = e.actionMasked == MotionEvent.ACTION_POINTER_UP
        for (i in 0 until e.pointerCount) {
            if (up && i == e.actionIndex) continue
            x += e.getX(i)
            y += e.getY(i)
            n++
        }
        if (n == 0) return floatArrayOf(e.x, e.y)
        return floatArrayOf(x / n, y / n)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!trackpad.enabled) return super.onTouchEvent(event)
        synchronized(RENDER_LOCK) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val f = focusOf(event)
                    gestureLastX = f[0]
                    gestureLastY = f[1]
                    gestureDownX = f[0]
                    gestureDownY = f[1]
                    gestureMoved = false
                    gestureMaxPointers = 1
                    gestureDownTime = event.eventTime
                    scrollAccX = 0f
                    scrollAccY = 0f
                    val isDoubleTap = lastTapTime != 0L
                            && event.eventTime - lastTapTime < doubleTapTimeout
                            && Math.abs(f[0] - lastTapX) < doubleTapSlop
                            && Math.abs(f[1] - lastTapY) < doubleTapSlop
                    lastTapTime = 0L
                    gestureDragging = isDoubleTap
                    if (isDoubleTap) emitPointer(PointerListener.Action.BUTTON_DOWN, PointerListener.Button.LEFT)
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    val f = focusOf(event)
                    gestureLastX = f[0]
                    gestureLastY = f[1]
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                        gestureMaxPointers = maxOf(gestureMaxPointers, event.pointerCount)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val f = focusOf(event)
                    val dx = f[0] - gestureLastX
                    val dy = f[1] - gestureLastY
                    if (!gestureMoved && (Math.abs(f[0] - gestureDownX) > touchSlop || Math.abs(f[1] - gestureDownY) > touchSlop)) {
                        gestureMoved = true
                    }
                    if (gestureMoved) {
                        gestureLastX = f[0]
                        gestureLastY = f[1]
                        if (event.pointerCount >= 2 && !gestureDragging) {
                            val notch = 40f * resources.displayMetrics.density / trackpad.scrollSensitivity
                            scrollAccX += dx
                            scrollAccY += dy
                            val sx = (scrollAccX / notch).toInt()
                            val sy = (scrollAccY / notch).toInt()
                            if (sx != 0 || sy != 0) {
                                scrollAccX -= sx * notch
                                scrollAccY -= sy * notch
                                emitPointer(PointerListener.Action.SCROLL, PointerListener.Button.NONE, -sx.toFloat(), -sy.toFloat())
                            }
                        } else {
                            cursorX += dx * trackpad.sensitivity
                            cursorY += dy * trackpad.sensitivity
                            clampCursor()
                            emitPointer(PointerListener.Action.MOVE)
                            render()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (gestureDragging) {
                        gestureDragging = false
                        emitPointer(PointerListener.Action.BUTTON_UP, PointerListener.Button.LEFT)
                    } else if (event.actionMasked == MotionEvent.ACTION_UP && !gestureMoved
                            && event.eventTime - gestureDownTime < tapTimeout) {
                        val button = when (gestureMaxPointers) {
                            1 -> PointerListener.Button.LEFT
                            2 -> PointerListener.Button.RIGHT
                            else -> PointerListener.Button.MIDDLE
                        }
                        emitPointer(PointerListener.Action.BUTTON_DOWN, button)
                        emitPointer(PointerListener.Action.BUTTON_UP, button)
                        if (button == PointerListener.Button.LEFT) {
                            lastTapTime = event.eventTime
                            lastTapX = gestureDownX
                            lastTapY = gestureDownY
                        }
                    }
                }
            }
        }
        return true
    }
    
    
    
    fun setBuffer(b: HardwareBuffer) {
        synchronized(RENDER_LOCK) {
            buffer = b
            if (disp != EGL14.EGL_NO_DISPLAY) {
                if (bufferImage != EGLImageKHR.EGL_NO_IMAGE_KHR) {
                    EGLImageKHR.eglDestroyImageKHR(disp, bufferImage)
                    bufferImage = EGLImageKHR.EGL_NO_IMAGE_KHR
                }
                render()
            }
        }
    }
    
    fun setFrameRate(rate: Float): Boolean {
        val s = surface
        synchronized(RENDER_LOCK) {
            if (s != null && rate > 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    s.setFrameRate(rate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
                return true
            } else {
                return false
            }
        }
    }
    
    
    fun getBuffer(): HardwareBuffer? {
        return buffer
    }
    
    
    
    private fun render() {
        synchronized(RENDER_LOCK) {
            initEGL()
            if (buffer != null && surface != null) {
                if (eglSurface == EGL14.EGL_NO_SURFACE) {
                    eglSurface = EGL14.eglCreateWindowSurface(disp, eglConfig, surface, null, 0)
                    if (eglSurface == EGL14.EGL_NO_SURFACE) {
                        val err = EGL14.eglGetError()
                        deinitEGL()
                        throw RuntimeException("Could create EGLSurface: 0x%x".format(err))
                    }
                }
                
                initGLES()
                if (!EGL14.eglMakeCurrent(disp, eglSurface, eglSurface, gl)) {
                    val err = EGL14.eglGetError()
                    if (err == EGL14.EGL_CONTEXT_LOST) {
                        EGL14.eglDestroyContext(disp, gl)
                        gl = EGL14.EGL_NO_CONTEXT
                        prog = -1
                        posI = -1
                        cursorProg = -1
                        cursorTex = -1
                        initEGL()
                        initGLES()
                    } else {
                        throw RuntimeException("Could not make GLES2 context current: 0x%x".format(err))
                    }
                }
                if (surfaceChanged) {
                    surfaceChanged = false
                    GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
                    logGLESError("viewport")
                }
                
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) throw RuntimeException("HardwareBufferSurfaceView used on Android < 8.0")
                val bWidth = buffer!!.width
                val bHeight = buffer!!.height

                val vertexArray = floatArrayOf(
                    -1f, -1f, // bottom left vertex
                    -1f, 1f, // top left vertex
                    1f, -1f, // bottom right vertex
                    1f, 1f // top right vertex
                )
                val textureArray = floatArrayOf(
                    0f, 0f, // bottom left vertex
                    0f, 1f, // top left vertex
                    1f, 0f, // bottom right vertex
                    1f, 1f // top right vertex
                )
                if (bWidth != surfaceWidth) {
                    when (config.x) {
                        Config.OnDimensionMismatch.CENTER_AXIS -> {
                            val marginPX = (bWidth.toFloat() - surfaceWidth.toFloat())/2f
                            val marginNDC = marginPX * (2f / surfaceWidth.toFloat())
                            vertexArray[0] -= marginNDC
                            vertexArray[2] -= marginNDC
                            vertexArray[4] += marginNDC
                            vertexArray[6] += marginNDC
                        }
                        Config.OnDimensionMismatch.STICK_TOPLEFT -> {
                            val ratio = bWidth.toFloat() / surfaceWidth.toFloat()
                            if (ratio < 1f) {
                                // scale the presentation rectangle down to fit the buffer
                                vertexArray[4] *= ratio
                                vertexArray[6] *= ratio
                            }
                            if (ratio > 1f) {
                                // scale the texture coordinates down to show a  slice of the buffer
                                textureArray[4] = 1f / ratio
                                textureArray[6] = 1f / ratio
                            }
                        }
                    }
                }
                if (bHeight != surfaceHeight) {
                    when (config.y) {
                        Config.OnDimensionMismatch.CENTER_AXIS -> {
                            val marginPX = (bHeight.toFloat() - surfaceHeight.toFloat())/2f
                            val marginNDC = marginPX * (2f / surfaceHeight.toFloat())
                            vertexArray[1] -= marginNDC
                            vertexArray[3] += marginNDC
                            vertexArray[5] -= marginNDC
                            vertexArray[7] += marginNDC
                        }
                        Config.OnDimensionMismatch.STICK_TOPLEFT -> {
                            val ratio = bHeight.toFloat() / surfaceHeight.toFloat()
                            if (ratio < 1f) {
                                // scale the presentation rectangle down to fit the buffer
                                vertexArray[1] *= ratio
                                vertexArray[5] *= ratio
                            }
                            if (ratio > 1f) {
                                // scale the texture coordinates down to show a  slice of the buffer
                                textureArray[1] = 1f - 1f / ratio
                                textureArray[5] = 1f - 1f / ratio
                            }
                        }
                    }
                }
                
                
                val fPos = posBuffer.asFloatBuffer()
                val fTex = tposBuffer.asFloatBuffer()
                for (i in vertexArray.indices) {
                    fPos.put(vertexArray[i])
                    fTex.put(textureArray[i])
                }
                
                GLES20.glVertexAttribPointer(posI, 2, GLES20.GL_FLOAT, false, 0, posBuffer)
                logGLESError("vertexAttribPointer")
                GLES20.glVertexAttribPointer(tposI, 2, GLES20.GL_FLOAT, false, 0, tposBuffer)
                logGLESError("vertexAttribPointer")
                
                GLES20.glClearColor(
                    (config.backgroundColor and 0xff).toFloat()/255f,
                    ((config.backgroundColor ushr 8) and 0xff).toFloat()/255f,
                    ((config.backgroundColor ushr 16) and 0xff).toFloat()/255f,
                    ((config.backgroundColor ushr 24) and 0xff).toFloat()/255f)
                logGLESError("glClearColor")
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                logGLESError("glClear")
                
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                logGLESError("drawArrays")

                drawCursor()
                
                EGL14.eglSwapBuffers(disp, eglSurface)
                checkEGLError("swap buffers")
                
                EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            }
        }
    }
    
    /** Draws the cursor on top of the buffer with the GLES context current. */
    private fun drawCursor() {
        if (!cursorVisible) return
        if (cursorProg == -1 || surfaceWidth == 0 || surfaceHeight == 0) return
        val img = cursorImage
        GLES20.glUseProgram(cursorProg)
        logGLESError("use cursor program")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        if (cursorTex == -1) {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            cursorTex = t[0]
            cursorTexDirty = true
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cursorTex)
        if (cursorTexDirty) {
            cursorTexDirty = false
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, img.bitmap, 0)
            logGLESError("cursor texImage2D")
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        GLES20.glUniform1i(GLES20.glGetUniformLocation(cursorProg, "cursorSampler"), 2)

        val scale = resources.displayMetrics.density
        val w = img.bitmap.width * scale
        val h = img.bitmap.height * scale
        val left = cursorX - img.hotspotX * scale
        val top = cursorY - img.hotspotY * scale
        val ndcL = left / surfaceWidth * 2f - 1f
        val ndcR = (left + w) / surfaceWidth * 2f - 1f
        val ndcT = 1f - top / surfaceHeight * 2f
        val ndcB = 1f - (top + h) / surfaceHeight * 2f

        val fPos = cursorPosBuffer.asFloatBuffer()
        fPos.put(floatArrayOf(ndcL, ndcB, ndcL, ndcT, ndcR, ndcB, ndcR, ndcT))
        val fTex = cursorTposBuffer.asFloatBuffer()
        fTex.put(floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f))

        GLES20.glEnableVertexAttribArray(cursorPosI)
        GLES20.glEnableVertexAttribArray(cursorTposI)
        GLES20.glVertexAttribPointer(cursorPosI, 2, GLES20.GL_FLOAT, false, 0, cursorPosBuffer)
        GLES20.glVertexAttribPointer(cursorTposI, 2, GLES20.GL_FLOAT, false, 0, cursorTposBuffer)

        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        logGLESError("draw cursor")
        GLES20.glDisable(GLES20.GL_BLEND)

        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
    }

    private fun compileProgram(vert: String, frag: String): Int {
        val f = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(f, frag)
        GLES20.glCompileShader(f)
        val v = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(v, vert)
        GLES20.glCompileShader(v)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val status = intArrayOf(0)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            println("GLES error linking program: " + GLES20.glGetProgramInfoLog(p))
            println(GLES20.glGetShaderInfoLog(v))
            println(GLES20.glGetShaderInfoLog(f))
            GLES20.glDeleteProgram(p)
            return -1
        }
        return p
    }
    
    private fun createBufferImage() {
        if (bufferImage == EGLImageKHR.EGL_NO_IMAGE_KHR && buffer != null && disp != EGL14.EGL_NO_DISPLAY) {
            bufferImage = EGLImageKHR(HBuffers.hardwareBufferToEGLImageKHR(disp, buffer!!))
            if (bufferImage == EGLImageKHR.EGL_NO_IMAGE_KHR) {
                deinitEGL()
                throw RuntimeException("Could create EGLImageKHR: "+EGL14.eglGetError())
            }
        }
    }
    
    private fun initGLES() {
        if (eglConfig == null)
            initEGL()
        if (gl == EGL14.EGL_NO_CONTEXT) {
            if (!EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
                val err = EGL14.eglGetError()
                deinitEGL()
                throw RuntimeException("Could not bind GLES: 0x%x".format(err))
            }
            gl = EGL14.eglCreateContext(
                disp, eglConfig, EGL14.EGL_NO_CONTEXT, intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
                ), 0
            )
            if (gl == EGL14.EGL_NO_CONTEXT) {
                val err = EGL14.eglGetError()
                deinitEGL()
                throw RuntimeException("Could not create GLES2 context: 0x%x".format(err))
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                if (!EGL14.eglMakeCurrent(disp, eglSurface, eglSurface, gl)) {
                    val err = EGL14.eglGetError()
                    deinitEGL()
                    throw RuntimeException("Could not make GLES2 context current: 0x%x".format(err))
                }
            }
        }
        if (eglSurface != EGL14.EGL_NO_SURFACE) {
            if (!EGL14.eglMakeCurrent(disp, eglSurface, eglSurface, gl)) {
                val err = EGL14.eglGetError()
                deinitEGL()
                throw RuntimeException("Could not make GLES2 context current: 0x%x".format(err))
            }
            val exts = GLES20.glGetString(GLES20.GL_EXTENSIONS)
            logGLESError("get gles2 extensions")
            if (exts == null) {
                deinitEGL()
                throw RuntimeException("Could not query GLES2 extensions")
            }
            if (!exts.contains("GL_OES_EGL_image_external")) {
                deinitEGL()
                throw RuntimeException("GLES2 doesn't have the GL_OES_EGL_image_external extension")
            }
            if (buffer != null && bufferImage == EGLImageKHR.EGL_NO_IMAGE_KHR) {
                createBufferImage()
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE1)
                logGLESError("bind external texture")
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                logGLESError("glActiveTexture")
                EGLImageKHR.eglImageTargetTexture2DOES(bufferImage)
                checkEGLError("eglImageTargetTexture2DOES")
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_NEAREST
                )
                logGLESError("set min filter")
                GLES20.glTexParameteri(
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_NEAREST
                )
                logGLESError("set mag filter")
            }
            if (prog == -1) {
                val frag = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
                logGLESError("create shader")
                GLES20.glShaderSource(frag, fragmentCode)
                logGLESError("shader source")
                GLES20.glCompileShader(frag)
                logGLESError("compile shader")
                val vert = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
                logGLESError("create shader")
                GLES20.glShaderSource(vert, vertexCode)
                logGLESError("shader source")
                GLES20.glCompileShader(vert)
                logGLESError("compile shader")
                prog = GLES20.glCreateProgram()
                logGLESError("create program")
                GLES20.glAttachShader(prog, vert)
                logGLESError("attach vertex shader")
                GLES20.glAttachShader(prog, frag)
                logGLESError("attach fragment shader shader")
                GLES20.glLinkProgram(prog)
                val err: Int = GLES20.glGetError()
                val status = intArrayOf(0)
                GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, status, 0)
                if (err != GLES20.GL_NO_ERROR || status[0] != GLES20.GL_TRUE) {
                    println("GLES error link program: $err")
                    println(GLES20.glGetProgramInfoLog(prog))
                    println(GLES20.glGetShaderInfoLog(vert))
                    println(GLES20.glGetShaderInfoLog(frag))
                }
                GLES20.glUseProgram(prog)
                logGLESError("use program")
            }
            if (prog != -1 && bufferImage != EGLImageKHR.EGL_NO_IMAGE_KHR) {
                val posS = GLES20.glGetUniformLocation(prog, "hbSampler")
                logGLESError("glGetUniformLocation")
                GLES20.glUniform1i(posS, 1)
            }
            if (posI == -1) {
                posI = GLES20.glGetAttribLocation(prog, "pos")
                logGLESError("get attrib location")
                GLES20.glEnableVertexAttribArray(posI)
                logGLESError("enableVertexAttribArray")
            }
            if (tposI == -1) {
                tposI = GLES20.glGetAttribLocation(prog, "tpos")
                logGLESError("get attrib location")
                GLES20.glEnableVertexAttribArray(tposI)
                logGLESError("enableVertexAttribArray")
                GLES20.glVertexAttribPointer(tposI, 2, GLES20.GL_FLOAT, false, 0, tposBuffer)
                logGLESError("vertexAttribPointer")
            }
            if (cursorProg == -1) {
                cursorProg = compileProgram(vertexCode, cursorFragmentCode)
                if (cursorProg != -1) {
                    cursorPosI = GLES20.glGetAttribLocation(cursorProg, "pos")
                    cursorTposI = GLES20.glGetAttribLocation(cursorProg, "tpos")
                }
                cursorTex = -1
                cursorTexDirty = true
                GLES20.glUseProgram(prog)
            }
            EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        }
    }
    
    
    
    private val callback = object: SurfaceHolder.Callback, SurfaceHolder.Callback2 {
        override fun surfaceCreated(holder: SurfaceHolder) {
            synchronized(RENDER_LOCK) {
                surface = holder.surface
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(disp, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
            }
        }
        
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            synchronized(RENDER_LOCK) {
                surfaceChangedListener?.onSurfaceChanged(width, height)
                val first = surfaceWidth == 0 && surfaceHeight == 0
                surfaceWidth = width
                surfaceHeight = height
                surfaceChanged = true
                if (first) {
                    cursorX = width / 2f
                    cursorY = height / 2f
                }
                clampCursor()
                render()
            }
        }
        
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            synchronized(RENDER_LOCK) {
                surface = null
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(disp, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
            }
        }
        
        override fun surfaceRedrawNeeded(holder: SurfaceHolder) {
            render()
        }

    }
    
    override fun onAttachedToWindow() {
        synchronized(RENDER_LOCK) {
            initEGL()
        }
        super.onAttachedToWindow()
        Choreographer.getInstance().postFrameCallback(this)
    }
    
    override fun onDetachedFromWindow() {
        synchronized(RENDER_LOCK) {
            deinitEGL()
        }
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
    }
    
    fun finalize() {
        deinitEGL()
    }
    
    
    private fun deinitEGL() {
        if (disp != EGL14.EGL_NO_DISPLAY) {
            if (gl != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(disp, gl)
                gl = EGL14.EGL_NO_CONTEXT
                EGL14.eglMakeCurrent(disp, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                posI = -1
                prog = -1
                cursorProg = -1
                cursorTex = -1
            }
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(disp, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (bufferImage != EGLImageKHR.EGL_NO_IMAGE_KHR) {
                EGLImageKHR.eglDestroyImageKHR(disp, bufferImage)
                bufferImage = EGLImageKHR.EGL_NO_IMAGE_KHR
            }
            
            EGL14.eglTerminate(disp)
            disp = EGL14.EGL_NO_DISPLAY
        }
    }
    
    private fun initEGL() {
        if (disp == EGL14.EGL_NO_DISPLAY) {
            disp = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (disp == EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("Could not get EGL display: 0x%x".format(EGL14.eglGetError()))
            }
            // Android bug: minor version is only updated when using 2 separate arrays with offset 0
            val major = IntArray(1)
            val minor = IntArray(1)
            if (!EGL14.eglInitialize(disp, major, 0, minor, 0)) {
                throw RuntimeException("Could not initialize EGL display: 0x%x".format(EGL14.eglGetError()))
            }
            if (major[0] != 1 || minor[0] < 2) {
                deinitEGL()
                throw RuntimeException("EGL version less than 1.2: " + major[0] + "." + minor[0])
            }
            val eglExts = EGL14.eglQueryString(disp, EGL14.EGL_EXTENSIONS)
            if (!eglExts.contains("EGL_KHR_image_base")) {
                deinitEGL()
                throw RuntimeException("EGL extension EGL_KHR_image_base not found")
            }
            if (!eglExts.contains("EGL_ANDROID_image_native_buffer")) {
                deinitEGL()
                throw RuntimeException("EGL extension EGL_ANDROID_image_native_buffer not found")
            }
            if (!eglExts.contains("EGL_ANDROID_get_native_client_buffer")) {
                deinitEGL()
                throw RuntimeException("EGL extension EGL_ANDROID_get_native_client_buffer not found")
            }
            val configs: Array<EGLConfig?> = Array(1) { null }
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(disp, intArrayOf(
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_COLOR_BUFFER_TYPE, EGL14.EGL_RGB_BUFFER,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            ), 0, configs, 0, 1, numConfigs, 0)) {
                deinitEGL()
                throw RuntimeException("Could not get EGL configs: 0x%x".format(EGL14.eglGetError()))
            }
            if (numConfigs[0] == 0) {
                deinitEGL()
                throw RuntimeException("No appropriate EGL configuration")
            }
            eglConfig = configs[0]
        }
        if (! EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)) {
            checkEGLError("eglBindAPI")
        }
        initGLES()
    }
    
    
    init {
        posBuffer.order(ByteOrder.nativeOrder())
        tposBuffer.order(ByteOrder.nativeOrder())
        cursorPosBuffer.order(ByteOrder.nativeOrder())
        cursorTposBuffer.order(ByteOrder.nativeOrder())
        synchronized(this.javaClass) {
            if (vertexCode == "") {
                val assets = context.assets
                vertexCode = assets.open("SurfaceShader.vert").use {
                    it.bufferedReader(Charset.defaultCharset()).readText()
                }
                fragmentCode = assets.open("SurfaceShader.frag").use {
                    it.bufferedReader(Charset.defaultCharset()).readText()
                }
                cursorFragmentCode = assets.open("CursorShader.frag").use {
                    it.bufferedReader(Charset.defaultCharset()).readText()
                }
            }
        }
        
        holder.setFormat(PixelFormat.RGBA_8888)
        setZOrderOnTop(true)
        
        initEGL()
        holder.addCallback(callback)
        
    }
    
    
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        val l = keyListener
        return if (l != null) {
            outAttrs.inputType = EditorInfo.TYPE_NULL
            RawInputConnection(l)
        } else {
            super.onCreateInputConnection(outAttrs)
        }
    }
    
    override fun setOnKeyListener(l: OnKeyListener?) {
        keyListener = l
        super.setOnKeyListener(l)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameCallback?.onSurfaceFrame(frameTimeNanos)
        if (isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
    }

}
