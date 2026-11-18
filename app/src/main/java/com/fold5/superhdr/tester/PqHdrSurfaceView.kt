package com.fold5.superhdr.tester

import android.content.Context
import android.graphics.Bitmap
import android.hardware.DataSpace
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.AttributeSet
import android.view.SurfaceControl
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import javax.microedition.khronos.opengles.GL10

class PqHdrSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs) {

    private val renderer: PqRenderer

    init {
        setEGLContextClientVersion(3)

        setEGLConfigChooser { egl, display ->
            val attrs10Bit = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 0x0004,
                EGL10.EGL_RED_SIZE, 10,
                EGL10.EGL_GREEN_SIZE, 10,
                EGL10.EGL_BLUE_SIZE, 10,
                EGL10.EGL_ALPHA_SIZE, 2,
                EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (egl.eglChooseConfig(display, attrs10Bit, configs, 1, numConfig) && numConfig[0] > 0 && configs[0] != null) {
                configs[0]!!
            } else {
                val attrs8Bit = intArrayOf(
                    EGL10.EGL_RENDERABLE_TYPE, 0x0004,
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_ALPHA_SIZE, 8,
                    EGL10.EGL_NONE
                )
                egl.eglChooseConfig(display, attrs8Bit, configs, 1, numConfig)
                configs[0]!!
            }
        }

        setEGLWindowSurfaceFactory(object : EGLWindowSurfaceFactory {
            override fun createWindowSurface(
                egl: EGL10,
                display: EGLDisplay,
                config: EGLConfig,
                nativeWindow: Any
            ): EGLSurface {
                val pqAttribs = intArrayOf(
                    0x309D,
                    0x3340,
                    EGL10.EGL_NONE
                )
                return try {
                    val surface = egl.eglCreateWindowSurface(display, config, nativeWindow, pqAttribs)
                    if (surface != null && surface != EGL10.EGL_NO_SURFACE) {
                        surface
                    } else {
                        egl.eglCreateWindowSurface(display, config, nativeWindow, null)
                    }
                } catch (_: Throwable) {
                    egl.eglCreateWindowSurface(display, config, nativeWindow, null)
                }
            }

            override fun destroySurface(egl: EGL10, display: EGLDisplay, surface: EGLSurface) {
                egl.eglDestroySurface(display, surface)
            }
        })

        renderer = PqRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        super.surfaceCreated(holder)
        applyHardwareHdrSurfaceTransaction()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        super.surfaceChanged(holder, format, w, h)
        applyHardwareHdrSurfaceTransaction()
    }

    private fun applyHardwareHdrSurfaceTransaction() {
        try {
            val sc = surfaceControl
            if (sc != null && sc.isValid) {
                val tx = SurfaceControl.Transaction()
                tx.setDataSpace(sc, DataSpace.DATASPACE_BT2020_PQ)
                try {
                    val method = SurfaceControl.Transaction::class.java.getMethod(
                        "setDesiredHdrHeadroom",
                        SurfaceControl::class.java,
                        Float::class.javaPrimitiveType
                    )
                    method.invoke(tx, sc, renderer.peakBoost)
                } catch (_: Throwable) {
                }
                tx.apply()
            }
        } catch (_: Throwable) {
        }
    }

    fun setImageBitmap(bitmap: Bitmap) {
        queueEvent {
            renderer.updateBitmap(bitmap)
            requestRender()
        }
    }

    fun updateHdrParams(enableHdr: Boolean, peakBoost: Float, threshold: Float, splitRatio: Float) {
        queueEvent {
            renderer.enableHdr = enableHdr
            renderer.peakBoost = peakBoost
            renderer.threshold = threshold
            renderer.splitRatio = splitRatio
            requestRender()
        }
        post { applyHardwareHdrSurfaceTransaction() }
    }

    private class PqRenderer : Renderer {
        var enableHdr: Boolean = true
        var peakBoost: Float = 3.0f
        var threshold: Float = 0.68f
        var splitRatio: Float = 0.50f

        private var pendingBitmap: Bitmap? = null
        private var textureId: Int = 0
        private var imageAspect: Float = 4f / 3f
        private var viewWidth: Int = 1
        private var viewHeight: Int = 1
        private var programId: Int = 0

        private val quadVertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            programId = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            val texIds = IntArray(1)
            GLES30.glGenTextures(1, texIds, 0)
            textureId = texIds[0]
            pendingBitmap?.let { uploadTexture(it) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width.coerceAtLeast(1)
            viewHeight = height.coerceAtLeast(1)
            GLES30.glViewport(0, 0, viewWidth, viewHeight)
        }

        fun updateBitmap(bmp: Bitmap) {
            pendingBitmap = bmp.copy(Bitmap.Config.ARGB_8888, false)
            imageAspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1).toFloat()
            if (textureId != 0) {
                pendingBitmap?.let { uploadTexture(it) }
            }
        }

