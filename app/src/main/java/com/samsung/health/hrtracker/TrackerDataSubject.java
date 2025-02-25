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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// 옵저버 패턴을 사용하여 심박수 데이터의 변경 사항을 옵저버들에게 알려주는 클래스
public class TrackerDataSubject {

    private final List<TrackerObserver> trackerObservers = Collections.synchronizedList(new ArrayList<>());

    // 옵저버 등록
    public void addObserver(TrackerObserver observer) {
        trackerObservers.add(observer);
    }

    // 옵저버 제거
    public void removeObserver(TrackerObserver observer) {
        trackerObservers.remove(observer);
    }

    // 심박수 데이터가 변경되면 등록된 옵저버들에게 알림을 보낸다.
    public void notifyHeartRateTrackerObservers(int status, float ppgGreenValue) {
        trackerObservers.forEach(observer -> observer.onHeartRateChanged(status, ppgGreenValue));
    }

    //오류 발생시 옵저버에게 알림 보내기
    public void notifyError(int errorResourceId) {
        trackerObservers.forEach(observer -> observer.notifyTrackerError(errorResourceId));
    }
}
