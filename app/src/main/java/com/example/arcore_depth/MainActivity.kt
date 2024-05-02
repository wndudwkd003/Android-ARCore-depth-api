package com.example.arcore_depth
/*
 * Copyright 2017 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.arcore_depth.common.helpers.CameraPermissionHelper
import com.example.arcore_depth.common.helpers.DepthSettings
import com.example.arcore_depth.common.helpers.DisplayRotationHelper
import com.example.arcore_depth.common.helpers.FullScreenHelper
import com.example.arcore_depth.common.helpers.InstantPlacementSettings
import com.example.arcore_depth.common.helpers.SnackbarHelper
import com.example.arcore_depth.common.helpers.TapHelper
import com.example.arcore_depth.common.helpers.TrackingStateHelper
import com.example.arcore_depth.common.samplerender.Framebuffer
import com.example.arcore_depth.common.samplerender.GLError
import com.example.arcore_depth.common.samplerender.Mesh
import com.example.arcore_depth.common.samplerender.SampleRender
import com.example.arcore_depth.common.samplerender.Shader
import com.example.arcore_depth.common.samplerender.Texture
import com.example.arcore_depth.common.samplerender.VertexBuffer
import com.example.arcore_depth.common.samplerender.arcore.BackgroundRenderer
import com.example.arcore_depth.common.samplerender.arcore.PlaneRenderer
import com.example.arcore_depth.common.samplerender.arcore.SpecularCubemapFilter
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Config.InstantPlacementMode
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import java.io.IOException
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity(), SampleRender.Renderer {
    private val TAG = MainActivity::class.java.simpleName

    private val Z_NEAR = 0.1f
    private val Z_FAR = 100f

    private val CUBEMAP_RESOLUTION = 16
    private val CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES = 32

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private var surfaceView: GLSurfaceView? = null

    private var installRequested = false

    private var session: Session? = null
    private val messageSnackbarHelper = SnackbarHelper()
    private var displayRotationHelper: DisplayRotationHelper? = null
    private var tapHelper: TapHelper? = null
    private var render: SampleRender? = null

    private var planeRenderer: PlaneRenderer? = null
    private var backgroundRenderer: BackgroundRenderer? = null
    private var virtualSceneFramebuffer: Framebuffer? = null
    private var hasSetTextureNames = false

    private val depthSettings = DepthSettings()
    private val depthSettingsMenuDialogCheckboxes = BooleanArray(2)

    private val instantPlacementSettings = InstantPlacementSettings()
    private val instantPlacementSettingsMenuDialogCheckboxes = BooleanArray(1)

    // Environmental HDR
    private var dfgTexture: Texture? = null
    private var cubemapFilter: SpecularCubemapFilter? = null

    private val projectionMatrix = FloatArray(16)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        surfaceView = findViewById<GLSurfaceView>(R.id.surfaceview)
        displayRotationHelper = DisplayRotationHelper( /*context=*/this)

        // Set up touch listener.
        tapHelper = TapHelper( /*context=*/this)
        surfaceView?.setOnTouchListener(tapHelper)

        // Set up renderer.
        render = SampleRender(surfaceView, this, assets)
        installRequested = false
        depthSettings.onCreate(this)
        instantPlacementSettings.onCreate(this)

        depthSettings.onCreate(this)
        depthSettings.setUseDepthForOcclusion(true)
        depthSettingsMenuDialogCheckboxes[1] = true
        depthSettingsMenuDialogCheckboxes[0] = true

        depthSettings.setUseDepthForOcclusion(depthSettingsMenuDialogCheckboxes[0])
        depthSettings.setDepthColorVisualizationEnabled(depthSettingsMenuDialogCheckboxes[1])
        instantPlacementSettings.isInstantPlacementEnabled =
            instantPlacementSettingsMenuDialogCheckboxes[0]


    }


    override fun onDestroy() {
        if (session != null) {
            // Explicitly close ARCore Session to release native resources.
            // Review the API reference for important considerations before calling close() in apps with
            // more complicated lifecycle requirements:
            // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
            session!!.close()
            session = null
        }
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (session == null) {
            var exception: Exception? = null
            var message: String? = null
            try {
                when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return
                    }

                    ArCoreApk.InstallStatus.INSTALLED -> {}
                }

                // ARCore requires camera permissions to operate. If we did not yet obtain runtime
                // permission on Android M and above, now is a good time to ask the user for it.
                if (!CameraPermissionHelper.hasCameraPermission(this)) {
                    CameraPermissionHelper.requestCameraPermission(this)
                    return
                }

                // Create the session.
                session = Session( /* context= */this)
            } catch (e: UnavailableArcoreNotInstalledException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableUserDeclinedInstallationException) {
                message = "Please install ARCore"
                exception = e
            } catch (e: UnavailableApkTooOldException) {
                message = "Please update ARCore"
                exception = e
            } catch (e: UnavailableSdkTooOldException) {
                message = "Please update this app"
                exception = e
            } catch (e: UnavailableDeviceNotCompatibleException) {
                message = "This device does not support AR"
                exception = e
            } catch (e: Exception) {
                message = "Failed to create AR session"
                exception = e
            }
            if (message != null) {
                messageSnackbarHelper.showError(this, message)
                Log.e(TAG, "Exception creating session", exception)
                return
            }
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            configureSession()
            // To record a live camera session for later playback, call
            // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
            // session instead of using the live camera feed, call
            // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
            // learn more about recording and playback, see:
            // https://developers.google.com/ar/develop/java/recording-and-playback
            session!!.resume()
        } catch (e: CameraNotAvailableException) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            session = null
            return
        }
        surfaceView!!.onResume()
        displayRotationHelper?.onResume()
    }

    public override fun onPause() {
        super.onPause()
        if (session != null) {
            // Note that the order matters - GLSurfaceView is paused first so that it does not try
            // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
            // still call session.update() and get a SessionPausedException.
            displayRotationHelper?.onPause()
            surfaceView!!.onPause()
            session!!.pause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
            // Use toast instead of snackbar here since the activity will exit.
            Toast.makeText(
                this,
                "Camera permission is needed to run this application",
                Toast.LENGTH_LONG
            )
                .show()
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this)
            }
            finish()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus)
    }



    /** Configures the session with feature settings.  */
    private fun configureSession() {
        val config = session!!.config
        config.lightEstimationMode = Config.LightEstimationMode.ENVIRONMENTAL_HDR
        if (session!!.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.setDepthMode(Config.DepthMode.AUTOMATIC)
        } else {
            config.setDepthMode(Config.DepthMode.DISABLED)
        }
        if (instantPlacementSettings.isInstantPlacementEnabled) {
            config.setInstantPlacementMode(InstantPlacementMode.LOCAL_Y_UP)
        } else {
            config.setInstantPlacementMode(InstantPlacementMode.DISABLED)
        }
        session!!.configure(config)
    }


    override fun onSurfaceCreated(render: SampleRender?) {
        // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
        // an IOException.
        try {
            planeRenderer = PlaneRenderer(render)
            backgroundRenderer = BackgroundRenderer(render)
            virtualSceneFramebuffer = Framebuffer(render,  /*width=*/1,  /*height=*/1)
            cubemapFilter = SpecularCubemapFilter(
                render, CUBEMAP_RESOLUTION, CUBEMAP_NUMBER_OF_IMPORTANCE_SAMPLES
            )
            // Load DFG lookup table for environmental lighting
            dfgTexture = Texture(
                render,
                Texture.Target.TEXTURE_2D,
                Texture.WrapMode.CLAMP_TO_EDGE,  /*useMipmaps=*/
                false
            )
            // The dfg.raw file is a raw half-float texture with two channels.
            val dfgResolution = 64
            val dfgChannels = 2
            val halfFloatSize = 2
            val buffer =
                ByteBuffer.allocateDirect(dfgResolution * dfgResolution * dfgChannels * halfFloatSize)
            assets.open("models/dfg.raw").use { `is` -> `is`.read(buffer.array()) }
            // SampleRender abstraction leaks here.
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, dfgTexture!!.textureId)
            GLError.maybeThrowGLException("Failed to bind DFG texture", "glBindTexture")
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,  /*level=*/
                0,
                GLES30.GL_RG16F,  /*width=*/
                dfgResolution,  /*height=*/
                dfgResolution,  /*border=*/
                0,
                GLES30.GL_RG,
                GLES30.GL_HALF_FLOAT,
                buffer
            )
            GLError.maybeThrowGLException("Failed to populate DFG texture", "glTexImage2D")

        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
        }
    }

    override fun onSurfaceChanged(render: SampleRender?, width: Int, height: Int) {
        displayRotationHelper!!.onSurfaceChanged(width, height)
        virtualSceneFramebuffer!!.resize(width, height)
    }

    override fun onDrawFrame(render: SampleRender?) {
        if (session == null) {
            return
        }

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.

        // Texture names should only be set once on a GL thread unless they change. This is done during
        // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
        // initialized during the execution of onSurfaceCreated.
        if (!hasSetTextureNames) {
            session!!.setCameraTextureNames(intArrayOf(backgroundRenderer!!.cameraColorTexture.textureId))
            hasSetTextureNames = true
        }

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.

        // -- Update per-frame state

        // Notify ARCore session that the view size changed so that the perspective matrix and
        // the video background can be properly adjusted.
        displayRotationHelper!!.updateSessionIfNeeded(session)

        // Obtain the current frame from the AR Session. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.

        // Obtain the current frame from the AR Session. When the configuration is set to
        // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
        // camera framerate.
        val frame: Frame = try {
            session!!.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available during onDrawFrame", e)
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.")
            return
        }

        val camera = frame.camera

        // Update BackgroundRenderer state to match the depth settings.
        try {
            backgroundRenderer!!.setUseDepthVisualization(
                render, depthSettings.depthColorVisualizationEnabled()
            )
            backgroundRenderer!!.setUseOcclusion(render, depthSettings.useDepthForOcclusion())
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read a required asset file", e)
            messageSnackbarHelper.showError(this, "Failed to read a required asset file: $e")
            return
        }

        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
        // used to draw the background camera image.
        backgroundRenderer!!.updateDisplayGeometry(frame)

        if (camera.trackingState == TrackingState.TRACKING
            && (depthSettings.useDepthForOcclusion()
                    || depthSettings.depthColorVisualizationEnabled())
        ) {
            try {
                frame.acquireDepthImage16Bits().use { depthImage ->
                    backgroundRenderer!!.updateCameraDepthTexture(
                        depthImage
                    )
                }
            } catch (e: NotYetAvailableException) {
                // This normally means that depth data is not available yet. This is normal so we will not
                // spam the logcat with this.
            }
        }

        // -- Draw background
        if (frame.timestamp != 0L) {
            // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
            // drawing possible leftover data from previous sessions if the texture is reused.
            backgroundRenderer!!.drawBackground(render)
        }

        // If not tracking, don't draw 3D objects.

        // If not tracking, don't draw 3D objects.
        if (camera.trackingState == TrackingState.PAUSED) {
            return
        }

        // Get projection matrix.

        // -- Draw non-occluded virtual objects (planes, point cloud)

        camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR)

    }
}
