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
import com.esri.arcgisruntime.loadable.LoadStatus;
import com.esri.arcgisruntime.location.AndroidLocationDataSource;
import com.esri.arcgisruntime.location.LocationDataSource;
import com.esri.arcgisruntime.mapping.ArcGISScene;
import com.esri.arcgisruntime.mapping.ArcGISTiledElevationSource;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.NavigationConstraint;
import com.esri.arcgisruntime.mapping.Surface;
import com.esri.arcgisruntime.mapping.view.AtmosphereEffect;
import com.esri.arcgisruntime.mapping.view.Camera;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.LayerSceneProperties;
import com.esri.arcgisruntime.mapping.view.SceneView;
import com.esri.arcgisruntime.mapping.view.SpaceEffect;
import com.esri.arcgisruntime.navigation.RouteTracker;
import com.esri.arcgisruntime.symbology.MultilayerPolylineSymbol;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.symbology.SolidStrokeSymbolLayer;
import com.esri.arcgisruntime.symbology.StrokeSymbolLayer;
import com.esri.arcgisruntime.symbology.SymbolLayer;
import com.esri.arcgisruntime.tasks.networkanalysis.Route;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask;
import com.esri.arcgisruntime.toolkit.ar.ArcGISArView;
import com.esri.arcgisruntime.toolkit.control.JoystickSeekBar;

public class ARNavigateActivity extends AppCompatActivity implements TextToSpeech.OnInitListener {

    private static final String TAG = ARNavigateActivity.class.getSimpleName();

    private ArcGISArView mArView;

    private TextView mHelpLabel;
    private View mCalibrationView;

    // TODO - don't pass data this way.
    public static Route route;
    public static RouteResult routeResult;
    public static RouteParameters routeParameters;
    public static RouteTask routeTask;

    private RouteTracker routeTracker;

    private TextToSpeech textToSpeech;

    private GraphicsOverlay routeOverlay;
    private ArcGISTiledElevationSource elevationSource;
    private Surface elevationSurface;
    private ArcGISScene mScene;

    // Calibration state fields
    private boolean isCalibrating = false;
    private float altitudeOffsetValue = 0f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (route == null) {
            String error = "Route not set before launching activity";
            Toast.makeText(ARNavigateActivity.this, error, Toast.LENGTH_SHORT).show();
            Log.e(TAG, error);
        }

        setContentView(R.layout.activity_ar);

        // Get references to the views defined in the layout
        mHelpLabel = findViewById(R.id.helpLabel);
        mArView = findViewById(R.id.arView);
        Button mCalibrationButton = findViewById(R.id.calibrateButton);
        Button mNavigateButton = findViewById(R.id.navigateStartButton);
        mCalibrationView = findViewById(R.id.calibrationView);
        JoystickSeekBar mHeadingSlider = findViewById(R.id.headingJoystick);
        JoystickSeekBar mAltitudeSlider = findViewById(R.id.altitudeJoystick);

        // Set up a special location data source that provides flexibility for calibration
        //     and provides locations with height above mean sea level, rather than height above ellipsoid.
        //     This matches the behavior of CLLocationDataSource on iOS.
        MSLAdjustedARLocationDataSource arLocationDataSource = new MSLAdjustedARLocationDataSource(this);
        arLocationDataSource.setAltitudeAdjustmentMode(MSLAdjustedARLocationDataSource.AltitudeAdjustmentMode.NMEA_PARSED_MSL);
        mArView.setLocationDataSource(arLocationDataSource);

        // If you want heights above ellipsoid (not mean sea level/orthometric), use this instead
        //mArView.setLocationDataSource(new ArLocationDataSource(this));

        // Disable plane visualization. It is not useful for this AR scenario.
        mArView.getArSceneView().getPlaneRenderer().setEnabled(false);
        mArView.getArSceneView().getPlaneRenderer().setVisible(false);

        mNavigateButton.setOnClickListener(v -> {
            // Start turn-by-turn when the user is ready
            startTurnByTurn();
        });

        // Show/hide calibration view
        mCalibrationButton.setOnClickListener(v -> setIsCalibrating(!isCalibrating));

        // Begin listening for calibration value changes for heading
        // This behavior makes larger adjustments the further from the center you move on the slider
        // The slider automatically snaps to the center (no change) when you stop interacting with it
        mHeadingSlider.addDeltaProgressUpdatedListener(delta -> {
            // Note: jsb_min and jsb_max must be set to ensure you get usable delta values
            // Sample uses -10 and 10, set in activity_ar.xml

            // Get the origin camera
            Camera camera = mArView.getOriginCamera();

            // Add the heading change to the existing heading
            double heading = camera.getHeading() + delta;

            // Get a camera with a new heading
            Camera newCam = camera.rotateTo(heading, camera.getPitch(), camera.getRoll());

            // Apply the new origin camera
            mArView.setOriginCamera(newCam);
        });

