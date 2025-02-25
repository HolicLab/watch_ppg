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

import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import com.samsung.android.service.health.tracking.HealthTracker;
import com.samsung.android.service.health.tracking.HealthTrackingService;
import com.samsung.android.service.health.tracking.data.DataPoint;
import com.samsung.android.service.health.tracking.data.HealthTrackerType;
import com.samsung.android.service.health.tracking.data.PpgType;
import com.samsung.android.service.health.tracking.data.ValueKey;

import java.util.Set;
import java.util.List;
import java.util.EnumSet;
import java.util.ArrayList;

public class HeartRateListener {
    private final String TAG = "HeartRateListener";
    private TrackerDataSubject trackerDataSubject;

    private final HealthTracker.TrackerEventListener heartRateListener = new HealthTracker.TrackerEventListener() {
        // Samsung Health SDK로부터 심박수 데이터를 수신한다.
        @Override
        public void onDataReceived(@NonNull List<DataPoint> list) {
            for (DataPoint data : list) {
                updateHeartRate(data);
            }
        }

        @Override
        public void onFlushCompleted() {
            Log.i(TAG, "Flush completed");
        }

        // 수신 중 오류 발생시 호출
        @Override
        public void onError(HealthTracker.TrackerError trackerError) {
            Log.i(TAG, "Heart Rate Tracker error: " + trackerError.toString());
            if (trackerError == HealthTracker.TrackerError.PERMISSION_ERROR) {
                trackerDataSubject.notifyError(R.string.no_permission_message);
            }
            if (trackerError == HealthTracker.TrackerError.SDK_POLICY_ERROR) {
                trackerDataSubject.notifyError(R.string.sdk_policy_error);
            }
        }
    };
    private Handler heartRateHandler;
    private boolean isHandlerRunning = false;
    private HealthTracker ppgTracker;

    void setTrackerDataSubject(TrackerDataSubject trackerDataSubject) {
        this.trackerDataSubject = trackerDataSubject;
    }

    Set<PpgType> ppgTypeSet = EnumSet.of(PpgType.GREEN, PpgType.RED, PpgType.IR);

    void setPpgTracker(HealthTrackingService healthTrackingService) {
        ppgTracker = healthTrackingService.getHealthTracker(
                HealthTrackerType.PPG_CONTINUOUS,
                ppgTypeSet
        );
    }

    void setHeartRateHandler(Handler handler) {
        heartRateHandler = handler;
    }

    // ppg 측정을 시작
    void startTracker() {
        if (!isHandlerRunning) {
            heartRateHandler.post(() -> ppgTracker.setEventListener(heartRateListener));
            isHandlerRunning = true;
        }
    }

    // ppg 측정을 중지
    void stopTracker() {
        if (ppgTracker != null) {
            ppgTracker.unsetEventListener();
        }
        heartRateHandler.removeCallbacksAndMessages(null);
        isHandlerRunning = false;
    }

    void updateHeartRate(DataPoint data) {
        final int status = data.getValue(ValueKey.PpgSet.GREEN_STATUS);
        float ppgGreenValue = data.getValue(ValueKey.PpgSet.PPG_GREEN);
        trackerDataSubject.notifyHeartRateTrackerObservers(status, ppgGreenValue);
        Log.d("HeartRateTracker", "ppg값: " + ppgGreenValue + ", Status: " + status);
    }
}
