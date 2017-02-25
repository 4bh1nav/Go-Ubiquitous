/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.example.android.sunshine.sync;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.text.format.DateUtils;
import android.util.Log;

import com.example.android.sunshine.data.SunshinePreferences;
import com.example.android.sunshine.data.WeatherContract;
import com.example.android.sunshine.utilities.NetworkUtils;
import com.example.android.sunshine.utilities.NotificationUtils;
import com.example.android.sunshine.utilities.OpenWeatherJsonUtils;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.net.URL;

public class SunshineSyncTask {

    /**
     * Performs the network request for updated weather, parses the JSON from that request, and
     * inserts the new weather information into our ContentProvider. Will notify the user that new
     * weather has been loaded if the user hasn't been notified of the weather within the last day
     * AND they haven't disabled notifications in the preferences screen.
     *
     * @param context Used to access utility methods and the ContentResolver
     */

    private static final String TAG = "sunshine.sync.task";

    private static final String KEY_HIGH_TEMP = "max_temp";
    private static final String KEY_LOW_TEMP = "min_temp";
    private static final String KEY_WEATHER_ID = "weather_id";
    private static final String KEY_PATH = "/wearable";
    private static final String KEY_TIME = "current_time";

    synchronized public static void syncWeather(Context context) {

        try {
            /*
             * The getUrl method will return the URL that we need to get the forecast JSON for the
             * weather. It will decide whether to create a URL based off of the latitude and
             * longitude or off of a simple location as a String.
             */
            URL weatherRequestUrl = NetworkUtils.getUrl(context);

            /* Use the URL to retrieve the JSON */
            String jsonWeatherResponse = NetworkUtils.getResponseFromHttpUrl(weatherRequestUrl);

            /* Parse the JSON into a list of weather values */
            ContentValues[] weatherValues = OpenWeatherJsonUtils
                    .getWeatherContentValuesFromJson(context, jsonWeatherResponse);

            /*
             * In cases where our JSON contained an error code, getWeatherContentValuesFromJson
             * would have returned null. We need to check for those cases here to prevent any
             * NullPointerExceptions being thrown. We also have no reason to insert fresh data if
             * there isn't any to insert.
             */
            if (weatherValues != null && weatherValues.length != 0) {
                /* Get a handle on the ContentResolver to delete and insert data */
                ContentResolver sunshineContentResolver = context.getContentResolver();

                /* Delete old weather data because we don't need to keep multiple days' data */
                sunshineContentResolver.delete(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        null,
                        null);

                /* Insert our new weather data into Sunshine's ContentProvider */
                sunshineContentResolver.bulkInsert(
                        WeatherContract.WeatherEntry.CONTENT_URI,
                        weatherValues);


                //Sync with the watch face
                GoogleApiClient apiClient=new GoogleApiClient.Builder(context).addApi(Wearable.API).build();

                Cursor cursor=context.getContentResolver()
                        .query(WeatherContract.WeatherEntry.CONTENT_URI,
                                new String[]{WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
                                        WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                                        WeatherContract.WeatherEntry.COLUMN_WEATHER_ID},null,null,null);

                if(cursor!=null) {
                    cursor.moveToFirst();
                    ConnectionResult result = apiClient.blockingConnect();

                    if(result.isSuccess()) {
                        PutDataMapRequest putDataMapRequest = PutDataMapRequest.create(KEY_PATH);
                        DataMap dataMap = putDataMapRequest.getDataMap();

                        dataMap.putString(KEY_HIGH_TEMP, cursor.getString(cursor
                                .getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP)));

                        dataMap.putString(KEY_LOW_TEMP, cursor.getString(cursor.
                                getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP)));

                        dataMap.putInt(KEY_WEATHER_ID, Integer.parseInt(cursor.getString
                                (cursor.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID))));

                        dataMap.putLong(KEY_TIME, System.currentTimeMillis());


                        PutDataRequest putDataRequest = putDataMapRequest.asPutDataRequest();
                        Wearable.DataApi.putDataItem(apiClient, putDataRequest);

                        PendingResult<DataApi.DataItemResult> pendingResult = Wearable.DataApi
                                .putDataItem(apiClient, putDataRequest);

                        pendingResult.setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                            @Override
                            public void onResult(final DataApi.DataItemResult Result) {
                                if(Result.getStatus().isSuccess()) {
                                    Log.d(TAG, "Data item set: " + Result.getDataItem().getUri());
                                }
                            }
                        });
                    }

                    else {
                        Log.e(TAG, result.getErrorMessage());
                    }
                }
                else {
                    Log.e(TAG, " Cursor crash ");
                }

                /*
                 * Finally, after we insert data into the ContentProvider, determine whether or not
                 * we should notify the user that the weather has been refreshed.
                 */
                boolean notificationsEnabled = SunshinePreferences.areNotificationsEnabled(context);

                /*
                 * If the last notification was shown was more than 1 day ago, we want to send
                 * another notification to the user that the weather has been updated. Remember,
                 * it's important that you shouldn't spam your users with notifications.
                 */
                long timeSinceLastNotification = SunshinePreferences
                        .getEllapsedTimeSinceLastNotification(context);

                boolean oneDayPassedSinceLastNotification = false;

                if (timeSinceLastNotification >= DateUtils.DAY_IN_MILLIS) {
                    oneDayPassedSinceLastNotification = true;
                }

                /*
                 * We only want to show the notification if the user wants them shown and we
                 * haven't shown a notification in the past day.
                 */
                if (notificationsEnabled && oneDayPassedSinceLastNotification) {
                    NotificationUtils.notifyUserOfNewWeather(context);
                }

            /* If the code reaches this point, we have successfully performed our sync */

            }

        } catch (Exception e) {
            /* Server probably invalid */
            e.printStackTrace();
        }
    }
}