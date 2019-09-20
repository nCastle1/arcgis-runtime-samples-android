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

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.geometry.Geometry;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.geometry.SpatialReferences;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.Viewpoint;
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.LocationDisplay;
import com.esri.arcgisruntime.mapping.view.MapView;

import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.security.AuthenticationManager;
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler;
import com.esri.arcgisruntime.security.OAuthConfiguration;
import com.esri.arcgisruntime.symbology.SimpleLineSymbol;
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.symbology.Symbol;
import com.esri.arcgisruntime.tasks.networkanalysis.DirectionManeuver;
import com.esri.arcgisruntime.tasks.networkanalysis.Route;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask;
import com.esri.arcgisruntime.tasks.networkanalysis.Stop;
import com.esri.arcgisruntime.tasks.networkanalysis.TravelMode;

import java.io.Serializable;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

public class RoutePlanActivity extends AppCompatActivity {

    private static final String TAG = RoutePlanActivity.class.getSimpleName();

    private TextView helpLabel;
    private Button navigateButton;
    private MapView mapview;
    private LocationDisplay locationDisplay;

    private GraphicsOverlay routeOverlay;
    private GraphicsOverlay stopsOverlay;

    private Point startPoint;
    private Point endPoint;

    private RouteTask routeTask;
    private Route route;
    private RouteResult routeResult;
    private RouteParameters routeParameters;

