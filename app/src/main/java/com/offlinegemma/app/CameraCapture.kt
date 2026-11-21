package com.offlinegemma.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/**
 * A camera inside the app.
 *
 * The phone's own camera app must not be used here: on Xiaomi/Redmi, opening it starts the
 * vendor "camera boost" killer, which SIGKILLs every background process - including this app,
 * even while it holds a foreground service. The model then had to be reloaded (20+ seconds)
 * and the photo was left behind. Keeping the preview in our own foreground process avoids
 * that entirely.
 */
@Composable
fun CameraCapture(onPhoto: (Bitmap) -> Unit, onClose: () -> Unit) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  var granted by remember {
    mutableStateOf(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED,
    )
  }
  val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
  LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
  BackHandler(onBack = onClose)

  if (!granted) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(32.dp),
      ) {
        Text(
          "Jonaki AI needs the camera to take a photo. Pictures stay on this phone.",
          color = Color.White,
          textAlign = TextAlign.Center,
          style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.size(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          TextButton(onClick = onClose) { Text("Cancel", color = Color.White) }
          Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
      }
    }
    return
  }

  val previewView = remember {
    PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
  }
  val capture = remember {
    ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
  }
  val worker = remember { Executors.newSingleThreadExecutor() }
  val main = remember { ContextCompat.getMainExecutor(context) }
  var lens by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }

  DisposableEffect(lifecycleOwner, lens) {
    val future = ProcessCameraProvider.getInstance(context)
    var provider: ProcessCameraProvider? = null
    future.addListener({
      runCatching {
        provider = future.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        capture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        provider!!.unbindAll()
        provider!!.bindToLifecycle(
          lifecycleOwner,
          CameraSelector.Builder().requireLensFacing(lens).build(),
          preview,
          capture,
        )
      }.onFailure { error = "The camera could not start: ${it.message}" }
    }, main)
    onDispose { runCatching { provider?.unbindAll() } }
  }

  fun shoot() {
    if (busy) return
    busy = true
    error = null
    capture.takePicture(worker, object : ImageCapture.OnImageCapturedCallback() {
      override fun onCaptureSuccess(image: ImageProxy) {
        val rotation = image.imageInfo.rotationDegrees
        val jpeg = image.planes.first().buffer.let { buf ->
          ByteArray(buf.remaining()).also { buf.get(it) }
        }
        image.close()
        val bitmap = runCatching { decodePhoto(jpeg, rotation) }.getOrNull()
        main.execute {
          busy = false
          if (bitmap != null) onPhoto(bitmap) else error = "The photo could not be read. Try again."
        }
      }

      override fun onError(exc: ImageCaptureException) {
        main.execute {
          busy = false
          error = exc.message ?: "The camera failed. Try again."
        }
      }
    })
  }

  Box(Modifier.fillMaxSize().background(Color.Black)) {
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    IconButton(
      onClick = onClose,
      modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp),
    ) { Icon(Icons.Rounded.Close, "Close the camera", tint = Color.White) }

    if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)

    Column(
      modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      error?.let {
        Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
      }
      Row(
        horizontalArrangement = Arrangement.spacedBy(36.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Spacer(Modifier.size(56.dp))
        FilledIconButton(
          onClick = ::shoot,
          enabled = !busy,
          shape = CircleShape,
          modifier = Modifier.size(76.dp),
          colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.White,
            contentColor = Color.Black,
          ),
        ) { Icon(Icons.Rounded.PhotoCamera, "Take the photo", Modifier.size(32.dp)) }
        IconButton(
          onClick = { lens = if (lens == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK },
          modifier = Modifier.size(56.dp),
        ) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White) }
      }
    }
  }
}

/** JPEG from the camera -> a small, upright bitmap (Gemma looks at 1024 px; bigger only costs memory). */
private fun decodePhoto(jpeg: ByteArray, rotation: Int): Bitmap? {
  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
  var sample = 1
  while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
  val options = BitmapFactory.Options().apply { inSampleSize = sample }
  val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null

  val longest = maxOf(raw.width, raw.height)
  val scaled = if (longest > MAX_SIDE) {
    val s = MAX_SIDE.toFloat() / longest
    Bitmap.createScaledBitmap(raw, (raw.width * s).toInt().coerceAtLeast(1), (raw.height * s).toInt().coerceAtLeast(1), true)
      .also { if (it !== raw) raw.recycle() }
  } else raw

  if (rotation == 0) return scaled
  val turned = Bitmap.createBitmap(
    scaled, 0, 0, scaled.width, scaled.height,
    Matrix().apply { postRotate(rotation.toFloat()) }, true,
  )
  if (turned !== scaled) scaled.recycle()
  return turned
}

private const val MAX_SIDE = 1024
