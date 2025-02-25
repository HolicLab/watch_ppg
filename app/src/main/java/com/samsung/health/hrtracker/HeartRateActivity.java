/*
 * Copyright 2023 Samsung Electronics Co., Ltd. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.samsung.health.hrtracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;

import com.samsung.health.hrtracker.databinding.ActivityHeartrateBinding;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.text.SimpleDateFormat; // 시간 형식 지정을 위해
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

// OkHttp 관련 imports
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.logging.HttpLoggingInterceptor; // 로깅 인터셉터 import
import java.io.IOException;
import com.google.gson.Gson; // Gson import

public class HeartRateActivity extends Activity implements TrackerObserver, SensorEventListener {
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean initialMeasurement = new AtomicBoolean(true);
    private final AtomicBoolean deviceWorn = new AtomicBoolean(false);

    private final AtomicBoolean isMeasurementRunning = new AtomicBoolean(false);
    private TrackerDataSubject trackerDataSubject = null;
    private HeartRateListener heartRateListener = null;
    private ConnectionManager connectionManager = null;
    private SensorManager mSensorManager;
    private Sensor offBodySensor;
    private ActivityHeartrateBinding activityHeartRateBinding = null;
    private OkHttpClient okHttpClient;
    private static final MediaType MEDIA_TYPE_JSON = MediaType.parse("application/json; charset=utf-8");

    // 데이터 버퍼링 관련 변수
    private final List<PpgDataItem> dataBuffer = new ArrayList<>();
    private static final long BUFFER_INTERVAL_MS = 5000; // 버퍼링 간격 (밀리초)
    private final Handler bufferHandler = new Handler(Looper.getMainLooper()); // UI 스레드 핸들러
    // SimpleDateFormat을 멤버 변수로 선언
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    private final Runnable bufferRunnable = new Runnable() { // Runnable 객체를 멤버 변수로
        @Override
        public void run() {
            sendBufferedData();  // 버퍼에 있는 데이터를 전송하는 메서드
            bufferHandler.postDelayed(this, BUFFER_INTERVAL_MS); // 다음 실행 예약
        }
    };

    private final ConnectionObserver connectionObserver = new ConnectionObserver() {
        @Override
        public void onConnectionResult(boolean isConnected) {
            connected.set(isConnected);
        }

        @Override
        public void onPpgAvailability(boolean isAvailable) {
            if (isAvailable) {
                // PPG 사용 가능: HeartRateListener 초기화, UI 활성화
                heartRateListener = new HeartRateListener();
                heartRateListener.setTrackerDataSubject(trackerDataSubject);
                connectionManager.initPpg(heartRateListener);

                // UI 업데이트는 UI 스레드에서 해야 합니다.
                runOnUiThread(() -> {
                    activityHeartRateBinding.butStart.setEnabled(true); // "Start" 버튼 활성화 (예시)
                    // 다른 UI 요소 업데이트 (필요한 경우)
                });

            } else {
                // PPG 사용 불가능: UI 비활성화, 메시지 표시
                runOnUiThread(() -> {
                    activityHeartRateBinding.butStart.setEnabled(false); // "Start" 버튼 비활성화 (예시)
                    Toast.makeText(HeartRateActivity.this, "Samsung Health Tracking Service를 사용할 수 없습니다.", Toast.LENGTH_LONG).show();
                    // 또는, 더 사용자 친화적인 대화 상자나 다른 UI 요소를 사용하여 안내.
                });
            }
        }
    };

    // 액티비티 생성시 호출, UI 초기화, 센서 권한 확인, SDK 연결
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        activityHeartRateBinding = ActivityHeartrateBinding.inflate(getLayoutInflater());
        setContentView(activityHeartRateBinding.getRoot());

        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) == PackageManager.PERMISSION_DENIED) {
            requestPermissions(new String[]{Manifest.permission.BODY_SENSORS}, 0);
        }
        trackerDataSubject = new TrackerDataSubject();
        trackerDataSubject.addObserver(this);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        offBodySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LOW_LATENCY_OFFBODY_DETECT);
        if (offBodySensor == null) {
            prepareAlertWindow(R.string.no_off_body_sensor_title, R.string.no_off_body_sensor_message).create().show();
        }
        createConnectionManager();

        // OkHttpClient 초기화
        setupOkHttpClient();
    }

    private void setupOkHttpClient() {
        // (선택 사항) 로깅 인터셉터 추가 (디버깅 시 유용)
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY); // 요청/응답 본문 로깅

        okHttpClient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor) // 인터셉터 추가
                .addInterceptor(new RetryInterceptor(2))
                .connectTimeout(10, TimeUnit.SECONDS) // 연결 타임아웃 (10초)
                .readTimeout(30, TimeUnit.SECONDS)    // 읽기 타임아웃 (30초)
                .writeTimeout(30, TimeUnit.SECONDS)   // 쓰기 타임아웃(30초)
                .build();
    }

    // 액티비티 재개시 호출, off-body 센서 리스너를 등록
    @Override
    protected void onResume() {
        super.onResume();
        if (offBodySensor != null) {
            mSensorManager.registerListener(HeartRateActivity.this,
                    offBodySensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        startBuffering();
    }

    // 액티비티 일시 중지시 호출, Off-body 센서 리스너를 해제
    @Override
    protected void onPause() {
        super.onPause();
        if (offBodySensor != null) {
            mSensorManager.unregisterListener(this);
        }
        stopBuffering();
    }

    // SDK 연결을 초기화하고 관리하는 역할
    void createConnectionManager() {
        try {
            connectionManager = new ConnectionManager(connectionObserver);
            connectionManager.connect(this, getApplicationContext());
        } catch (Throwable t) {
            final String errMsg = t.getMessage() == null ? "Error in creating connection manager" : t.getMessage();
            Log.e(getString(R.string.app_name), errMsg);
        }
    }

    // 액티비티 소멸될 때 호출되며, SDK 연결을 해제
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (connected.get()) {
            connectionManager.disconnect();
        }
        trackerDataSubject.removeObserver(this);
        if (okHttpClient != null) {
            // OkHttpClient 정리 (중요)
            okHttpClient.dispatcher().executorService().shutdown(); // Dispatcher 종료
            okHttpClient.connectionPool().evictAll(); // Connection pool 정리
        }
    }

    // 측정 시작/중지 버튼 클릭 이벤트 처리
    public void onMeasurementButtonClick(View view) {
        if(isMeasurementRunning.get()) {
            endMeasurement();
        } else {
            startMeasurement();
        }
    }

    // 심박수 측정을 시작
    public void startMeasurement() {
        if(!connected.get()) {
            Toast.makeText(this, R.string.no_connection_message, Toast.LENGTH_SHORT).show();
            return;
        }
        // ConnectionManager의 isPpgAvailable() 메서드를 사용하도록 수정
        if (!connectionManager.isPpgAvailable(connectionManager.healthTrackingService)) {
            prepareAlertWindow(R.string.no_ppg_rate_available_title, R.string.no_ppg_rate_available_message).create().show();
            return;
        }
        if(!deviceWorn.get()) {
            Toast.makeText(this, R.string.device_not_worn, Toast.LENGTH_SHORT).show();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runOnUiThread(() -> {
                    activityHeartRateBinding.txtPpgGreenValue.setText(R.string.status_default_value);
                    activityHeartRateBinding.txtPpgGreenStatusValue.setText(R.string.ppg_rate_default_value);
                    activityHeartRateBinding.butStart.setText(R.string.button_stop);
                    activityHeartRateBinding.pgMeasurement.setVisibility(View.VISIBLE);
                });
        initialMeasurement.set(true);
        heartRateListener.startTracker();
        isMeasurementRunning.set(true);
        startBuffering();
    }

    // 버퍼링 시작 (onResume 또는 측정 시작 시 호출)
    private void startBuffering() {
        bufferHandler.postDelayed(bufferRunnable, BUFFER_INTERVAL_MS);
    }
    // 버퍼링 중지 (onPause 또는 측정 종료 시 호출)
    private void stopBuffering() {
        bufferHandler.removeCallbacks(bufferRunnable);
    }
    // 버퍼에 데이터를 추가하고, 꽉 찼을 경우 서버로 전송
    private void addToBuffer(float ppgGreenValue, int status) {
        String currentTime = dateFormat.format(new Date());
        dataBuffer.add(new PpgDataItem(ppgGreenValue, status, currentTime));
    }

    // 심박수 데이터가 변경될 때 호출, UI를 업데이트한다.
    @Override
    public void onHeartRateChanged(int status, float ppgGreenValue) {
        runOnUiThread(() -> {
            if (initialMeasurement.get()) {
                activityHeartRateBinding.pgMeasurement.setVisibility(View.INVISIBLE);
                initialMeasurement.set(false);
            }
            activityHeartRateBinding.txtPpgGreenValue.setText(
                    String.format(Locale.getDefault(), "%.2f", ppgGreenValue));
            activityHeartRateBinding.txtPpgGreenStatusValue.setText(
                    String.format(Locale.getDefault(), "%d", status));

            // 서버로 PPG 데이터 전송 (OkHttp 사용)
            addToBuffer(ppgGreenValue, status);
        });
    }

    // 버퍼에 있는 데이터를 서버로 전송하고 버퍼를 비움
    private synchronized void sendBufferedData() {
        if (dataBuffer.isEmpty()) {
            return; // 버퍼가 비어있으면 아무것도 하지 않음
        }

        // 세션 ID 생성 (하드코딩)
        String sessionId = "01JMY6H732HBAV1XZ4KP9YAQBS";
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1c2VyX2lkIjoiMDFKTVdCUUM2M0c0SlFQUDhTWDYzM1dGV1MiLCJyb2xlIjoiVVNFUiIsImV4cCI6MTc0MDUxMzAxM30.VpMftsSm4qGYs20xdQXPoyWyqhVOqxP76fIG14VjtOs";

        final List<PpgDataItem> dataToSend = new ArrayList<>(dataBuffer); // final 변수에 복사
        dataBuffer.clear();
        PpgDataWrapper dataWrapper = new PpgDataWrapper(sessionId, dataToSend);
        String jsonData = new Gson().toJson(dataWrapper);
        RequestBody requestBody = RequestBody.create(jsonData, MEDIA_TYPE_JSON);

        // Request 객체 생성
        Request request = new Request.Builder()
                .url("https://youngwon.site/study/data") // 실제 서버 URL
                .addHeader("Authorization", "Bearer " + token)
                .post(requestBody)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                // 네트워크 오류 처리 (UI 스레드에서 처리)
                runOnUiThread(() -> {
                    Toast.makeText(HeartRateActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    Log.e("HeartRateActivity", "OkHttp request failed: " + e.getMessage(), e);
                });
            }
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (response.isSuccessful()) {
                        // dataBuffer.clear(); // 여기서 제거! 위에서 이미 clear함.

                        // 성공 (200 OK 등)
                        ResponseBody responseBody = response.body();
                        String responseString = responseBody != null ? responseBody.string() : "";
                        Log.d("HeartRateActivity", "OkHttp response successful: " + responseString);
                        runOnUiThread(()-> Toast.makeText(HeartRateActivity.this, "Data sent successfully!", Toast.LENGTH_SHORT).show());
                    } else {
                        // 서버 에러 (4xx, 5xx 등)
                        String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                        Log.e("HeartRateActivity", "OkHttp response failed: " + response.code() + ", " + response.message() + ", Error Body: " + errorBody);
                        runOnUiThread(() -> Toast.makeText(HeartRateActivity.this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show());
                    }
                } catch (IOException e) {
                    Log.e("HeartRateActivity", "Error reading response body", e);
                    runOnUiThread(() -> Toast.makeText(HeartRateActivity.this, "Error processing response", Toast.LENGTH_SHORT).show());
                } finally {
                    if (response.body() != null) {
                        response.close();
                    }
                }
            }
        });
    }

    // 심박수 측정을 중지한다.
    void endMeasurement() {
        if (heartRateListener != null) {
            heartRateListener.stopTracker();
            isMeasurementRunning.set(false);
            runOnUiThread(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                activityHeartRateBinding.pgMeasurement.setVisibility(View.INVISIBLE);
                activityHeartRateBinding.butStart.setText(R.string.button_start);
            });
        }
        stopBuffering(); // 측정이 종료될 때 버퍼링 중지
        sendBufferedData();
    }

    // 오류 발생시 호출됨
    @Override
    public void notifyTrackerError(int errorResourceId) {
        endMeasurement();
        runOnUiThread(() -> {
            if (errorResourceId == R.string.no_permission_message) {
                final AlertDialog.Builder alertBuilder = prepareAlertWindow(R.string.no_permission_title, R.string.no_permission_message);
                alertBuilder.setPositiveButton("Settings", (dialog, which) -> openAppSettings(getPackageName()));
                alertBuilder.setNegativeButton("Not now", null);
                final AlertDialog alertDialog = alertBuilder.create();
                alertDialog.show();
            }
            if (errorResourceId == R.string.sdk_policy_error) {
                Toast.makeText(this, R.string.sdk_policy_error, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Off-body 센서 데이터가 변경될 때 호출됨, 워치 착용 상태를 확인한다.
    @Override
    public void onSensorChanged(@NonNull SensorEvent sensorEvent) {
        final float offBodyDataFloat = sensorEvent.values[0];
        final int offBodyData = Math.round(offBodyDataFloat);
        if (offBodyData == OffBodyStatus.DEVICE_ON_BODY) {
            deviceWorn.set(true);
        }
        if (offBodyData == OffBodyStatus.DEVICE_OFF_BODY) {
            deviceWorn.set(false);
            if(isMeasurementRunning.get()) {
                endMeasurement();
                Toast.makeText(this, R.string.device_removed_during_measurement, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    void openAppSettings(String packageName) {
        final Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        final Uri uri = Uri.fromParts("package", packageName, null);
        intent.setData(uri);
        startActivity(intent);
    }

    AlertDialog.Builder prepareAlertWindow(int titleResourceId, int messageResourceId) {
        final AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
        alertBuilder.setMessage(messageResourceId);
        alertBuilder.setTitle(titleResourceId);
        return alertBuilder;
    }
}
