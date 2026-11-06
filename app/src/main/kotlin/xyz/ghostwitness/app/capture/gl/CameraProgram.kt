package xyz.ghostwitness.app.capture.gl

import android.opengl.GLES11Ext
import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the camera's external texture over the whole viewport, centre-cropped to the target's aspect
 * ratio so a 4:3 sensor stream fills a 16:9 tier without stretching. Needs a current GLES 2 context.
 */
class CameraProgram {
    private val program: Int = link(compile(GLES20.GL_VERTEX_SHADER, VERTEX), compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
    private val position = GLES20.glGetAttribLocation(program, "aPosition")
    private val texCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
    private val texMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    private val crop = GLES20.glGetUniformLocation(program, "uCrop")
    private val quad: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val coords: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    /** A new external texture for a SurfaceTexture. */
    fun createTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun draw(texture: Int, transform: FloatArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(texMatrix, 1, false, transform, 0)
        val (sx, sy) = cropScale(width, height, sourceWidth, sourceHeight)
        GLES20.glUniform2f(crop, sx, sy)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glEnableVertexAttribArray(texCoord)
        GLES20.glVertexAttribPointer(texCoord, 2, GLES20.GL_FLOAT, false, 0, coords)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(texCoord)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun cropScale(width: Int, height: Int, sourceWidth: Int, sourceHeight: Int): Pair<Float, Float> {
        val target = width.toFloat() / height
        val source = sourceWidth.toFloat() / sourceHeight
        return if (source > target) (target / source) to 1f else 1f to (source / target)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("shader compile failed: $log")
        }
        return shader
    }

    private fun link(vertex: Int, fragment: Int): Int {
        val linked = GLES20.glCreateProgram()
        GLES20.glAttachShader(linked, vertex)
        GLES20.glAttachShader(linked, fragment)
        GLES20.glLinkProgram(linked)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(linked)
            GLES20.glDeleteProgram(linked)
            throw IllegalStateException("program link failed: $log")
        }
        return linked
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uTexMatrix;
            uniform vec2 uCrop;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vec2 cropped = vec2(0.5) + (aTexCoord - vec2(0.5)) * uCrop;
                vTexCoord = (uTexMatrix * vec4(cropped, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(values)
                position(0)
            }
    }
}