        private fun uploadTexture(bmp: Bitmap) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            if (programId == 0 || textureId == 0) return

            val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
            val sx: Float
            val sy: Float
            if (imageAspect > viewAspect) {
                sx = 1f
                sy = viewAspect / imageAspect
            } else {
                sx = imageAspect / viewAspect
                sy = 1f
            }

            val coords = floatArrayOf(
                -sx, -sy, 0f, 1f,
                 sx, -sy, 1f, 1f,
                -sx,  sy, 0f, 0f,
                 sx,  sy, 1f, 0f
            )
            quadVertices.clear()
            quadVertices.put(coords).position(0)

            GLES30.glUseProgram(programId)

            val posLoc = GLES30.glGetAttribLocation(programId, "aPosition")
            val texLoc = GLES30.glGetAttribLocation(programId, "aTexCoord")

            quadVertices.position(0)
            GLES30.glEnableVertexAttribArray(posLoc)
            GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

            quadVertices.position(2)
            GLES30.glEnableVertexAttribArray(texLoc)
            GLES30.glVertexAttribPointer(texLoc, 2, GLES30.GL_FLOAT, false, 16, quadVertices)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uTexture"), 0)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(programId, "uEnablePqHdr"), if (enableHdr) 1 else 0)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uPeakBoost"), peakBoost)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uThreshold"), threshold)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(programId, "uSplitRatio"), splitRatio)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }

        private fun buildProgram(vsSource: String, fsSource: String): Int {
            val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSource)
            val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSource)
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            return prog
        }

        private fun compileShader(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src)
            GLES30.glCompileShader(s)
            return s
        }

        companion object {
            private const val VERTEX_SHADER = """#version 300 es
                in vec2 aPosition;
                in vec2 aTexCoord;
                out vec2 vTexCoord;
                void main() {
                    gl_Position = vec4(aPosition, 0.0, 1.0);
                    vTexCoord = aTexCoord;
                }
            """

            private const val FRAGMENT_SHADER = """#version 300 es
                precision highp float;
                in vec2 vTexCoord;
                uniform sampler2D uTexture;
                uniform int uEnablePqHdr;
                uniform float uPeakBoost;
                uniform float uThreshold;
                uniform float uSplitRatio;
                out vec4 fragColor;

                vec3 srgbToLinear(vec3 c) {
                    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
                }

                vec3 rec709ToRec2020(vec3 c) {
                    float r = 0.627404 * c.r + 0.329283 * c.g + 0.043313 * c.b;
                    float g = 0.069097 * c.r + 0.919540 * c.g + 0.011362 * c.b;
                    float b = 0.016391 * c.r + 0.088013 * c.g + 0.895595 * c.b;
                    return max(vec3(r, g, b), vec3(0.0));
                }

                vec3 linearToPq(vec3 nits) {
                    vec3 L = clamp(nits / 10000.0, 0.0, 1.0);
                    const float m1 = 0.1593017578125;
                    const float m2 = 78.84375;
                    const float c1 = 0.8359375;
                    const float c2 = 18.8515625;
                    const float c3 = 18.6875;
                    vec3 Lm1 = pow(L, vec3(m1));
                    return pow((c1 + c2 * Lm1) / (1.0 + c3 * Lm1), vec3(m2));
                }

                void main() {
                    vec4 tex = texture(uTexture, vTexCoord);
                    if (uEnablePqHdr == 0) {
                        vec3 base2020 = rec709ToRec2020(srgbToLinear(tex.rgb));
                        fragColor = vec4(linearToPq(base2020 * 203.0), 1.0);
                        return;
                    }

                    bool isHdrSide = vTexCoord.x >= uSplitRatio;
                    float luma = dot(tex.rgb, vec3(0.2126, 0.7152, 0.0722));
                    float boost = 1.0;
                    if (isHdrSide && luma > uThreshold) {
                        float norm = clamp((luma - uThreshold) / max(1.0 - uThreshold, 0.01), 0.0, 1.0);
                        // 采用软膝盖平滑过渡 (Soft-knee)，防止高光区域过早撞顶产生白色死光晕
                        float curve = pow(norm, 1.8);
                        boost = 1.0 + curve * (uPeakBoost - 1.0);
                    }

                    vec3 lin2020 = rec709ToRec2020(srgbToLinear(tex.rgb));
                    vec3 targetNits = lin2020 * 203.0 * boost;
                    vec3 pq = linearToPq(targetNits);

                    if (abs(vTexCoord.x - uSplitRatio) < 0.0025) {
                        pq = linearToPq(vec3(203.0));
                    }
                    fragColor = vec4(pq, 1.0);
                }
            """
        }
    }
}
