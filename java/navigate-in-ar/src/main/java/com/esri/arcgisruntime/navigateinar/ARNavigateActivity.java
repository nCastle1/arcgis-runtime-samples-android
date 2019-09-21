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

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.esri.arcgisruntime.geometry.GeometryEngine;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.geometry.Polyline;
import com.esri.arcgisruntime.geometry.PolylineBuilder;
import com.esri.arcgisruntime.location.AndroidLocationDataSource;
import com.esri.arcgisruntime.location.LocationDataSource;
import com.esri.arcgisruntime.mapping.ArcGISScene;
import com.esri.arcgisruntime.mapping.ArcGISTiledElevationSource;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.NavigationConstraint;
import com.esri.arcgisruntime.mapping.Surface;
import com.esri.arcgisruntime.mapping.view.AtmosphereEffect;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.LayerSceneProperties;
import com.esri.arcgisruntime.mapping.view.SceneView;
import com.esri.arcgisruntime.mapping.view.SpaceEffect;
import com.esri.arcgisruntime.navigation.RouteTracker;
import com.esri.arcgisruntime.symbology.GeometricEffect;
import com.esri.arcgisruntime.symbology.MultilayerPolylineSymbol;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.symbology.SolidStrokeSymbolLayer;
import com.esri.arcgisruntime.symbology.StrokeSymbolLayer;
import com.esri.arcgisruntime.symbology.SymbolLayer;
import com.esri.arcgisruntime.tasks.networkanalysis.Route;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask;
import com.esri.arcgisruntime.toolkit.ar.ArLocationDataSource;
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView;

