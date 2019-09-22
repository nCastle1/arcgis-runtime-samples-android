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
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.esri.arcgisruntime.concurrent.ListenableFuture;
import com.esri.arcgisruntime.geometry.Point;
import com.esri.arcgisruntime.mapping.ArcGISMap;
import com.esri.arcgisruntime.mapping.Basemap;
import com.esri.arcgisruntime.mapping.view.DefaultMapViewOnTouchListener;
import com.esri.arcgisruntime.mapping.view.Graphic;
import com.esri.arcgisruntime.mapping.view.GraphicsOverlay;
import com.esri.arcgisruntime.mapping.view.LocationDisplay;
import com.esri.arcgisruntime.mapping.view.MapView;
import com.esri.arcgisruntime.security.AuthenticationManager;
import com.esri.arcgisruntime.security.DefaultAuthenticationChallengeHandler;
import com.esri.arcgisruntime.security.OAuthConfiguration;
import com.esri.arcgisruntime.symbology.SimpleLineSymbol;
import com.esri.arcgisruntime.symbology.SimpleMarkerSymbol;
import com.esri.arcgisruntime.symbology.SimpleRenderer;
import com.esri.arcgisruntime.tasks.networkanalysis.Route;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteParameters;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteResult;
import com.esri.arcgisruntime.tasks.networkanalysis.RouteTask;
import com.esri.arcgisruntime.tasks.networkanalysis.Stop;
import com.esri.arcgisruntime.tasks.networkanalysis.TravelMode;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

public class RoutePlanActivity extends AppCompatActivity {

    private static final String TAG = RoutePlanActivity.class.getSimpleName();

    private TextView mHelpLabel;
    private Button mNavigateButton;

    private MapView mMapView;

    private GraphicsOverlay mRouteOverlay;
    private GraphicsOverlay mStopsOverlay;

    private Point mStartPoint;
    private Point mEndPoint;

    private RouteTask mRouteTask;
    private Route mRoute;
    private RouteResult mRouteResult;
    private RouteParameters mRouteParameters;

    private String[] mRequestedPermission = new String[]
            {Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_route_plan);

        // Get references to the views defined in the layout
        mHelpLabel = findViewById(R.id.helpLabel);
        mMapView = findViewById(R.id.mapView);
        mNavigateButton = findViewById(R.id.navigateButton);