        // Begin listening for calibration value changes for altitude
        // NOTE: the custom location data source enables applying an altitude offset to every altitude provided by the GPS
        mAltitudeSlider.addDeltaProgressUpdatedListener(delta -> {
            // Note: jsb_min and jsb_max must be set to ensure you get usable delta values
            // Sample uses -10 and 10, set in activity_ar.xml
            altitudeOffsetValue += delta;
            arLocationDataSource.setManualOffset(altitudeOffsetValue);
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

    private void setIsCalibrating(boolean isCalibrating) {
        // Show the basemap for reference while calibration is in progress
        this.isCalibrating = isCalibrating;
        if (isCalibrating) {
            mScene.getBaseSurface().setOpacity(0.5f);
            mCalibrationView.setVisibility(View.VISIBLE);
        } else {
            mScene.getBaseSurface().setOpacity(0f);
            mCalibrationView.setVisibility(View.GONE);
        }
    }

    private void configureRouteDisplay() {
        // Get the scene view from the AR view
        SceneView sceneView = mArView.getSceneView();

        // Create and show a scene
        mScene = new ArcGISScene(Basemap.createImageryWithLabels());
        sceneView.setScene(mScene);

        // Create and add an elevation surface to the scene
        elevationSource = new ArcGISTiledElevationSource(getString(R.string.elevation_url));
        elevationSurface = new Surface();
        elevationSurface.getElevationSources().add(elevationSource);
        sceneView.getScene().setBaseSurface(elevationSurface);

        // Allow the user to navigate underneath the surface
        // This would be critical for working underground or on paths that go underground (e.g. a tunnel)
        elevationSurface.setNavigationConstraint(NavigationConstraint.NONE);

        // Hide the basemap. The image feed provides map context while navigating in AR
        elevationSurface.setOpacity(0f);

        // Create and add a graphics overlay for showing the route line
        routeOverlay = new GraphicsOverlay();
        sceneView.getGraphicsOverlays().add(routeOverlay);

        // Create a renderer for the route geometry
        SolidStrokeSymbolLayer strokeSymbolLayer = new SolidStrokeSymbolLayer(1, Color.YELLOW, new LinkedList<>(), StrokeSymbolLayer.LineStyle3D.TUBE);
        strokeSymbolLayer.setCapStyle(StrokeSymbolLayer.CapStyle.ROUND);
        ArrayList<SymbolLayer> layers = new ArrayList<>();
        layers.add(strokeSymbolLayer);
        MultilayerPolylineSymbol polylineSymbol = new MultilayerPolylineSymbol(layers);
        SimpleRenderer polylineRenderer = new SimpleRenderer(polylineSymbol);
        routeOverlay.setRenderer(polylineRenderer);

        // Create and start a location data source for use with the route tracker
        AndroidLocationDataSource trackingLocationDataSource = new AndroidLocationDataSource(this);
        trackingLocationDataSource.addLocationChangedListener(this::HandleUpdatedLocation);
        trackingLocationDataSource.startAsync();

        // Turn off the space effect and atmosphere effect rendering
        sceneView.setSpaceEffect(SpaceEffect.TRANSPARENT);
        sceneView.setAtmosphereEffect(AtmosphereEffect.NONE);

        // Start displaying the route in AR
        setRoute(routeResult.getRoutes().get(0));
    }

    private void setRoute(Route inputRoute) {
        // Clear any existing route lines
        routeOverlay.getGraphics().clear();

        // Create a graphic for the route geometry
        Graphic routeGraphic = new Graphic(inputRoute.getRouteGeometry());

        // Add the graphic to the overlay
        routeOverlay.getGraphics().add(routeGraphic);

        // Display the graphic 3 meters above the ground
        routeOverlay.getSceneProperties().setSurfacePlacement(LayerSceneProperties.SurfacePlacement.RELATIVE);
        routeOverlay.getSceneProperties().setAltitudeOffset(3);
    }

    private void HandleUpdatedLocation(LocationDataSource.LocationChangedEvent locationEvent) {
        if (routeTracker != null) {
            // Pass new location to the route tracker
            routeTracker.trackLocationAsync(locationEvent.getLocation());
        }
    }

    private void startTurnByTurn() {
        routeTracker = new RouteTracker(this, routeResult, 0);

        textToSpeech = new TextToSpeech(this, this, "com.google.android.tts");

        routeTracker.addNewVoiceGuidanceListener((RouteTracker.NewVoiceGuidanceEvent newVoiceGuidanceEvent) -> {
            // Get new guidance
            String newGuidance = newVoiceGuidanceEvent.getVoiceGuidance().getText();

            // Display and then read out the new guidance
            mHelpLabel.setText(newGuidance);
            speak(newGuidance);
        });

        routeTracker.addTrackingStatusChangedListener((RouteTracker.TrackingStatusChangedEvent trackingStatusChangedEvent) -> {
            // Display updated guidance
            mHelpLabel.setText(routeTracker.generateVoiceGuidance().getText());
        });

        // Only configure rerouting if it is supported by the route task
        if (routeTask.getRouteTaskInfo().isSupportsRerouting()) {
            // Add listeners for reroute events
            routeTracker.addRerouteStartedListener((RouteTracker.RerouteStartedEvent rerouteStartedEvent) -> mHelpLabel.setText(R.string.nav_rerouting_helptext));

            routeTracker.addRerouteCompletedListener((RouteTracker.RerouteCompletedEvent rerouteCompletedEvent) -> {
                // Get the new route
                Route newRoute = rerouteCompletedEvent.getTrackingStatus().getRouteResult().getRoutes().get(0);

                // If the route is different, use it
                if (!newRoute.equals(route)) {
                    setRoute(newRoute);
                }
            });

            // Enable rerouting
            routeTracker.enableReroutingAsync(routeTask, routeParameters, RouteTracker.ReroutingStrategy.TO_NEXT_STOP, true);
        }
    }

    @TargetApi(21)
    private void speak(String utterance) {
        // Read out directions
        textToSpeech.stop();
        textToSpeech.speak(utterance, TextToSpeech.QUEUE_FLUSH, null, null);
    }

    /**
     * Request read external storage for API level 23+.
     */
    private void requestPermissions() {
        // define permission to request
        String[] reqPermission = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA};
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
