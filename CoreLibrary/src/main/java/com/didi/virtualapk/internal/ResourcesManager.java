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
import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.ResourcesImpl;
import android.content.res.ResourcesKey;
import android.os.Build;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;

import com.didi.virtualapk.PluginManager;
import com.didi.virtualapk.utils.Reflector;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Created by renyugang on 16/8/9.
 */
class ResourcesManager {

    // VA.LoadedPlugin
    public static final String TAG = Constants.TAG_PREFIX + "LoadedPlugin";

    private static Configuration mDefaultConfiguration;
    
    public static synchronized Resources createResources(Context hostContext, String packageName, File apk) throws Exception {
        // 7.0 以上
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return createResourcesForN(hostContext, packageName, apk);
        }

        // 7.0 以下
        Resources resources = ResourcesManager.createResourcesSimple(hostContext, apk.getAbsolutePath());
        ResourcesManager.hookResources(hostContext, resources);
        return resources;
    }
    
    private static Resources createResourcesSimple(Context hostContext, String apk) throws Exception {
        // 获取主应用的资源
        Resources hostResources = hostContext.getResources();
        Resources newResources;
        AssetManager assetManager;
        Reflector reflector = Reflector.on(AssetManager.class).method("addAssetPath", String.class);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            //小于 5.0 新建 assetManager
            assetManager = AssetManager.class.newInstance();
            reflector.bind(assetManager);
            // Returns the cookie of the added asset, or 0 on failure
            final int cookie1 = reflector.call(hostContext.getApplicationInfo().sourceDir);;
            if (cookie1 == 0) {
                throw new RuntimeException("createResources failed, can't addAssetPath for " + hostContext.getApplicationInfo().sourceDir);
            }
        } else {
            // 5.0 ~ 6.0 使用 host assetManager
            assetManager = hostResources.getAssets();
            reflector.bind(assetManager);
        }
        final int cookie2 = reflector.call(apk);
        if (cookie2 == 0) {
            throw new RuntimeException("createResources failed, can't addAssetPath for " + apk);
        }
        List<LoadedPlugin> pluginList = PluginManager.getInstance(hostContext).getAllLoadedPlugins();
        // 遍历插件列表,依次加载资源
        for (LoadedPlugin plugin : pluginList) {
            final int cookie3 = reflector.call(plugin.getLocation());
            if (cookie3 == 0) {
                throw new RuntimeException("createResources failed, can't addAssetPath for " + plugin.getLocation());
            }
        }
        // 根据机型创建 resources
        if (isMiUi(hostResources)) {
            newResources = MiUiResourcesCompat.createResources(hostResources, assetManager);
        } else if (isVivo(hostResources)) {
            newResources = VivoResourcesCompat.createResources(hostContext, hostResources, assetManager);
        } else if (isNubia(hostResources)) {
            newResources = NubiaResourcesCompat.createResources(hostResources, assetManager);
        } else if (isNotRawResources(hostResources)) {
            newResources = AdaptationResourcesCompat.createResources(hostResources, assetManager);
        } else {
            // is raw android resources
            newResources = new Resources(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
        }
        // 最新合成的 resources 给到每个插件（每个插件都可以加载其他插件或host的资源）
        // lastly, sync all LoadedPlugin to newResources
        for (LoadedPlugin plugin : pluginList) {
            plugin.updateResources(newResources);
        }
        
        return newResources;
    }

    /**
     * hook host
     * 替换两个地方的 resoucre:
     * 1. ContextImpl.mResources
     * 2.ContextImpl.mPackageInfo(LoadedApk).mResources
     * @param base
     * @param resources
     */
    public static void hookResources(Context base, Resources resources) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return;
        }
        try {
            Reflector reflector = Reflector.with(base);
            reflector.field("mResources").set(resources);
            Object loadedApk = reflector.field("mPackageInfo").get();
            Reflector.with(loadedApk).field("mResources").set(resources);

            Object activityThread = ActivityThread.currentActivityThread();
            Object resManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                resManager = android.app.ResourcesManager.getInstance();
            } else {
                resManager = Reflector.with(activityThread).field("mResourcesManager").get();
            }
            Map<Object, WeakReference<Resources>> map = Reflector.with(resManager).field("mActiveResources").get();
            Object key = map.keySet().iterator().next();
            map.put(key, new WeakReference<>(resources));
        } catch (Exception e) {
            Log.w(TAG, e);
        }
    }
    
    /**
     * 7.0 以上直接用系统方法
     * Use System Apis to update all existing resources.
     * <br/>
     * 1. Update ApplicationInfo.splitSourceDirs and LoadedApk.mSplitResDirs
     * <br/>
     * 2. Replace all keys of ResourcesManager.mResourceImpls to new ResourcesKey
     * <br/>
     * 3. Use ResourcesManager.appendLibAssetForMainAssetPath(appInfo.publicSourceDir, "${packageName}.vastub") to update all existing resources.
     * <br/>
     *
     * see android.webkit.WebViewDelegate.addWebViewAssetPath(Context)
     */
    @TargetApi(Build.VERSION_CODES.N)
    private static Resources createResourcesForN(Context context, String packageName, File apk) throws Exception {
        long startTime = System.currentTimeMillis();
        String newAssetPath = apk.getAbsolutePath();
        ApplicationInfo info = context.getApplicationInfo();
        String baseResDir = info.publicSourceDir;

        // 1. Update ApplicationInfo.splitSourceDirs and LoadedApk.mSplitResDirs
        info.splitSourceDirs = append(info.splitSourceDirs, newAssetPath);
        LoadedApk loadedApk = Reflector.with(context).field("mPackageInfo").get();
    
        Reflector rLoadedApk = Reflector.with(loadedApk).field("mSplitResDirs");
        String[] splitResDirs = rLoadedApk.get();
        rLoadedApk.set(append(splitResDirs, newAssetPath));

        // 2. Replace all keys of ResourcesManager.mResourceImpls to new ResourcesKey
        final android.app.ResourcesManager resourcesManager = android.app.ResourcesManager.getInstance();
        // private final ArrayMap<ResourcesKey, WeakReference<ResourcesImpl>> mResourceImpls = new ArrayMap<>();
        ArrayMap<ResourcesKey, WeakReference<ResourcesImpl>> originalMap = Reflector.with(resourcesManager).field("mResourceImpls").get();
    
        synchronized (resourcesManager) {
            HashMap<ResourcesKey, WeakReference<ResourcesImpl>> resolvedMap = new HashMap<>();

            // 8.1 9
            if (Build.VERSION.SDK_INT >= 28
                || (Build.VERSION.SDK_INT == 27 && Build.VERSION.PREVIEW_SDK_INT != 0)) { // P Preview
                ResourcesManagerCompatForP.resolveResourcesImplMap(originalMap, resolvedMap, context, loadedApk);
            } else {
                // 7.0 ~ 8.0
                ResourcesManagerCompatForN.resolveResourcesImplMap(originalMap, resolvedMap, baseResDir, newAssetPath);
            }
    
            originalMap.clear();
            originalMap.putAll(resolvedMap);
        }
    
        android.app.ResourcesManager.getInstance().appendLibAssetForMainAssetPath(baseResDir, packageName + ".vastub");
    
        Resources newResources = context.getResources();
    
        // lastly, sync all LoadedPlugin to newResources
        for (LoadedPlugin plugin : PluginManager.getInstance(context).getAllLoadedPlugins()) {
            plugin.updateResources(newResources);
        }
    
        Log.d(TAG, "createResourcesForN cost time: +" + (System.currentTimeMillis() - startTime) + "ms");
        return newResources;
    }

    /**
     * 将 newPath 合并到 paths
     * @param paths
     * @param newPath
     * @return
     */
    private static String[] append(String[] paths, String newPath) {
        if (contains(paths, newPath)) {
            return paths;
        }
        
        final int newPathsCount = 1 + (paths != null ? paths.length : 0);
        final String[] newPaths = new String[newPathsCount];
        if (paths != null) {
            System.arraycopy(paths, 0, newPaths, 0, paths.length);
        }
        newPaths[newPathsCount - 1] = newPath;
        return newPaths;
    }

    /**
     * 检查是否已经存在数组中
     * @param array
     * @param value
     * @return
     */
    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static boolean contains(String[] array, String value) {
        if (array == null) {
            return false;
        }
        for (int i = 0; i < array.length; i++) {
            if (Objects.equals(array[i], value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMiUi(Resources resources) {
        return resources.getClass().getName().equals("android.content.res.MiuiResources");
    }

    private static boolean isVivo(Resources resources) {
        return resources.getClass().getName().equals("android.content.res.VivoResources");
    }

    private static boolean isNubia(Resources resources) {
        return resources.getClass().getName().equals("android.content.res.NubiaResources");
    }

    private static boolean isNotRawResources(Resources resources) {
        return !resources.getClass().getName().equals("android.content.res.Resources");
    }

    private static final class MiUiResourcesCompat {
        private static Resources createResources(Resources hostResources, AssetManager assetManager) throws Exception {
            Reflector reflector = Reflector.on("android.content.res.MiuiResources");
            Resources newResources = reflector.constructor(AssetManager.class, DisplayMetrics.class, Configuration.class)
                .newInstance(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
            return newResources;
        }
    }

    private static final class VivoResourcesCompat {
        private static Resources createResources(Context hostContext, Resources hostResources, AssetManager assetManager) throws Exception {
            Reflector reflector = Reflector.on("android.content.res.VivoResources");
            Resources newResources = reflector.constructor(AssetManager.class, DisplayMetrics.class, Configuration.class)
                .newInstance(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
            reflector.method("init", String.class).callByCaller(newResources, hostContext.getPackageName());
            reflector.field("mThemeValues");
            reflector.set(newResources, reflector.get(hostResources));
            return newResources;
        }
    }

    private static final class NubiaResourcesCompat {
        private static Resources createResources(Resources hostResources, AssetManager assetManager) throws Exception {
            Reflector reflector = Reflector.on("android.content.res.NubiaResources");
            Resources newResources = reflector.constructor(AssetManager.class, DisplayMetrics.class, Configuration.class)
                .newInstance(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
            return newResources;
        }
    }

    private static final class AdaptationResourcesCompat {
        private static Resources createResources(Resources hostResources, AssetManager assetManager) throws Exception {
            Resources newResources;
            try {
                Reflector reflector = Reflector.with(hostResources);
                newResources = reflector.constructor(AssetManager.class, DisplayMetrics.class, Configuration.class)
                    .newInstance(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
            } catch (Exception e) {
                newResources = new Resources(assetManager, hostResources.getDisplayMetrics(), hostResources.getConfiguration());
            }

            return newResources;
        }
    }

    private static final class ResourcesManagerCompatForN {
        
        @TargetApi(Build.VERSION_CODES.KITKAT)
        public static void resolveResourcesImplMap(Map<ResourcesKey, WeakReference<ResourcesImpl>> originalMap, Map<ResourcesKey, WeakReference<ResourcesImpl>> resolvedMap, String baseResDir, String newAssetPath) throws Exception {
            for (Map.Entry<ResourcesKey, WeakReference<ResourcesImpl>> entry : originalMap.entrySet()) {
                ResourcesKey key = entry.getKey();
                if (Objects.equals(key.mResDir, baseResDir)) {
                    resolvedMap.put(new ResourcesKey(key.mResDir,
                        append(key.mSplitResDirs, newAssetPath),
                        key.mOverlayDirs,
                        key.mLibDirs,
                        key.mDisplayId,
                        key.mOverrideConfiguration,
                        key.mCompatInfo), entry.getValue());
                } else {
                    resolvedMap.put(key, entry.getValue());
                }
            }
        }
    }
    
    private static final class ResourcesManagerCompatForP {
        
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public static void resolveResourcesImplMap(Map<ResourcesKey, WeakReference<ResourcesImpl>> originalMap, Map<ResourcesKey, WeakReference<ResourcesImpl>> resolvedMap, Context context, LoadedApk loadedApk) throws Exception {
            // 存储 context/activity 和对应新建的 impl
            HashMap<ResourcesImpl, Context> newResImplMap = new HashMap<>();
            // 存储原始 impl 和 key
            Map<ResourcesImpl, ResourcesKey> resKeyMap = new HashMap<>();
            Resources newRes;
        
            // Recreate the resImpl of the context
        
            // See LoadedApk.getResources()
            if (mDefaultConfiguration == null) {
                mDefaultConfiguration = new Configuration();
            }
            newRes = context.createConfigurationContext(mDefaultConfiguration).getResources();
            newResImplMap.put(newRes.getImpl(), context);
        
            // Recreate the ResImpl of the activity
            for (WeakReference<Activity> ref : PluginManager.getInstance(context).getInstrumentation().getActivities()) {
                Activity activity = ref.get();
                if (activity != null) {
                    newRes = activity.createConfigurationContext(activity.getResources().getConfiguration()).getResources();
                    newResImplMap.put(newRes.getImpl(), activity);
                }
            }
        
            // Mapping all resKey and resImpl
            for (Map.Entry<ResourcesKey, WeakReference<ResourcesImpl>> entry : originalMap.entrySet()) {
                ResourcesImpl resImpl = entry.getValue().get();
                if (resImpl != null) {
                    resKeyMap.put(resImpl, entry.getKey());
                }
                resolvedMap.put(entry.getKey(), entry.getValue());
            }
        
            // Replace the resImpl to the new resKey and remove the origin resKey
            for (Map.Entry<ResourcesImpl, Context> entry : newResImplMap.entrySet()) {
                // 找到原始的 key
                ResourcesKey newKey = resKeyMap.get(entry.getKey());
                // 找到原始的 impl
                ResourcesImpl originResImpl = entry.getValue().getResources().getImpl();

                // 放入 resolvedMap
                resolvedMap.put(newKey, new WeakReference<>(originResImpl));
                // 移除旧的 TODO 后续再看原因，有点看不懂
                resolvedMap.remove(resKeyMap.get(originResImpl));
            }
        }
    }
}