public class ARNavigateActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

  private static final String TAG = ARNavigateActivity.class.getSimpleName();

  private ArcGISArView mArView;
  private TextView helpLabel;
  private Button calibrationButton;
  private Button navigateButton;

  private TextToSpeech textToSpeech;

  // EEVVVIILLLL
  public static Route route;
  public static RouteResult routeResult;
  public static RouteParameters routeParameters;
  public static RouteTask routeTask;

  private RouteTracker routeTracker;

  private AndroidLocationDataSource trackingLocationDataSource;

  private GraphicsOverlay routeOverlay;
  private ArcGISTiledElevationSource elevationSource;
  private Surface elevationSurface;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (route == null){
      String error = "Route not set before launching activity";
      Toast.makeText(ARNavigateActivity.this, error, Toast.LENGTH_SHORT).show();
      Log.e(TAG, error);
    }

    setContentView(R.layout.activity_ar);

    helpLabel = findViewById(R.id.helpLabel);
    mArView = findViewById(R.id.arView);
    calibrationButton = findViewById(R.id.calibrateButton);
    navigateButton = findViewById(R.id.navigateStartButton);

    MSLAdjustedARLocationDataSource arLocationDataSource = new MSLAdjustedARLocationDataSource(this);
    arLocationDataSource.setAltitudeAdjustmentMode(MSLAdjustedARLocationDataSource.AltitudeAdjustmentMode.NMEA_PARSED_MSL);
    mArView.setLocationDataSource(arLocationDataSource);
    //mArView.setLocationDataSource(new ArLocationDataSource(this));

    // Disable plane visualization - not useful for this scenario
    mArView.getArSceneView().getPlaneRenderer().setEnabled(false);
    mArView.getArSceneView().getPlaneRenderer().setVisible(false);

    navigateButton.setOnClickListener(new View.OnClickListener() {
      @Override
      public void onClick(View v) {
        startTurnByTurn();

        if (routeTask.getRouteTaskInfo().isSupportsRerouting()){
          routeTracker.enableReroutingAsync(routeTask, routeParameters, RouteTracker.ReroutingStrategy.TO_NEXT_STOP, true);
        }
      }
    });

    requestPermissions();
  }

  /**
   * Setup the Ar View to use ArCore and tracking. Also add a touch listener to the scene view which checks for single
   * taps on a plane, as identified by ArCore. On tap, set the initial transformation matrix and load the scene.
   */
  private void setupArView() {

    mArView = findViewById(R.id.arView);
    mArView.registerLifecycle(getLifecycle());

    // show simple instructions to the user. Refer to the README for more details
    Toast.makeText(this,
        "Calibrate your heading before navigating!",
        Toast.LENGTH_LONG).show();

    configureRouteDisplay();
  }


  private void configureRouteDisplay(){
    SceneView sceneView = mArView.getSceneView();

    ArcGISScene scene = new ArcGISScene(Basemap.createImageryWithLabels());
    sceneView.setScene(scene);

    elevationSource = new ArcGISTiledElevationSource(getString(R.string.elevation_url));
    elevationSurface = new Surface();
    elevationSurface.getElevationSources().add(elevationSource);

    elevationSurface.setNavigationConstraint(NavigationConstraint.NONE);
    sceneView.getScene().setBaseSurface(elevationSurface);

    routeOverlay = new GraphicsOverlay();
    sceneView.getGraphicsOverlays().add(routeOverlay);

    routeOverlay.getSceneProperties().setSurfacePlacement(LayerSceneProperties.SurfacePlacement.ABSOLUTE);

    SolidStrokeSymbolLayer strokeSymbolLayer = new SolidStrokeSymbolLayer(1, Color.YELLOW, new LinkedList<GeometricEffect>(), StrokeSymbolLayer.LineStyle3D.TUBE);
    strokeSymbolLayer.setCapStyle(StrokeSymbolLayer.CapStyle.ROUND);
    ArrayList<SymbolLayer> layers = new ArrayList<SymbolLayer>();
    layers.add(strokeSymbolLayer);
    MultilayerPolylineSymbol polylineSymbol = new MultilayerPolylineSymbol(layers);
    SimpleRenderer polylineRenderer = new SimpleRenderer(polylineSymbol);
    routeOverlay.setRenderer(polylineRenderer);

    trackingLocationDataSource = new AndroidLocationDataSource(this);

    trackingLocationDataSource.addLocationChangedListener(lce -> HandleUpdatedLocation(lce));

    trackingLocationDataSource.startAsync();

    sceneView.setSpaceEffect(SpaceEffect.TRANSPARENT);
    sceneView.setAtmosphereEffect(AtmosphereEffect.NONE);

    setRoute(routeResult.getRoutes().get(0));
  }

  private void setRoute(Route inputRoute){
    route = inputRoute;
    Polyline originalPolyline = route.getRouteGeometry();

    // TODO - as written will this be subscribed multiple times?
    // TODO - de-jank
    elevationSource.addDoneLoadingListener(() -> {
      routeOverlay.getGraphics().clear();

      Polyline densifiedPolyline = (Polyline)GeometryEngine.densify(originalPolyline, 0.3);

      Iterable<Point> allPoints = densifiedPolyline.getParts().getPartsAsPoints();

      PolylineBuilder builder = new PolylineBuilder(densifiedPolyline.getSpatialReference());

      // TODO - parallelize
      int adjustedCount = 0;
      int originalCount = 0;
      for (Point originalPoint : allPoints){
        originalCount++;
        try {
          double elevation = elevationSurface.getElevationAsync(originalPoint).get(10, TimeUnit.SECONDS);

          Point newPoint = new Point(originalPoint.getX(), originalPoint.getY(), elevation + 3, originalPoint.getSpatialReference());

          builder.addPoint(newPoint);
          adjustedCount++;
        } catch (ExecutionException | InterruptedException e) {
          e.printStackTrace();
        } catch (TimeoutException e) {
          e.printStackTrace();
        }
      }

      if (originalCount > adjustedCount){
        Graphic routeGraphic = new Graphic(originalPolyline);
        routeOverlay.getGraphics().add(routeGraphic);
      } else {
        Graphic routeGraphic = new Graphic(builder.toGeometry());
        routeOverlay.getGraphics().add(routeGraphic);
      }
    });
    elevationSource.retryLoadAsync();
  }

  private void HandleUpdatedLocation(LocationDataSource.LocationChangedEvent locationEvent){
    if (routeTracker != null){
      routeTracker.trackLocationAsync(locationEvent.getLocation());
    }
  }

  private void startTurnByTurn(){
    routeTracker = new RouteTracker(this, routeResult, 0);

    textToSpeech = new TextToSpeech(this, this, "com.google.android.tts");

    routeTracker.addNewVoiceGuidanceListener((RouteTracker.NewVoiceGuidanceEvent newVoiceGuidanceEvent) -> {
      String newGuidance = newVoiceGuidanceEvent.getVoiceGuidance().getText();

      helpLabel.setText(newGuidance);
      speak(newGuidance);
    });

    routeTracker.addTrackingStatusChangedListener((RouteTracker.TrackingStatusChangedEvent trackingStatusChangedEvent) -> {
      helpLabel.setText(routeTracker.generateVoiceGuidance().getText());
    });

    routeTracker.addRerouteCompletedListener((RouteTracker.RerouteCompletedEvent rerouteCompletedEvent) -> {
      Route newRoute = rerouteCompletedEvent.getTrackingStatus().getRouteResult().getRoutes().get(0);

      if (!newRoute.equals(route)){
        setRoute(newRoute);
      }
    });

    routeTracker.addRerouteStartedListener((RouteTracker.RerouteStartedEvent rerouteStartedEvent) -> {
      helpLabel.setText(R.string.nav_rerouting_helptext);
    });
  }

  @TargetApi(21)
  private void speak(String utterance){
    textToSpeech.stop();
    textToSpeech.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, null);
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
    mArView.stopTracking();
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    mArView.startTracking(ArcGISArView.ARLocationTrackingMode.CONTINUOUS);
  }

  @Override
  protected void onDestroy() {
    mArView.stopTracking();
    super.onDestroy();
  }

  @Override
  public void onInit(int status) {
    // Had to put this here because of the voice guidance text-to-speech
  }
}