        // Request permissions before starting
        requestPermissions();
    }

    private void initialize() {
        // Create and show a map
        ArcGISMap map = new ArcGISMap(Basemap.createImagery());
        mMapView.setMap(map);

        // Enable location display
        LocationDisplay locationDisplay = mMapView.getLocationDisplay();

        // Listen to changes in the status of the location data source.
        locationDisplay.addDataSourceStatusChangedListener(dataSourceStatusChangedEvent -> {
            if (!dataSourceStatusChangedEvent.isStarted() || dataSourceStatusChangedEvent.getError() != null) {
                // Report other unknown failure types to the user - for example, location services may not
                // be enabled on the device.
                String message = String.format("Error in DataSourceStatusChangedListener: %s", dataSourceStatusChangedEvent
                        .getSource().getLocationDataSource().getError().getMessage());
                Toast.makeText(RoutePlanActivity.this, message, Toast.LENGTH_LONG).show();
                mHelpLabel.setText(getString(R.string.location_failed_error_message));
            }
        });

        // Enable autopan and start location display
        locationDisplay.setAutoPanMode(LocationDisplay.AutoPanMode.RECENTER);
        locationDisplay.startAsync();

        // Enable authentication; the routing service requires login and consumes credits
        enableAuth();

        // Create a route task
        mRouteTask = new RouteTask(this, "https://route.arcgis.com/arcgis/rest/services/World/Route/NAServer/Route_World");

        // Enable the user to specify a route once the service is ready
        mRouteTask.addDoneLoadingListener(() -> {
            if (mRouteTask.getLoadError() == null) {
                enableTapToPlace();
            } else {
                Toast.makeText(RoutePlanActivity.this, "Error connecting to route service.", Toast.LENGTH_SHORT).show();
                Log.e(TAG, mRouteTask.getLoadError().getMessage());
                mHelpLabel.setText(getString(R.string.route_failed_error_message));
            }
        });

        // Connect to the service and configure the route task
        mRouteTask.loadAsync();

        // Create and configure a graphics overlay for showing the calculated route
        mRouteOverlay = new GraphicsOverlay();
        SimpleLineSymbol routeSymbol = new SimpleLineSymbol(SimpleLineSymbol.Style.SOLID, Color.YELLOW, 1);
        SimpleRenderer routeRenderer = new SimpleRenderer(routeSymbol);
        mRouteOverlay.setRenderer(routeRenderer);

        // Create and configure an overlay for showing the stops
        mStopsOverlay = new GraphicsOverlay();
        SimpleMarkerSymbol stopSymbol = new SimpleMarkerSymbol(SimpleMarkerSymbol.Style.CIRCLE, Color.RED, 5);
        SimpleRenderer stopRenderer = new SimpleRenderer(stopSymbol);
        mStopsOverlay.setRenderer(stopRenderer);

        // Add the overlays to the map view
        mMapView.getGraphicsOverlays().add(mRouteOverlay);
        mMapView.getGraphicsOverlays().add(mStopsOverlay);
    }

    private void enableTapToPlace() {
        mHelpLabel.setText(R.string.place_start_message);

        mMapView.setOnTouchListener(new DefaultMapViewOnTouchListener(this, mMapView) {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e == null) {
                    return true;
                }
                if (mStartPoint == null) {
                    // Add start point
                    mStartPoint = RoutePlanActivity.this.mMapView
                            .screenToLocation(new android.graphics.Point(Math.round(e.getX()), Math.round(e.getY())));
                    Graphic graphic = new Graphic(mStartPoint);
                    mStopsOverlay.getGraphics().add(graphic);

                    // Update UI
                    mHelpLabel.setText(R.string.place_end_message);
                } else if (mEndPoint == null) {
                    // Add end point
                    mEndPoint = RoutePlanActivity.this.mMapView
                            .screenToLocation(new android.graphics.Point(Math.round(e.getX()), Math.round(e.getY())));
                    Graphic graphic = new Graphic(mEndPoint);
                    mStopsOverlay.getGraphics().add(graphic);

                    // Solve route
                    solveRoute();
                }

                return true;
            }
        });
    }

    private void enableNavigation() {
        mNavigateButton.setOnClickListener(v -> {
            // TODO - this seems bad, but route doesn't implement serializable so..
            // I'd just pass the start and end point, but I don't want to have to re-connect to the route service
            // and re-calculate in the AR activity
            ARNavigateActivity.route = mRoute;
            ARNavigateActivity.routeParameters = mRouteParameters;
            ARNavigateActivity.routeResult = mRouteResult;
            ARNavigateActivity.routeTask = mRouteTask;

            // Pass route to activity and navigate
            Intent myIntent = new Intent(RoutePlanActivity.this, ARNavigateActivity.class);
            Bundle bundle = new Bundle();
            startActivity(myIntent, bundle);
        });

        mNavigateButton.setVisibility(View.VISIBLE);
        mHelpLabel.setText(R.string.nav_ready_message);
    }

    private void enableAuth() {
        // See the authenticate with OAuth sample for more details

        // Enable auth for protected service
        // set up an oauth config with url to portal, a client id and a re-direct url
        // a custom client id for your app can be set on the ArcGIS for Developers dashboard under
        // Authentication --> Redirect URIs
        OAuthConfiguration oAuthConfiguration;
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

    private void solveRoute() {
        // Update UI
        mHelpLabel.setText(R.string.solving_route_message);

        final ListenableFuture<RouteParameters> listenableFuture = mRouteTask.createDefaultParametersAsync();
        listenableFuture.addDoneListener(() -> {
            try {
                if (listenableFuture.isDone()) {
                    mRouteParameters = listenableFuture.get();

                    // Parameters needed for navigation (happens in ARNavigate)
                    mRouteParameters.setReturnStops(true);
                    mRouteParameters.setReturnDirections(true);
                    mRouteParameters.setReturnRoutes(true);

                    // This sample is intended for navigating while walking only
                    List<TravelMode> travelModes = mRouteTask.getRouteTaskInfo().getTravelModes();
                    TravelMode walkingMode = travelModes.get(0);
                    // TODO - streams aren't allowed???
                    // TravelMode walkingMode = travelModes.stream().filter(tm -> tm.getName().contains("Walking")).findFirst();
                    for (TravelMode tm : travelModes) {
                        if (tm.getName().contains("Walking")) {
                            walkingMode = tm;
                            break;
                        }
                    }

                    mRouteParameters.setTravelMode(walkingMode);

                    // create stops
                    Stop stop1 = new Stop(mStartPoint);
                    Stop stop2 = new Stop(mEndPoint);

                    List<Stop> routeStops = new ArrayList<>();

                    // add stops
                    routeStops.add(stop1);
                    routeStops.add(stop2);
                    mRouteParameters.setStops(routeStops);

                    // set return directions as true to return turn-by-turn directions in the result of
                    mRouteParameters.setReturnDirections(true);

                    // solve
                    mRouteResult = mRouteTask.solveRouteAsync(mRouteParameters).get();
                    final List routes = mRouteResult.getRoutes();
                    mRoute = (Route) routes.get(0);
                    // create a mRouteSymbol graphic
                    Graphic routeGraphic = new Graphic(mRoute.getRouteGeometry());
                    // add mRouteSymbol graphic to the map
                    mRouteOverlay.getGraphics().add(routeGraphic);

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
        boolean permissionCheck1 = ContextCompat.checkSelfPermission(RoutePlanActivity.this, mRequestedPermission[0]) ==
                PackageManager.PERMISSION_GRANTED;
        boolean permissionCheck2 = ContextCompat.checkSelfPermission(RoutePlanActivity.this, mRequestedPermission[1]) ==
                PackageManager.PERMISSION_GRANTED;

        if (!(permissionCheck1 && permissionCheck2)) {
            // If permissions are not already granted, request permission from the user.
            ActivityCompat.requestPermissions(RoutePlanActivity.this, mRequestedPermission, 2);
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
    protected void onPause() {
        AuthenticationManager.CredentialCache.clear();
        AuthenticationManager.clearOAuthConfigurations();

        super.onPause();
    }
}
