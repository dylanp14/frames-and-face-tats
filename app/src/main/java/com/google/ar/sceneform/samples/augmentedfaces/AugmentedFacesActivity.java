/*
 * Copyright 2019 Google LLC. All Rights Reserved.
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
package com.google.ar.sceneform.samples.augmentedfaces;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.support.design.widget.Snackbar;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedFace;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.TrackingState;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.rendering.Texture;
import com.google.ar.sceneform.ux.AugmentedFaceNode;
import com.google.ar.sceneform.ux.TransformableNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import static android.os.Environment.getExternalStoragePublicDirectory;

/**
 * This is an example activity that uses the Sceneform UX package to make common Augmented Faces
 * tasks easier.
 */
public class AugmentedFacesActivity extends AppCompatActivity {
  private static final String TAG = AugmentedFacesActivity.class.getSimpleName();

  private static final double MIN_OPENGL_VERSION = 3.0;

  private FaceArFragment arFragment;

  Button btnTakePic;
  ImageView imageView;

  private ModelRenderable faceRegionsRenderable;
  private Texture faceMeshTexture;

  private final HashMap<AugmentedFace, AugmentedFaceNode> faceNodeMap = new HashMap<>();

  @Override
  @SuppressWarnings({"AndroidApiChecker", "FutureReturnValueIgnored"})
  // CompletableFuture requires api level 24
  // FutureReturnValueIgnored is not valid
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!checkIsSupportedDeviceOrFinish(this)) {
      return;
    }

    setContentView(R.layout.activity_face_mesh);
    arFragment = (FaceArFragment) getSupportFragmentManager().findFragmentById(R.id.face_fragment);

    btnTakePic = findViewById(R.id.btnTakePic);
    btnTakePic.setOnClickListener(imageView -> takePhoto());
    imageView = findViewById(R.id.image);

    // Load the face mesh texture.
    Texture.builder()
        .setSource(this, R.drawable.peep)
        .build()
        .thenAccept(texture -> faceMeshTexture = texture);

    ArSceneView sceneView = arFragment.getArSceneView();

    initializeFrameGallery();
    // This is important to make sure that the camera stream renders first so that
    // the face mesh occlusion works correctly.
    sceneView.setCameraStreamRenderPriority(Renderable.RENDER_PRIORITY_FIRST);

    Scene scene = sceneView.getScene();

    scene.addOnUpdateListener(
        (FrameTime frameTime) -> {
          if (faceRegionsRenderable == null || faceMeshTexture == null) {
            return;
          }

          Collection<AugmentedFace> faceList =
              sceneView.getSession().getAllTrackables(AugmentedFace.class);

          // Make new AugmentedFaceNodes for any new faces.
          for (AugmentedFace face : faceList) {
            if (!faceNodeMap.containsKey(face)) {
              AugmentedFaceNode faceNode = new AugmentedFaceNode(face);
              faceNode.setParent(scene);
              faceNode.setFaceRegionsRenderable(faceRegionsRenderable);
              faceNode.setFaceMeshTexture(faceMeshTexture);
              faceNodeMap.put(face, faceNode);
            }
          }

          // Remove any AugmentedFaceNodes associated with an AugmentedFace that stopped tracking.
          Iterator<Map.Entry<AugmentedFace, AugmentedFaceNode>> iter =
              faceNodeMap.entrySet().iterator();
          while (iter.hasNext()) {
            Map.Entry<AugmentedFace, AugmentedFaceNode> entry = iter.next();
            AugmentedFace face = entry.getKey();
            if (face.getTrackingState() == TrackingState.STOPPED) {
              AugmentedFaceNode faceNode = entry.getValue();
              faceNode.setParent(null);
              iter.remove();
            }
          }
        });

  }

  /**
   * Returns false and displays an error message if Sceneform can not run, true if Sceneform can run
   * on this device.
   *
   * <p>Sceneform requires Android N on the device as well as OpenGL 3.0 capabilities.
   *
   * <p>Finishes the activity if Sceneform can not run
   */
  public static boolean checkIsSupportedDeviceOrFinish(final Activity activity) {
    if (ArCoreApk.getInstance().checkAvailability(activity)
        == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE) {
      Log.e(TAG, "Augmented Faces requires ArCore.");
      Toast.makeText(activity, "Augmented Faces requires ArCore", Toast.LENGTH_LONG).show();
      activity.finish();
      return false;
    }
    String openGlVersionString =
        ((ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE))
            .getDeviceConfigurationInfo()
            .getGlEsVersion();
    if (Double.parseDouble(openGlVersionString) < MIN_OPENGL_VERSION) {
      Log.e(TAG, "Sceneform requires OpenGL ES 3.0 later");
      Toast.makeText(activity, "Sceneform requires OpenGL ES 3.0 or later", Toast.LENGTH_LONG)
          .show();
      activity.finish();
      return false;
    }
    return true;
  }

  private void buildObject(String object){
    Iterator<Map.Entry<AugmentedFace, AugmentedFaceNode>> iter =
            faceNodeMap.entrySet().iterator();
    while (iter.hasNext()) {
      Map.Entry<AugmentedFace, AugmentedFaceNode> entry = iter.next();
      AugmentedFace face = entry.getKey();
      AugmentedFaceNode faceNode = entry.getValue();
      for(Node child : faceNode.getChildren()){
        child.setRenderable(null);
      }
      iter.remove();
    }
    faceNodeMap.clear();
    ModelRenderable.builder()
            .setSource(this, Uri.parse(object))
            .build()
            .thenAccept(
                    modelRenderable -> {
                      faceRegionsRenderable = modelRenderable;
                      modelRenderable.setShadowCaster(false);
                      modelRenderable.setShadowReceiver(false);
                    });
    Texture.builder()
            .setSource(this, R.drawable.smiley)
            .build()
            .thenAccept(texture -> faceMeshTexture = texture);
  }

  private void initializeFrameGallery() {
    LinearLayout gallery = findViewById(R.id.gallery_layout);

    ImageView frame1 = new ImageView(this);
    frame1.setImageResource(R.drawable.circle_frames);
    frame1.setContentDescription("frame1");
    frame1.setOnClickListener(view ->{buildObject("Glasses_FBX.sfb");});
    gallery.addView(frame1);

    ImageView frame2 = new ImageView(this);
    frame2.setImageResource(R.drawable.eye_glass);
    frame2.setContentDescription("frame2");
    frame2.setOnClickListener(view ->{buildObject("Eye_Glass_fix.sfb");});
    gallery.addView(frame2);

    ImageView frame3 = new ImageView(this);
    frame3.setImageResource(R.drawable.sunglasses2);
    frame3.setContentDescription("frame3");
    frame3.setOnClickListener(view ->{buildObject("Sunglasses2.sfb");});
    gallery.addView(frame3);

    ImageView frame4 = new ImageView(this);
    frame4.setImageResource(R.drawable.eyeglasses2);
    frame4.setContentDescription("frame4");
    frame4.setOnClickListener(view ->{buildObject("eyeglasses.sfb");});
    gallery.addView(frame4);

    ImageView frame5 = new ImageView(this);
    frame5.setImageResource(R.drawable.pinhole);
    frame5.setContentDescription("frame5");
    frame5.setOnClickListener(view ->{buildObject("Pinhole_Shades_fix.sfb");});
    gallery.addView(frame5);

    ImageView frame6 = new ImageView(this);
    frame6.setImageResource(R.drawable.glasses3);
    frame6.setContentDescription("frame6");
    frame6.setOnClickListener(view ->{buildObject("3d-model.sfb");});
    gallery.addView(frame6);

  }

  private String generateFilename() {
    String date =
            new SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.getDefault()).format(new Date());
    return Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES) + File.separator + "Sceneform/" + date + "_screenshot.jpg";
  }

  private void saveBitmapToDisk(Bitmap bitmap, String filename) throws IOException {

    File out = new File(filename);
    if (!out.getParentFile().exists()) {
      out.getParentFile().mkdirs();
    }
    try (FileOutputStream outputStream = new FileOutputStream(filename);
         ByteArrayOutputStream outputData = new ByteArrayOutputStream()) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputData);
      outputData.writeTo(outputStream);
      outputStream.flush();
      outputStream.close();
    } catch (IOException ex) {
      throw new IOException("Failed to save bitmap to disk", ex);
    }
  }

  private void takePhoto() {
    final String filename = generateFilename();
    ArSceneView view = arFragment.getArSceneView();

    // Create a bitmap the size of the scene view.
    final Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(),
            Bitmap.Config.ARGB_8888);

    // Create a handler thread to offload the processing of the image.
    final HandlerThread handlerThread = new HandlerThread("PixelCopier");
    handlerThread.start();
    // Make the request to copy.
    PixelCopy.request(view, bitmap, (copyResult) -> {
      if (copyResult == PixelCopy.SUCCESS) {
        try {
          saveBitmapToDisk(bitmap, filename);
        } catch (IOException e) {
          Toast toast = Toast.makeText(AugmentedFacesActivity.this, e.toString(),
                  Toast.LENGTH_LONG);
          toast.show();
          return;
        }
        Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content),
                "Photo saved", Snackbar.LENGTH_LONG);
        snackbar.setAction("Open in Photos", v -> {
          File photoFile = new File(filename);

          Uri photoURI = FileProvider.getUriForFile(AugmentedFacesActivity.this,
                  AugmentedFacesActivity.this.getPackageName() + ".ar.codelab.name.provider",
                  photoFile);
          Intent intent = new Intent(Intent.ACTION_VIEW, photoURI);
          intent.setDataAndType(photoURI, "image/*");
          intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
          startActivity(intent);

        });
        snackbar.show();
      } else {
        Toast toast = Toast.makeText(AugmentedFacesActivity.this,
                "Failed to copyPixels: " + copyResult, Toast.LENGTH_LONG);
        toast.show();
      }
      handlerThread.quitSafely();
    }, new Handler(handlerThread.getLooper()));
  }

}
