/*
 *  Copyright 2019 Esri
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.esri.arcgisruntime.navigateinar;

import java.util.List;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.geometry.Envelope;
import com.esri.arcgisruntime.geometry.GeodeticCurveType;
import com.esri.arcgisruntime.geometry.GeometryEngine;
import com.esri.arcgisruntime.geometry.LinearUnit;
import com.esri.arcgisruntime.geometry.LinearUnitId;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.mapping.ArcGISScene;
import com.esri.arcgisruntime.mapping.view.Camera;
import com.esri.arcgisruntime.mapping.view.DefaultSceneViewOnTouchListener;
import com.esri.arcgisruntime.tasks.networkanalysis.Route;
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;

public class ARNavigateActivity extends AppCompatActivity {

  private static final String TAG = ARNavigateActivity.class.getSimpleName();

  private boolean mHasConfiguredScene = false;

  private ArcGISArView mArView;

  // EEVVVIILLLL
  public static Route route;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (route == null){
      String error = "Route not set before launching activity";
      Toast.makeText(ARNavigateActivity.this, error, Toast.LENGTH_SHORT).show();
      Log.e(TAG, error);
    }

    setContentView(R.layout.activity_ar);
    requestPermissions();
  }

  /**
   * Setup the Ar View to use ArCore and tracking. Also add a touch listener to the scene view which checks for single
   * taps on a plane, as identified by ArCore. On tap, set the initial transformation matrix and load the scene.
   */
  private void setupArView() {

    mArView = findViewById(R.id.arView);
    mArView.registerLifecycle(getLifecycle());

    // use ArCore and tracking
    mArView.setTracking(true);
    // show planes identified by ArCore
    mArView.getArSceneView().getPlaneRenderer().setEnabled(true);
    mArView.getArSceneView().getPlaneRenderer().setVisible(true);

    // show simple instructions to the user. Refer to the README for more details
    Toast.makeText(this,
        "Move the camera back and forth over a plane. When a plane is detected, tap on the plane to place a scene",
        Toast.LENGTH_LONG).show();

    // on tap
    mArView.getSceneView().setOnTouchListener(new DefaultSceneViewOnTouchListener(mArView.getSceneView()) {
      @Override public boolean onSingleTapConfirmed(MotionEvent motionEvent) {
        // get the hit results for the tap
        List<HitResult> hitResults = mArView.getArSceneView().getArFrame().hitTest(motionEvent);
        // check if the tapped point is recognized as a plane by ArCore
        if (!hitResults.isEmpty() && hitResults.get(0).getTrackable() instanceof Plane) {
          // get a reference to the tapped plane
          Plane plane = (Plane) hitResults.get(0).getTrackable();
          Toast.makeText(ARNavigateActivity.this, "Plane detected with a width of: " + plane.getExtentX(), Toast.LENGTH_SHORT)
              .show();

          // get the tapped point as a graphics point
          android.graphics.Point screenPoint = new android.graphics.Point(Math.round(motionEvent.getX()),
              Math.round(motionEvent.getY()));
          // if initial transformation set correctly
          if (mArView.setInitialTransformationMatrix(screenPoint)) {
            // the scene hasn't been configured
            if (!mHasConfiguredScene) {
              //loadSceneFromPackage(plane);
            } else if (mArView.getSceneView().getScene() != null) {
              // use information from the scene to determine the origin camera and translation factor
              updateTranslationFactorAndOriginCamera(mArView.getSceneView().getScene(), plane);
            }
          }
        } else {
          String error = "ArCore doesn't recognize this point as a plane.";
          Toast.makeText(ARNavigateActivity.this, error, Toast.LENGTH_SHORT).show();
          Log.e(TAG, error);
        }
        return super.onSingleTapConfirmed(motionEvent);
      }
    });
  }

  /**
   * Load the scene's first layer and calculate it'd geographical width. Use the scene's width and ArCore's assessment
   * of the plane's width to set the AR view's translation transformation factor. Use the center of the scene, corrected
   * for elevation, as the origin camera's look at point.
   *
   * @param scene to display
   * @param plane detected by ArCore to which the scene should be pinned
   */
  private void updateTranslationFactorAndOriginCamera(ArcGISScene scene, Plane plane) {

    // load the scene's first layer
    scene.getOperationalLayers().get(0).loadAsync();
    scene.getOperationalLayers().get(0).addDoneLoadingListener(() -> {
      // get the scene extent
      Envelope layerExtent = scene.getOperationalLayers().get(0).getFullExtent();
      // calculate the width of the layer content in meters
      double width = GeometryEngine
          .lengthGeodetic(layerExtent, new LinearUnit(LinearUnitId.METERS), GeodeticCurveType.GEODESIC);

      // set the translation factor based on scene content width and desired physical size
      mArView.setTranslationFactor(width / plane.getExtentX());

      // find the center point of the scene content
      Point centerPoint = layerExtent.getCenter();

      // find the altitude of the surface at the center
      ListenableFuture<Double> elevationFuture = mArView.getSceneView().getScene().getBaseSurface()
          .getElevationAsync(centerPoint);
      elevationFuture.addDoneListener(() -> {
        try {
          double elevation = elevationFuture.get();
          // create a new origin camera looking at the bottom center of the scene
          mArView.setOriginCamera(new Camera(new Point(centerPoint.getX(), centerPoint.getY(), elevation), 0, 90, 0));
        } catch (Exception e) {
          Log.e(TAG, "Error getting elevation at point: " + e.getMessage());
        }
      });
    });
  }

  /**
   * Request read external storage for API level 23+.
   */
  private void requestPermissions() {
    // define permission to request
    String[] reqPermission = { Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA };
    int requestCode = 2;
    if (ContextCompat.checkSelfPermission(this, reqPermission[0]) == PackageManager.PERMISSION_GRANTED) {
      setupArView();
    } else {
      // request permission
      ActivityCompat.requestPermissions(this, reqPermission, requestCode);
    }
  }

  /**
   * Handle the permissions request response.
   */
  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      setupArView();
    } else {
      // report to user that permission was denied
      Toast.makeText(this, getString(R.string.navigate_ar_permission_denied), Toast.LENGTH_SHORT).show();
    }
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
  }

  @Override
  protected void onPause() {
    mArView.getSceneView().pause();
    mArView.stopTracking();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    mArView.startTracking(ArcGISArView.ARLocationTrackingMode.IGNORE);
    mArView.getSceneView().resume();
  }

  @Override
  protected void onDestroy() {
    mArView.stopTracking();
    super.onDestroy();
  }
}
