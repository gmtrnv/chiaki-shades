// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL

package com.metallic.chiaki.stream

import android.content.res.AssetManager
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Two-pass video renderer for our own effect shaders.
 *
 * Pass 1 copies the decoder frame (OES texture) into an FBO at the STREAM resolution,
 * so effect shaders can do their own high-quality resampling from the original pixels.
 * Pass 2 runs the effect fragment shader (loaded from assets/shaders) to the view.
 *
 * Effect shader contract: `in vec2 vPos` (0..1, top-left origin), `uniform sampler2D uTex`,
 * `uniform vec2 uOutSize` (view px), `uniform vec2 uStreamSize` (stream px).
 * With [lensParams] set, the "Stretch óptico" lens uniforms (OpticalLens) are filled too.
 */
class ShaderRenderer(
	private val assets: AssetManager,
	private val effectFragmentAsset: String,
	private val streamWidth: Int,
	private val streamHeight: Int,
	private val lensParams: OpticalLens.Params?,
	colorParams: ColorGrade.Params,
	private val debandParams: Deband.Params,
	private val onSurfaceReady: (Surface) -> Unit
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener
{
	companion object
	{
		private const val TAG = "ShaderRenderer"

		private val QUAD_VERTICES = floatArrayOf(
			// X,    Y,    U,    V
			-1.0f, -1.0f, 0.0f, 0.0f,
			 1.0f, -1.0f, 1.0f, 0.0f,
			-1.0f,  1.0f, 0.0f, 1.0f,
			 1.0f,  1.0f, 1.0f, 1.0f
		)
		private const val VERTEX_STRIDE = 4 * 4
	}

	private var copyProgram = 0
	private var effectProgram = 0
	private var oesTexture = 0
	private var fboTexture = 0
	private var fbo = 0

	private var copyPosLoc = 0
	private var copyTexCoordLoc = 0
	private var copySTMatrixLoc = 0
	private var copyTexLoc = 0
	private var effPosLoc = 0
	private var effTexCoordLoc = 0
	private var effTexLoc = 0
	private var effOutSizeLoc = 0
	private var effStreamSizeLoc = 0
	private var effOpticalLoc = 0
	private var effSwapLoc = 0
	private var effLensWLoc = 0
	private var effKLoc = 0
	private var effAtanKLoc = 0
	private var effZcLoc = 0
	private var effVcompLoc = 0
	private var effKeepLoc = 0
	private var effHQLoc = 0
	private var effAntiringLoc = 0
	private var effSharpLoc = 0
	private var effSharpMaxLoc = 0
	private var effVigAmountLoc = 0
	private var effVigStartLoc = 0
	private var effVigAxisLoc = 0
	private var effColorLoc = 0
	private var effColorMulLoc = 0
	private var effColorAddLoc = 0
	private var effSatBaseLoc = 0
	private var effSatVibLoc = 0
	private var effCurveALoc = 0
	private var effCurveBLoc = 0
	private var effCurveCLoc = 0
	private var effCurveDLoc = 0
	private var effSplitShadowLoc = 0
	private var effSplitHighLoc = 0
	private var effSplitAmountLoc = 0
	private var effSplitRangeLoc = 0
	private var effBwLoc = 0
	private var effBwWeightsLoc = 0
	private var effDebandStepsLoc = 0
	private var effDebandThresholdLoc = 0
	private var effDebandRangeLoc = 0
	private var effGrainLoc = 0
	private var effFrameLoc = 0

	/** Replaced as a whole by [setColorParams] (UI thread); the GL thread reads it once per frame. */
	@Volatile private var colorUniforms = ColorGrade.uniforms(colorParams)
	/** The vignette belongs to the color preset, so it changes with it. */
	private class Vignette(val amount: Float, val start: Float, val axis: OpticalLens.VignetteAxis)
	@Volatile private var vignette = Vignette(lensParams?.vignetteAmount ?: 0f, lensParams?.vignetteStart ?: 0.55f,
		lensParams?.vignetteAxis ?: OpticalLens.VignetteAxis.SIDES)
	private var frameCount = 0

	/**
	 * Asks the view to draw once more. The view draws only when a new picture arrives (or this is
	 * called): it used to draw at every screen refresh, 120 times a second on a 120 Hz phone, with
	 * the whole effect shader each time, to show a picture that changes 60 times a second.
	 */
	@Volatile var requestRedraw: (() -> Unit)? = null

	/** Optical stretch lens on/off; set from the UI thread, applied on the next frame. */
	@Volatile var opticalEnabled = false
		set(value) { field = value; requestRedraw?.invoke() }
	/** "Color" toggle (color grading and vignette); set from the UI thread. */
	@Volatile var colorEnabled = false
		set(value) { field = value; requestRedraw?.invoke() }
	/** "HQ" toggle: high quality resampling and sharpening; set from the UI thread. */
	@Volatile var hqEnabled = true
		set(value) { field = value; requestRedraw?.invoke() }
	/** "Deband" toggle: our light debanding and grain; set from the UI thread. */
	@Volatile var debandEnabled = true
		set(value) { field = value; requestRedraw?.invoke() }

	private var lastDrawNs = 0L

	private var surfaceTexture: SurfaceTexture? = null
	private var surface: Surface? = null
	private val stMatrix = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
	private val pendingFrames = AtomicInteger(0)

	private var viewWidth = 1
	private var viewHeight = 1
	private var lens: OpticalLens.Solved? = null

	private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
		.order(ByteOrder.nativeOrder())
		.asFloatBuffer()
		.put(QUAD_VERTICES)
		.also { it.position(0) }

	override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)
	{
		copyProgram = createProgram("shaders/copy_oes.vert", "shaders/copy_oes.frag")
		copyPosLoc = GLES30.glGetAttribLocation(copyProgram, "aPosition")
		copyTexCoordLoc = GLES30.glGetAttribLocation(copyProgram, "aTexCoord")
		copySTMatrixLoc = GLES30.glGetUniformLocation(copyProgram, "uSTMatrix")
		copyTexLoc = GLES30.glGetUniformLocation(copyProgram, "uTex")

		effectProgram = createProgram("shaders/fullscreen.vert", effectFragmentAsset)
		effPosLoc = GLES30.glGetAttribLocation(effectProgram, "aPosition")
		effTexCoordLoc = GLES30.glGetAttribLocation(effectProgram, "aTexCoord")
		effTexLoc = GLES30.glGetUniformLocation(effectProgram, "uTex")
		effOutSizeLoc = GLES30.glGetUniformLocation(effectProgram, "uOutSize")
		effStreamSizeLoc = GLES30.glGetUniformLocation(effectProgram, "uStreamSize")
		effOpticalLoc = GLES30.glGetUniformLocation(effectProgram, "uOptical")
		effSwapLoc = GLES30.glGetUniformLocation(effectProgram, "uSwap")
		effLensWLoc = GLES30.glGetUniformLocation(effectProgram, "uLensW")
		effKLoc = GLES30.glGetUniformLocation(effectProgram, "uK")
		effAtanKLoc = GLES30.glGetUniformLocation(effectProgram, "uAtanK")
		effZcLoc = GLES30.glGetUniformLocation(effectProgram, "uZc")
		effVcompLoc = GLES30.glGetUniformLocation(effectProgram, "uVcomp")
		effKeepLoc = GLES30.glGetUniformLocation(effectProgram, "uKeep")
		effHQLoc = GLES30.glGetUniformLocation(effectProgram, "uHQ")
		effAntiringLoc = GLES30.glGetUniformLocation(effectProgram, "uAntiring")
		effSharpLoc = GLES30.glGetUniformLocation(effectProgram, "uSharp")
		effSharpMaxLoc = GLES30.glGetUniformLocation(effectProgram, "uSharpMax")
		effVigAmountLoc = GLES30.glGetUniformLocation(effectProgram, "uVigAmount")
		effVigStartLoc = GLES30.glGetUniformLocation(effectProgram, "uVigStart")
		effVigAxisLoc = GLES30.glGetUniformLocation(effectProgram, "uVigAxis")
		effColorLoc = GLES30.glGetUniformLocation(effectProgram, "uColor")
		effColorMulLoc = GLES30.glGetUniformLocation(effectProgram, "uColorMul")
		effColorAddLoc = GLES30.glGetUniformLocation(effectProgram, "uColorAdd")
		effSatBaseLoc = GLES30.glGetUniformLocation(effectProgram, "uSatBase")
		effSatVibLoc = GLES30.glGetUniformLocation(effectProgram, "uSatVib")
		effCurveALoc = GLES30.glGetUniformLocation(effectProgram, "uCurveA")
		effCurveBLoc = GLES30.glGetUniformLocation(effectProgram, "uCurveB")
		effCurveCLoc = GLES30.glGetUniformLocation(effectProgram, "uCurveC")
		effCurveDLoc = GLES30.glGetUniformLocation(effectProgram, "uCurveD")
		effSplitShadowLoc = GLES30.glGetUniformLocation(effectProgram, "uSplitShadow")
		effSplitHighLoc = GLES30.glGetUniformLocation(effectProgram, "uSplitHigh")
		effSplitAmountLoc = GLES30.glGetUniformLocation(effectProgram, "uSplitAmount")
		effSplitRangeLoc = GLES30.glGetUniformLocation(effectProgram, "uSplitRange")
		effBwLoc = GLES30.glGetUniformLocation(effectProgram, "uBw")
		effBwWeightsLoc = GLES30.glGetUniformLocation(effectProgram, "uBwWeights")
		effDebandStepsLoc = GLES30.glGetUniformLocation(effectProgram, "uDebandSteps")
		effDebandThresholdLoc = GLES30.glGetUniformLocation(effectProgram, "uDebandThreshold")
		effDebandRangeLoc = GLES30.glGetUniformLocation(effectProgram, "uDebandRange")
		effGrainLoc = GLES30.glGetUniformLocation(effectProgram, "uGrain")
		effFrameLoc = GLES30.glGetUniformLocation(effectProgram, "uFrame")

		val textures = IntArray(2)
		GLES30.glGenTextures(2, textures, 0)
		oesTexture = textures[0]
		fboTexture = textures[1]

		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

		// FBO at stream resolution: effect shaders sample the original pixels
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
		GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, streamWidth, streamHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
		GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

		val fbos = IntArray(1)
		GLES30.glGenFramebuffers(1, fbos, 0)
		fbo = fbos[0]
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
		GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexture, 0)
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

		surfaceTexture = SurfaceTexture(oesTexture).also {
			it.setOnFrameAvailableListener(this)
			surface = Surface(it).also(onSurfaceReady)
		}

		GLES30.glClearColor(0f, 0f, 0f, 1f)
		Log.i(TAG, "Created: stream ${streamWidth}x${streamHeight}, effect $effectFragmentAsset")
	}

	override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int)
	{
		viewWidth = width
		viewHeight = height
		Log.i(TAG, "View size ${width}x${height}")
		lens = lensParams?.let { params ->
			OpticalLens.solve(width, height, streamWidth.toFloat() / streamHeight, params).also {
				Log.i(TAG, "Lens: swap=${it.swap} W=${it.w} s0=${it.s0} k=${it.k} atan(k)=${it.atanK}")
			}
		}
	}

	override fun onDrawFrame(gl: GL10?)
	{
		val nowNs = System.nanoTime()
		if(lastDrawNs != 0L)
			RenderStats.noteDraw((nowNs - lastDrawNs) / 1_000_000)
		lastDrawNs = nowNs

		// Drain every queued frame so only the newest is shown and all buffers go back to the decoder
		var pending = pendingFrames.getAndSet(0)
		if(pending > 0)
		{
			while(pending-- > 0)
				surfaceTexture?.updateTexImage()
			surfaceTexture?.getTransformMatrix(stMatrix)
		}

		// Pass 1: OES -> stream-sized FBO
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
		GLES30.glViewport(0, 0, streamWidth, streamHeight)
		GLES30.glUseProgram(copyProgram)
		GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
		GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
		GLES30.glUniform1i(copyTexLoc, 0)
		GLES30.glUniformMatrix4fv(copySTMatrixLoc, 1, false, stMatrix, 0)
		drawQuad(copyPosLoc, copyTexCoordLoc)

		// Pass 2: effect shader -> view
		GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
		GLES30.glViewport(0, 0, viewWidth, viewHeight)
		GLES30.glUseProgram(effectProgram)
		GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
		GLES30.glUniform1i(effTexLoc, 0)
		GLES30.glUniform2f(effOutSizeLoc, viewWidth.toFloat(), viewHeight.toFloat())
		GLES30.glUniform2f(effStreamSizeLoc, streamWidth.toFloat(), streamHeight.toFloat())
		val lens = lens
		if(lensParams != null && lens != null)
		{
			GLES30.glUniform1f(effOpticalLoc, if(opticalEnabled) 1f else 0f)
			GLES30.glUniform1f(effSwapLoc, if(lens.swap) 1f else 0f)
			GLES30.glUniform1f(effLensWLoc, lens.w)
			GLES30.glUniform1f(effKLoc, lens.k)
			GLES30.glUniform1f(effAtanKLoc, lens.atanK)
			GLES30.glUniform1f(effZcLoc, lensParams.zc)
			GLES30.glUniform1f(effVcompLoc, lensParams.vcomp)
			GLES30.glUniform1f(effKeepLoc, lensParams.keep)
			GLES30.glUniform1f(effHQLoc, if(hqEnabled) 1f else 0f)
			GLES30.glUniform1f(effAntiringLoc, lensParams.antiring)
			GLES30.glUniform1f(effSharpLoc, lensParams.sharp)
			GLES30.glUniform1f(effSharpMaxLoc, lensParams.sharpMax)
			val vignette = vignette
			GLES30.glUniform1f(effVigAmountLoc, if(colorEnabled) vignette.amount else 0f)
			GLES30.glUniform1f(effVigStartLoc, vignette.start)
			GLES30.glUniform1f(effVigAxisLoc, vignette.axis.ordinal.toFloat())
		}
		val color = colorUniforms
		GLES30.glUniform1f(effColorLoc, if(colorEnabled) 1f else 0f)
		GLES30.glUniform3fv(effColorMulLoc, 1, color.mul, 0)
		GLES30.glUniform1f(effColorAddLoc, color.add)
		GLES30.glUniform1f(effSatBaseLoc, color.satBase)
		GLES30.glUniform1f(effSatVibLoc, color.satVib)
		GLES30.glUniform4fv(effCurveALoc, 1, color.curveA, 0)
		GLES30.glUniform4fv(effCurveBLoc, 1, color.curveB, 0)
		GLES30.glUniform4fv(effCurveCLoc, 1, color.curveC, 0)
		GLES30.glUniform4fv(effCurveDLoc, 1, color.curveD, 0)
		GLES30.glUniform3fv(effSplitShadowLoc, 1, color.splitShadow, 0)
		GLES30.glUniform3fv(effSplitHighLoc, 1, color.splitHigh, 0)
		GLES30.glUniform1f(effSplitAmountLoc, color.splitAmount)
		GLES30.glUniform2fv(effSplitRangeLoc, 1, color.splitRange, 0)
		GLES30.glUniform1f(effBwLoc, color.bw)
		GLES30.glUniform3fv(effBwWeightsLoc, 1, color.bwWeights, 0)
		val deband = debandEnabled
		GLES30.glUniform1i(effDebandStepsLoc, if(deband) debandParams.steps else 0)
		GLES30.glUniform1f(effDebandThresholdLoc, debandParams.threshold * Deband.THRESHOLD_FULL)
		GLES30.glUniform1f(effDebandRangeLoc, debandParams.range)
		GLES30.glUniform1f(effGrainLoc, if(deband) debandParams.grain * Deband.GRAIN_FULL else 0f)
		frameCount = (frameCount + 1) % 4096
		GLES30.glUniform1f(effFrameLoc, frameCount.toFloat())
		drawQuad(effPosLoc, effTexCoordLoc)
	}

	/** Called (on any thread) for every picture the decoder delivers: the connection is alive */
	@Volatile var onPicture: (() -> Unit)? = null

	override fun onFrameAvailable(surfaceTexture: SurfaceTexture?)
	{
		pendingFrames.incrementAndGet()
		RenderStats.newFrames.incrementAndGet()
		onPicture?.invoke()
		requestRedraw?.invoke()
	}

	/** New color grading and vignette (preset chosen during the stream); set from the UI thread, applied on the next frame. */
	fun setColorParams(params: ColorGrade.Params, vignetteAmount: Float, vignetteStart: Float, vignetteAxis: OpticalLens.VignetteAxis)
	{
		colorUniforms = ColorGrade.uniforms(params)
		vignette = Vignette(vignetteAmount, vignetteStart, vignetteAxis)
		requestRedraw?.invoke()
	}

	/** Called from the UI thread; GL objects are freed with the EGL context. */
	fun release()
	{
		surface?.release()
		surfaceTexture?.release()
	}

	private fun drawQuad(posLoc: Int, texCoordLoc: Int)
	{
		vertexBuffer.position(0)
		GLES30.glVertexAttribPointer(posLoc, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
		GLES30.glEnableVertexAttribArray(posLoc)
		vertexBuffer.position(2)
		GLES30.glVertexAttribPointer(texCoordLoc, 2, GLES30.GL_FLOAT, false, VERTEX_STRIDE, vertexBuffer)
		GLES30.glEnableVertexAttribArray(texCoordLoc)
		GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
		GLES30.glDisableVertexAttribArray(posLoc)
		GLES30.glDisableVertexAttribArray(texCoordLoc)
	}

	private fun loadAsset(path: String) = assets.open(path).bufferedReader().use { it.readText() }

	private fun createProgram(vertexAsset: String, fragmentAsset: String): Int
	{
		val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexAsset)
		val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentAsset)
		val program = GLES30.glCreateProgram()
		GLES30.glAttachShader(program, vertexShader)
		GLES30.glAttachShader(program, fragmentShader)
		GLES30.glLinkProgram(program)
		GLES30.glDeleteShader(vertexShader)
		GLES30.glDeleteShader(fragmentShader)
		val status = IntArray(1)
		GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
		if(status[0] != GLES30.GL_TRUE)
		{
			val log = GLES30.glGetProgramInfoLog(program)
			GLES30.glDeleteProgram(program)
			throw RuntimeException("Program link failed ($vertexAsset + $fragmentAsset): $log")
		}
		return program
	}

	private fun compileShader(type: Int, asset: String): Int
	{
		val shader = GLES30.glCreateShader(type)
		// #version must be on the very first line, strict drivers (Mali) reject anything before it
		GLES30.glShaderSource(shader, loadAsset(asset).trimStart())
		GLES30.glCompileShader(shader)
		val status = IntArray(1)
		GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
		if(status[0] != GLES30.GL_TRUE)
		{
			val log = GLES30.glGetShaderInfoLog(shader)
			GLES30.glDeleteShader(shader)
			throw RuntimeException("Shader compile failed ($asset): $log")
		}
		return shader
	}
}
