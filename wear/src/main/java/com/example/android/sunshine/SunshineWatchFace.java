/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataItemBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;

        private static final String TAG = "WatchFace_Service";

        private static final String KEY_HIGH_TEMP = "max_temp";
        private static final String KEY_LOW_TEMP = "min_temp";
        private static final String KEY_WEATHER_ID = "weather_id";
        private static final String KEY_PATH = "/wearable";
        private static final String KEY_TIME = "current_time";

        private GoogleApiClient googleApiClient;

        private int mWeatherId = 0;
        private String mMaxTemperature = "0";
        private String mMinTemperature = "0";
        private long mTimeStamp;

        private Paint mBackgroundPaint;
        private Calendar mCalendar;
        private Paint mHourPaint;
        private Paint mMinutePaint;
        private Paint mDatePaint;
        private Paint mColonPaint;
        private Paint mMeridiemPaint;
        private Paint mDividerLinePaint;
        private Paint mWeatherIconPaint;
        private Paint mLowTempPaint;
        private Paint mHighTempPaint;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormat();
                invalidate();
            }
        };

        private float mXOffset;
        private float mYOffset;
        private float mLineHeight;
        private String mAmString;
        private String mPmString;

        private Date mDate;
        private SimpleDateFormat mDayOfWeekFormat;
        private Bitmap weatherIcon;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();
            mYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mXOffset = resources.getDimension(R.dimen.digital_x_offset);

            int timeColor = resources.getColor(R.color.digital_time_color);
            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date_color));
            mHourPaint = createTextPaint(timeColor);
            mMinutePaint = createTextPaint(timeColor);
            mColonPaint = createTextPaint(timeColor);
            mMeridiemPaint = createTextPaint(resources.getColor(R.color.digital_Meridiem_color));

            mDividerLinePaint = new Paint();
            mDividerLinePaint.setColor(resources.getColor(R.color.digital_divider_color));

            mWeatherIconPaint = new Paint();
            mHighTempPaint = createTextPaint(resources.getColor(R.color.digital_high_temp_color));
            mLowTempPaint = createTextPaint(resources.getColor(R.color.digital_low_temp_color));

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            googleApiClient = new GoogleApiClient.Builder(getApplicationContext())
                    .addApi(Wearable.API).addOnConnectionFailedListener(this)
                    .addConnectionCallbacks(this).build();

            initFormat();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            releaseGoogleApiClient();
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        private void initFormat() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE, MMM d yyyy", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();
                googleApiClient.connect();

                // Update time date and zone in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormat();
                invalidate();
            } else {
                unregisterReceiver();
                releaseGoogleApiClient();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void requestWeatherUpdate() {
            Log.d(TAG, "Request Weather Update through Message API");

            Wearable.NodeApi.getConnectedNodes(googleApiClient)
                    .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                        @Override
                        public void onResult(NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                            final List<Node> nodes = getConnectedNodesResult.getNodes();

                            for (Node node : nodes) {
                                Wearable.MessageApi.sendMessage(googleApiClient
                                        , node.getId()
                                        , KEY_PATH
                                        , new byte[0]).setResultCallback(
                                        new ResultCallback<MessageApi.SendMessageResult>() {
                                            @Override
                                            public void onResult(MessageApi.SendMessageResult sendMessageResult) {
                                                if (sendMessageResult.getStatus().isSuccess()) {
                                                    Wearable.DataApi.getDataItems(googleApiClient).setResultCallback(OnConnectedResultCallback);
                                                    Log.d(TAG, "Message Sent Successfully");
                                                } else {
                                                    Log.d(TAG, "Message sending Failed");
                                                }
                                            }
                                        }
                                );
                            }
                        }
                    });
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }

            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }

            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);

            float MeridiemSize = resources.getDimension(isRound
                    ? R.dimen.digital_Meridiem_size_round : R.dimen.digital_Meridiem_size);

            float tempSize = resources.getDimension(isRound
                    ? R.dimen.digital_temp_size_round : R.dimen.digital_temp_size);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mMeridiemPaint.setTextSize(MeridiemSize);
            mHighTempPaint.setTextSize(tempSize);
            mLowTempPaint.setTextSize(tempSize);

        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);

            mMeridiemPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_Meridiem_color_ambient) :
                    getResources().getColor(R.color.digital_Meridiem_color));

            mDatePaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_date_color_ambient) :
                    getResources().getColor(R.color.digital_date_color));

            mDividerLinePaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_divider_color_ambient) :
                    getResources().getColor(R.color.digital_divider_color));

            mHighTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_high_temp_color_ambient) :
                    getResources().getColor(R.color.digital_high_temp_color));

            mLowTempPaint.setColor(inAmbientMode ?
                    getResources().getColor(R.color.digital_low_temp_color_ambient) :
                    getResources().getColor(R.color.digital_low_temp_color));


            if (mLowBitAmbient) {
                mDatePaint.setAntiAlias(!inAmbientMode);
                mHourPaint.setAntiAlias(!inAmbientMode);
                mMinutePaint.setAntiAlias(!inAmbientMode);
                mColonPaint.setAntiAlias(!inAmbientMode);
                mDividerLinePaint.setAntiAlias(!inAmbientMode);
                mWeatherIconPaint.setAntiAlias(!inAmbientMode);
                mHighTempPaint.setAntiAlias(!inAmbientMode);
                mLowTempPaint.setAntiAlias(!inAmbientMode);
            }

            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getMeridiemString(int Meridiem) {
            return Meridiem == Calendar.AM ? mAmString : mPmString;
        }

        private Bitmap getBitmapForWeatherCondition(int weatherId) {

            int weatherIconId = R.drawable.ic_clear;

            if (weatherId >= 200 && weatherId <= 232) {
                weatherIconId = R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                weatherIconId = R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                weatherIconId = R.drawable.ic_rain;
            } else if (weatherId == 511) {
                weatherIconId = R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                weatherIconId = R.drawable.ic_light_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                weatherIconId = R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                weatherIconId = R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                weatherIconId = R.drawable.ic_storm;
            } else if (weatherId == 800) {
                weatherIconId = R.drawable.ic_clear;
            } else if (weatherId == 801) {
                weatherIconId = R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                weatherIconId = R.drawable.ic_cloudy;
            }

            return BitmapFactory.decodeResource(getResources(), weatherIconId);
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {

            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFace.this);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            int TimeCenterAdjust = 40;
            int MeridiemAdjust = 20;
            String colonString =":";

            //onDraw hours
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }

                hourString = String.valueOf(hour);
            }

            canvas.drawText(hourString,
                    bounds.centerX() - (mHourPaint.measureText(hourString) + TimeCenterAdjust),
                    mYOffset,
                    mHourPaint);

            //onDraw Colon
            canvas.drawText(colonString,
                    bounds.centerX() - TimeCenterAdjust,
                    mYOffset ,
                    mColonPaint);

            //onDraw minutes
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString,
                    bounds.centerX() + mColonPaint.measureText(colonString) - TimeCenterAdjust,
                    mYOffset,
                    mMinutePaint);

            // In unmuted interactive mode, draw a second blinking colon followed by the seconds.
            // Otherwise, if we're in 12-hour mode, draw AM/PM
            if (!is24Hour) {
                canvas.drawText(getMeridiemString(mCalendar.get(Calendar.AM_PM)),
                        bounds.centerX() + mMinutePaint.measureText(minuteString) - MeridiemAdjust,
                        mYOffset,
                        mMeridiemPaint);
            }


            String formattedDate = mDayOfWeekFormat.format(mDate).toUpperCase();
            // Day of week
            canvas.drawText(formattedDate,
                    bounds.centerX() - (mDatePaint.measureText(formattedDate)) / 2,
                    mYOffset + mLineHeight,
                    mDatePaint);

            // onDraw horizontal divider
            int lineWidth = 70;
            canvas.drawLine(bounds.centerX() - lineWidth / 2,
                    mYOffset + (mLineHeight * 1.8f),
                    bounds.centerX() + lineWidth / 2,
                    mYOffset + (mLineHeight * 1.8f),
                    mDividerLinePaint);

            //onDraw weather Icon
            weatherIcon = getBitmapForWeatherCondition(mWeatherId);
            float xImage = (bounds.width() / 6 + (bounds.width() / 6 - weatherIcon.getHeight()) / 2);
            if (isInAmbientMode()) {
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                mWeatherIconPaint.setColorFilter(filter);
                canvas.drawBitmap(weatherIcon, xImage,
                        mYOffset + (mLineHeight * 2f),
                        mWeatherIconPaint);
            } else {
                mWeatherIconPaint.setColorFilter(null);
                canvas.drawBitmap(weatherIcon,
                        xImage,
                        mYOffset + (mLineHeight * 2f),
                        mWeatherIconPaint);
            }

            Log.d(TAG, "HERE IS HIGH TEMPERAtURE - " + mMaxTemperature);
            Log.d(TAG, "HERE IS LOW TEMPERAtURE - " + mMinTemperature);

            //onDraw weather detail
            String highTempText = String.format(getString(R.string.format_temperature),
                    Float.valueOf(mMaxTemperature));
            String lowTempText = String.format(getString(R.string.format_temperature),
                    Float.valueOf(mMinTemperature));

            canvas.drawText(highTempText,
                    bounds.centerX() - 40,
                    mYOffset + (mLineHeight * 3.2f),
                    mHighTempPaint);

            canvas.drawText(lowTempText,
                    bounds.centerX() + 35,
                    mYOffset + (mLineHeight * 3.2f),
                    mLowTempPaint);
        }

        public void processItem(DataItem dataItem) {
            if(KEY_PATH.equals(dataItem.getUri().getPath())){
                DataMap map = DataMapItem.fromDataItem(dataItem).getDataMap();
                mMaxTemperature = map.getString(KEY_HIGH_TEMP);
                mMinTemperature = map.getString(KEY_LOW_TEMP);
                mWeatherId = map.getInt(KEY_WEATHER_ID);
                mTimeStamp = map.getLong(KEY_TIME);

                Log.d(TAG, "here is high temperature - " + mMaxTemperature);
                Log.d(TAG, "here is low temperature - " + mMinTemperature);
                Log.d(TAG, "here is weather id temperature - " + mWeatherId);
                Log.d(TAG, "here is the time stamp - " + mTimeStamp);

                invalidate();
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.e(TAG, String.valueOf(connectionResult.getErrorCode()));
        }

        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d(TAG, "connected GoogleAPI");
            requestWeatherUpdate();
            Wearable.DataApi.addListener(googleApiClient, onDataChangedListener);
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d(TAG, "Suspended GoogleAPI");
        }

        private void releaseGoogleApiClient() {
            if (googleApiClient != null && googleApiClient.isConnected()) {
                Wearable.DataApi.removeListener(googleApiClient, onDataChangedListener);
                googleApiClient.disconnect();
            }
        }

        DataApi.DataListener onDataChangedListener=new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEventBuffer) {

                Log.d(TAG, "On Data Changed Count - " + dataEventBuffer.getCount());
                for(DataEvent dataEvent:dataEventBuffer)
                {
                    Log.d(TAG, "On Data Changed Event Type - " + dataEvent.getType());
                    if(dataEvent.getType()==DataEvent.TYPE_CHANGED) {
                        DataItem dataItem = dataEvent.getDataItem();
                        Log.d(TAG, "On Data Changed Data Event Details - " + dataEvent.toString());
                        processItem(dataItem);
                    }
                }

                dataEventBuffer.release();
                invalidate();
            }
        };

        ResultCallback<DataItemBuffer> OnConnectedResultCallback = new ResultCallback<DataItemBuffer>() {
            @Override
            public void onResult(DataItemBuffer dataItems) {
                Log.d(TAG, "On Connected Result Callback " + dataItems.getCount());
                for(DataItem item:dataItems)
                {
                    processItem(item);

                }

                dataItems.release();
                invalidate();
            }
        };
    }
}