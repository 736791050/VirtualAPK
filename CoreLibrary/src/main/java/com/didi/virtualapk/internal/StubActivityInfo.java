/*
 * Copyright (C) 2017 Beijing Didi Infinity Technology and Development Co.,Ltd. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.didi.virtualapk.internal;

import android.content.pm.ActivityInfo;
import android.content.res.TypedArray;
import android.util.Log;
import android.content.res.Resources.Theme;

import java.util.HashMap;

/**
 * Created by renyugang on 16/8/15.
 */
class StubActivityInfo {
    /**
     * stand 占坑两个（一个透明主题）
     */
    public static final int MAX_COUNT_STANDARD = 1;
    /**
     * single top
     * single task
     * single instance
     * 各占坑 8 个
     */
    public static final int MAX_COUNT_SINGLETOP = 8;
    public static final int MAX_COUNT_SINGLETASK = 8;
    public static final int MAX_COUNT_SINGLEINSTANCE = 8;

    /**
     * activity 名称定义，如：com.didi.virtualapk.core.A$1
     */
    public static final String corePackage = "com.didi.virtualapk.core";
    public static final String STUB_ACTIVITY_STANDARD = "%s.A$%d";
    public static final String STUB_ACTIVITY_SINGLETOP = "%s.B$%d";
    public static final String STUB_ACTIVITY_SINGLETASK = "%s.C$%d";
    public static final String STUB_ACTIVITY_SINGLEINSTANCE = "%s.D$%d";

    /**
     * 记录使用个数
     */
    public final int usedStandardStubActivity = 1;
    public int usedSingleTopStubActivity = 0;
    public int usedSingleTaskStubActivity = 0;
    public int usedSingleInstanceStubActivity = 0;

    /**
     * key: com.didi.virtualapk.MainActivity
     * value: com.didi.virtualapk.core.A$2
     */
    private HashMap<String, String> mCachedStubActivity = new HashMap<>();

    /**
     * 获取占坑 activity
     *
     * @param className  目标 activity
     * @param launchMode 启动模式
     * @param theme      主题样式
     * @return
     */
    public String getStubActivity(String className, int launchMode, Theme theme) {
        // 先找缓存
        String stubActivity = mCachedStubActivity.get(className);
        if (stubActivity != null) {
            return stubActivity;
        }

        // 获取透明和背景属性
        TypedArray array = theme.obtainStyledAttributes(new int[]{
                android.R.attr.windowIsTranslucent,
                android.R.attr.windowBackground
        });
        // 获取是否透明
        boolean windowIsTranslucent = array.getBoolean(0, false);
        array.recycle();
        if (Constants.DEBUG) {
            Log.d(Constants.TAG_PREFIX + "StubActivityInfo", "getStubActivity, is transparent theme ? " + windowIsTranslucent);
        }
        // 默认生成 com.didi.virtualapk.core.A$1
        stubActivity = String.format(STUB_ACTIVITY_STANDARD, corePackage, usedStandardStubActivity);
        switch (launchMode) {
            // standard 模式
            case ActivityInfo.LAUNCH_MULTIPLE: {
                // com.didi.virtualapk.core.A$1
                stubActivity = String.format(STUB_ACTIVITY_STANDARD, corePackage, usedStandardStubActivity);
                if (windowIsTranslucent) {
                    // com.didi.virtualapk.core.A$2
                    stubActivity = String.format(STUB_ACTIVITY_STANDARD, corePackage, 2);
                }
                break;
            }
            case ActivityInfo.LAUNCH_SINGLE_TOP: {
                usedSingleTopStubActivity = usedSingleTopStubActivity % MAX_COUNT_SINGLETOP + 1;
                // com.didi.virtualapk.core.B$2
                stubActivity = String.format(STUB_ACTIVITY_SINGLETOP, corePackage, usedSingleTopStubActivity);
                break;
            }
            case ActivityInfo.LAUNCH_SINGLE_TASK: {
                usedSingleTaskStubActivity = usedSingleTaskStubActivity % MAX_COUNT_SINGLETASK + 1;
                // com.didi.virtualapk.core.C$2
                stubActivity = String.format(STUB_ACTIVITY_SINGLETASK, corePackage, usedSingleTaskStubActivity);
                break;
            }
            case ActivityInfo.LAUNCH_SINGLE_INSTANCE: {
                usedSingleInstanceStubActivity = usedSingleInstanceStubActivity % MAX_COUNT_SINGLEINSTANCE + 1;
                // com.didi.virtualapk.core.A$2
                stubActivity = String.format(STUB_ACTIVITY_SINGLEINSTANCE, corePackage, usedSingleInstanceStubActivity);
                break;
            }

            default:
                break;
        }

        mCachedStubActivity.put(className, stubActivity);
        return stubActivity;
    }

}
