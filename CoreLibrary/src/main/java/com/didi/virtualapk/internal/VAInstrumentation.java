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

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Application;
import android.app.Fragment;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PersistableBundle;
import android.util.Log;

import com.didi.virtualapk.PluginManager;
import com.didi.virtualapk.delegate.StubActivity;
import com.didi.virtualapk.internal.utils.PluginUtil;
import com.didi.virtualapk.utils.Reflector;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;


/**
 * Created by renyugang on 16/8/10.
 * 代理 Instrumentation，在 execStartActivity 方法中，替换要启动的 activity 为占坑 activity
 */
public class VAInstrumentation extends Instrumentation implements Handler.Callback {
    public static final String TAG = Constants.TAG_PREFIX + "VAInstrumentation";
    public static final int LAUNCH_ACTIVITY  = 100;

    protected Instrumentation mBase;
    
    protected final ArrayList<WeakReference<Activity>> mActivities = new ArrayList<>();

    protected PluginManager mPluginManager;

    public VAInstrumentation(PluginManager pluginManager, Instrumentation base) {
        this.mPluginManager = pluginManager;
        this.mBase = base;
    }

    @Override
    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token, Activity target, Intent intent, int requestCode) {
        injectIntent(intent);
        return mBase.execStartActivity(who, contextThread, token, target, intent, requestCode);
    }

    @Override
    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token, Activity target, Intent intent, int requestCode, Bundle options) {
        injectIntent(intent);
        return mBase.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
    }

    @Override
    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token, Fragment target, Intent intent, int requestCode, Bundle options) {
        injectIntent(intent);
        return mBase.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
    }

    @Override
    public ActivityResult execStartActivity(Context who, IBinder contextThread, IBinder token, String target, Intent intent, int requestCode, Bundle options) {
        injectIntent(intent);
        return mBase.execStartActivity(who, contextThread, token, target, intent, requestCode, options);
    }
    
    protected void injectIntent(Intent intent) {
        // 保证 component 被设置
        mPluginManager.getComponentsHandler().transformIntentToExplicitAsNeeded(intent);
        // null component is an implicitly intent
        if (intent.getComponent() != null) {
            Log.i(TAG, String.format("execStartActivity[%s : %s]", intent.getComponent().getPackageName(), intent.getComponent().getClassName()));
            // resolve intent with Stub Activity if needed
            // 将真正需要打开的 activity 信息存储到 category
            // 替换成占坑 activity
            this.mPluginManager.getComponentsHandler().markIntentIfNeeded(intent);
        }
    }

    /**
     * 创建真正要打开的 activity, 并设置 resources
     * @param cl
     * @param className
     * @param intent
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws ClassNotFoundException
     */
    @Override
    public Activity newActivity(ClassLoader cl, String className, Intent intent) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        try {
            // 如果能找到说明是 host activity
            cl.loadClass(className);
            // com.didi.virtualapk.MainActivity
            Log.i(TAG, String.format("newActivity[%s]", className));
            
        } catch (ClassNotFoundException e) {
            // 从 intent 中解析出真正要创建的 activity
            ComponentName component = PluginUtil.getComponent(intent);
            
            if (component == null) {
                // 如果不是插件 actvivity 走 base
                return newActivity(mBase.newActivity(cl, className, intent));
            }

            // 真正要打开的 activity
            String targetClassName = component.getClassName();
            // newActivity[com.didi.virtualapk.core.A$1 : com.didi.virtualapk.demo/com.didi.virtualapk.demo.aidl.BookManagerActivity]
            Log.i(TAG, String.format("newActivity[%s : %s/%s]", className, component.getPackageName(), targetClassName));
    
            LoadedPlugin plugin = this.mPluginManager.getLoadedPlugin(component);

            // 如果没有发现，直接去 StubActivity 跳回主页
            if (plugin == null) {
                // Not found then goto stub activity.
                boolean debuggable = false;
                try {
                    Context context = this.mPluginManager.getHostContext();
                    debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
                } catch (Throwable ex) {
        
                }
    
                if (debuggable) {
                    throw new ActivityNotFoundException("error intent: " + intent.toURI());
                }
                
                Log.i(TAG, "Not found. starting the stub activity: " + StubActivity.class);
                return newActivity(mBase.newActivity(cl, StubActivity.class.getName(), intent));
            }

            // 通过插件的 classloader 创建插件 activity
            Activity activity = mBase.newActivity(plugin.getClassLoader(), targetClassName, intent);
            activity.setIntent(intent);
    
            // for 4.1+
            // 设置 resources
            Reflector.QuietReflector.with(activity).field("mResources").set(plugin.getResources());
    
            return newActivity(activity);
        }

        // 兜底
        return newActivity(mBase.newActivity(cl, className, intent));
    }
    
    @Override
    public Application newApplication(ClassLoader cl, String className, Context context) throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        return mBase.newApplication(cl, className, context);
    }

    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        injectActivity(activity);
        mBase.callActivityOnCreate(activity, icicle);
    }
    
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        injectActivity(activity);
        mBase.callActivityOnCreate(activity, icicle, persistentState);
    }
    
    protected void injectActivity(Activity activity) {
        Log.i(TAG, "injectActivity: " + activity.getClass().getSimpleName());
        final Intent intent = activity.getIntent();
        if (PluginUtil.isIntentFromPlugin(intent)) {
            Context base = activity.getBaseContext();
            try {
                LoadedPlugin plugin = this.mPluginManager.getLoadedPlugin(intent);
                // 设置 resources
                Reflector.with(base).field("mResources").set(plugin.getResources());
                Reflector reflector = Reflector.with(activity);
                // 设置 mBase 为 PluginContext
                reflector.field("mBase").set(plugin.createPluginContext(activity.getBaseContext()));
                reflector.field("mApplication").set(plugin.getApplication());

                // set screenOrientation
                ActivityInfo activityInfo = plugin.getActivityInfo(PluginUtil.getComponent(intent));
                if (activityInfo.screenOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                    activity.setRequestedOrientation(activityInfo.screenOrientation);
                }
    
                // for native activity
                ComponentName component = PluginUtil.getComponent(intent);
                Intent wrapperIntent = new Intent(intent);
                wrapperIntent.setClassName(component.getPackageName(), component.getClassName());
                wrapperIntent.setExtrasClassLoader(activity.getClassLoader());
                activity.setIntent(wrapperIntent);
                
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        Log.i(TAG, "handleMessage:" + msg.what);
        // 9.0 以下才会回调
        if (msg.what == LAUNCH_ACTIVITY) {
            // ActivityClientRecord r
            Object r = msg.obj;
            try {
                Reflector reflector = Reflector.with(r);
                Intent intent = reflector.field("intent").get();
//                intent.setExtrasClassLoader(mPluginManager.getHostContext().getClassLoader());
                ActivityInfo activityInfo = reflector.field("activityInfo").get();

                // 启动 activity 时，设置 theme
                if (PluginUtil.isIntentFromPlugin(intent)) {
                    int theme = PluginUtil.getTheme(mPluginManager.getHostContext(), intent);
                    if (theme != 0) {
                        Log.i(TAG, "resolve theme, current theme:" + activityInfo.theme + "  after :0x" + Integer.toHexString(theme));
                        activityInfo.theme = theme;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, e);
            }
        }
        return false;
    }

    @Override
    public Context getContext() {
        return mBase.getContext();
    }

    @Override
    public Context getTargetContext() {
        return mBase.getTargetContext();
    }

    @Override
    public ComponentName getComponentName() {
        return mBase.getComponentName();
    }

    protected Activity newActivity(Activity activity) {
        synchronized (mActivities) {
            for (int i = mActivities.size() - 1; i >= 0; i--) {
                // WeakReference 持有，如果为空，认为被回收了
                if (mActivities.get(i).get() == null) {
                    mActivities.remove(i);
                }
            }
            mActivities.add(new WeakReference<>(activity));
        }
        return activity;
    }

    List<WeakReference<Activity>> getActivities() {
        synchronized (mActivities) {
            return new ArrayList<>(mActivities);
        }
    }
}