    private String[] reqPermissions = new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission
            .ACCESS_COARSE_LOCATION};
    private int requestCode = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_plan);

        helpLabel = findViewById(R.id.helpLabel);
        mapview = findViewById(R.id.mapView);
        navigateButton = findViewById(R.id.navigateButton);

        requestPermissions();
    }

    private void initialize(){
        ArcGISMap map = new ArcGISMap(Basemap.createImagery());
        // set the map to be displayed in this view
        mapview.setMap(map);

        locationDisplay = mapview.getLocationDisplay();

        // Listen to changes in the status of the location data source.
        locationDisplay.addDataSourceStatusChangedListener(dataSourceStatusChangedEvent -> {

            // If LocationDisplay started OK, then continue.
            if (!dataSourceStatusChangedEvent.isStarted() || dataSourceStatusChangedEvent.getError() != null){
                // Report other unknown failure types to the user - for example, location services may not
                // be enabled on the device.
                String message = String.format("Error in DataSourceStatusChangedListener: %s", dataSourceStatusChangedEvent
                        .getSource().getLocationDataSource().getError().getMessage());
                Toast.makeText(RoutePlanActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });

        locationDisplay.setAutoPanMode(LocationDisplay.AutoPanMode.RECENTER);
        locationDisplay.startAsync();

        enableAuth();

        routeTask = new RouteTask(this, "https://route.arcgis.com/arcgis/rest/services/World/Route/NAServer/Route_World");
        routeTask.addDoneLoadingListener(()->{
            if (routeTask.getLoadError() == null){
                enableTapToPlace();
                helpLabel.setText(R.string.place_start_message);
            } else {
                Toast.makeText(RoutePlanActivity.this, "Error connecting to route service.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, routeTask.getLoadError().getMessage());
            }
        });
        routeTask.loadAsync();

        routeOverlay = new GraphicsOverlay();
        stopsOverlay = new GraphicsOverlay();

        SimpleLineSymbol routeSymbol = new SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.YELLOW, 1);
        SimpleRenderer routeRenderer = new SimpleRenderer(routeSymbol);
        routeOverlay.setRenderer(routeRenderer);

        SimpleMarkerSymbol stopSymbol = new SimpleMarkerSymbol(SimpleMarkerSymbol.Style.CIRCLE, Color.RED, 5);
        SimpleRenderer stopRenderer = new SimpleRenderer(stopSymbol);
        stopsOverlay.setRenderer(stopRenderer);

        mapview.getGraphicsOverlays().add(routeOverlay);
        mapview.getGraphicsOverlays().add(stopsOverlay);
    }

    private void enableTapToPlace(){
        mapview.setOnTouchListener(new DefaultMapViewOnTouchListener(this, mapview) {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e == null) { return true; }
                if (startPoint == null){
                    // Add start point
                    startPoint = mapview
                            .screenToLocation(new android.graphics.Point(Math.round(e.getX()), Math.round(e.getY())));
                    Graphic graphic = new Graphic(startPoint);
                    stopsOverlay.getGraphics().add(graphic);

                    // Update UI
                    helpLabel.setText(R.string.place_end_message);
                } else if (endPoint == null) {
                    // Add end point
                    endPoint = mapview
                            .screenToLocation(new android.graphics.Point(Math.round(e.getX()), Math.round(e.getY())));
                    Graphic graphic = new Graphic(endPoint);
                    stopsOverlay.getGraphics().add(graphic);

                    // Solve route
                    solveRoute();
                }

                return true;
            }
        });
    }

    private void enableNavigation(){
        navigateButton.setOnClickListener(v -> {
            // TODO - this seems bad, but route doesn't implement serializable so..
            ARNavigateActivity.route = route;
            ARNavigateActivity.routeParameters = routeParameters;
            ARNavigateActivity.routeResult = routeResult;
            ARNavigateActivity.routeTask = routeTask;
            // Pass route to activity and navigate
            Intent myIntent = new Intent(RoutePlanActivity.this, ARNavigateActivity.class);
            Bundle bundle = new Bundle();
            startActivity(myIntent, bundle);
        });

        navigateButton.setVisibility(View.VISIBLE);
        helpLabel.setText(R.string.nav_ready_message);
    }

    private void enableAuth(){
        // Enable auth for protected service
        // set up an oauth config with url to portal, a client id and a re-direct url
        // a custom client id for your app can be set on the ArcGIS for Developers dashboard under
        // Authentication --> Redirect URIs
        OAuthConfiguration oAuthConfiguration = null;
        try {
            oAuthConfiguration = new OAuthConfiguration(getString(R.string.portal_url),
                    getString(R.string.oauth_client_id),
                    getString(R.string.oauth_redirect_uri) + "://" + getString(R.string.oauth_redirect_host));
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return;
        }

        // setup AuthenticationManager to handle auth challenges
        DefaultAuthenticationChallengeHandler defaultAuthenticationChallengeHandler = new DefaultAuthenticationChallengeHandler(
                this);

        // use the DefaultChallengeHandler to handle authentication challenges
        AuthenticationManager.setAuthenticationChallengeHandler(defaultAuthenticationChallengeHandler);

        // add an OAuth configuration
        // NOTE: you must add the DefaultOAuthIntentReceiver Activity to the app's manifest to handle starting a browser
        AuthenticationManager.addOAuthConfiguration(oAuthConfiguration);
    }

    private void solveRoute(){
        // Update UI
        helpLabel.setText(R.string.solving_route_message);

        final ListenableFuture<RouteParameters> listenableFuture = routeTask.createDefaultParametersAsync();
        listenableFuture.addDoneListener(() -> {
            try {
                if (listenableFuture.isDone()) {
                    routeParameters = listenableFuture.get();

                    // Parameters needed for navigation (happens in ARNavigate)
                    routeParameters.setReturnStops(true);
                    routeParameters.setReturnDirections(true);
                    routeParameters.setReturnRoutes(true);

                    // this scenario is intended for directions while walking
                    List<TravelMode> travelModes = routeTask.getRouteTaskInfo().getTravelModes();
                    TravelMode walkingMode = travelModes.get(0);
                    // TODO - streams aren't allowed???
                    // TravelMode walkingMode = travelModes.stream().filter(tm -> tm.getName().contains("Walking")).findFirst();
                    for (TravelMode tm : travelModes){
                        if (tm.getName().contains("Walking")){
                            walkingMode = tm;
                            break;
                        }
                    }

                    routeParameters.setTravelMode(walkingMode);

                    // create stops
                    Stop stop1 = new Stop(startPoint);
                    Stop stop2 = new Stop(endPoint);

                    List<Stop> routeStops = new ArrayList<>();
                    // add stops
                    routeStops.add(stop1);
                    routeStops.add(stop2);
                    routeParameters.setStops(routeStops);

                    // set return directions as true to return turn-by-turn directions in the result of
                    routeParameters.setReturnDirections(true);

                    // solve
                    routeResult = routeTask.solveRouteAsync(routeParameters).get();
                    final List routes = routeResult.getRoutes();
                    route = (Route) routes.get(0);
                    // create a mRouteSymbol graphic
                    Graphic routeGraphic = new Graphic(route.getRouteGeometry());
                    // add mRouteSymbol graphic to the map
                    routeOverlay.getGraphics().add(routeGraphic);

                    enableNavigation();
                }
            } catch (Exception e) {
                Log.e(TAG, e.getMessage());
            }
        });
    }

    private void requestPermissions() {
        // define permission to request
        // If an error is found, handle the failure to start.
        // Check permissions to see if failure may be due to lack of permissions.
        boolean permissionCheck1 = ContextCompat.checkSelfPermission(RoutePlanActivity.this, reqPermissions[0]) ==
                PackageManager.PERMISSION_GRANTED;
        boolean permissionCheck2 = ContextCompat.checkSelfPermission(RoutePlanActivity.this, reqPermissions[1]) ==
                PackageManager.PERMISSION_GRANTED;

        if (!(permissionCheck1 && permissionCheck2)) {
            // If permissions are not already granted, request permission from the user.
            ActivityCompat.requestPermissions(RoutePlanActivity.this, reqPermissions, requestCode);
        } else {
            initialize();
        }
    }

    /**
     * Handle the permissions request response.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initialize();
        } else {
            // report to user that permission was denied
            Toast.makeText(this, getString(R.string.navigate_ar_permission_denied), Toast.LENGTH_SHORT).show();
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    @Override
    protected void onPause(){
        AuthenticationManager.CredentialCache.clear();
        AuthenticationManager.clearOAuthConfigurations();

        super.onPause();
    }
}
