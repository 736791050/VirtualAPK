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

package com.didi.virtualapk.internal.utils;

import android.app.ActivityThread;
import android.content.Context;
import android.os.Build;

import com.didi.virtualapk.internal.Constants;
import com.didi.virtualapk.utils.Reflector;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.List;

import dalvik.system.DexClassLoader;

/**
 *         BaseDexClassLoader
 *           /          \
 * DexClassLoader     PathClassLoader
 *  (支持外部）             (仅系统）
 *
 *  BaseDexClassLoader
 *       |
 *  DexPathList pathList
 *       |
 *  Element[] dexElements  (List of dex/resource)
 *  NativeLibraryElement[] nativeLibraryPathElements
 *  List<File> nativeLibraryDirectories
 */
public class DexUtil {
    private static boolean sHasInsertedNativeLibrary = false;

    public static void insertDex(DexClassLoader dexClassLoader, ClassLoader baseClassLoader, File nativeLibsDir) throws Exception {
        Object baseDexElements = getDexElements(getPathList(baseClassLoader));
        Object newDexElements = getDexElements(getPathList(dexClassLoader));
        Object allDexElements = combineArray(baseDexElements, newDexElements);
        Object pathList = getPathList(baseClassLoader);
        //将合并的 dexElements 设置到 baseClassLoader
        Reflector.with(pathList).field("dexElements").set(allDexElements);

        insertNativeLibrary(dexClassLoader, baseClassLoader, nativeLibsDir);
    }

    /**
     * 反射获取 dexElements
     * @param pathList
     * @return
     * @throws Exception
     */
    private static Object getDexElements(Object pathList) throws Exception {
        return Reflector.with(pathList).field("dexElements").get();
    }

    /**
     * 反射获取 pathList
     * @param baseDexClassLoader
     * @return
     * @throws Exception
     */
    private static Object getPathList(ClassLoader baseDexClassLoader) throws Exception {
        return Reflector.with(baseDexClassLoader).field("pathList").get();
    }

    /**
     * 合并 arrays
     * @param firstArray
     * @param secondArray
     * @return
     */
    private static Object combineArray(Object firstArray, Object secondArray) {
        Class<?> localClass = firstArray.getClass().getComponentType();
        int firstArrayLength = Array.getLength(firstArray);
        int secondArrayLength = Array.getLength(secondArray);
        Object result = Array.newInstance(localClass, firstArrayLength + secondArrayLength);
        System.arraycopy(firstArray, 0, result, 0, firstArrayLength);
        System.arraycopy(secondArray, 0, result, firstArrayLength, secondArrayLength);
        return result;
    }

    private static synchronized void insertNativeLibrary(DexClassLoader dexClassLoader, ClassLoader baseClassLoader, File nativeLibsDir) throws Exception {
        if (sHasInsertedNativeLibrary) {
            return;
        }
        sHasInsertedNativeLibrary = true;

        Context context = ActivityThread.currentApplication();
        Object basePathList = getPathList(baseClassLoader);
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) {
            Reflector reflector = Reflector.with(basePathList);
            List<File> nativeLibraryDirectories = reflector.field("nativeLibraryDirectories").get();
            nativeLibraryDirectories.add(nativeLibsDir);

            Object baseNativeLibraryPathElements = reflector.field("nativeLibraryPathElements").get();
            final int baseArrayLength = Array.getLength(baseNativeLibraryPathElements);

            // 找到插件中的 pathList
            Object newPathList = getPathList(dexClassLoader);
            // 反射获取插件中的 nativeLibraryPathElements
            Object newNativeLibraryPathElements = reflector.get(newPathList);
            Class<?> elementClass = newNativeLibraryPathElements.getClass().getComponentType();
            // 新建数组
            Object allNativeLibraryPathElements = Array.newInstance(elementClass, baseArrayLength + 1);
            // 存储 base 的 nativeLibraryPathElements
            System.arraycopy(baseNativeLibraryPathElements, 0, allNativeLibraryPathElements, 0, baseArrayLength);

            Field soPathField;
            if (Build.VERSION.SDK_INT >= 26) {
                soPathField = elementClass.getDeclaredField("path");
            } else {
                soPathField = elementClass.getDeclaredField("dir");
            }
            soPathField.setAccessible(true);
            // 遍历插件的 nativeLibraryPathElements
            final int newArrayLength = Array.getLength(newNativeLibraryPathElements);
            for (int i = 0; i < newArrayLength; i++) {
                Object element = Array.get(newNativeLibraryPathElements, i);
                String dir = ((File)soPathField.get(element)).getAbsolutePath();
                // 找到包含 valibs 路径的 NativeLibraryPathElement，添加到数组
                if (dir.contains(Constants.NATIVE_DIR)) {
                    Array.set(allNativeLibraryPathElements, baseArrayLength, element);
                    break;
                }
            }

            // 设置 base + 插件融合的新数组
            reflector.set(allNativeLibraryPathElements);
        } else {
            Reflector reflector = Reflector.with(basePathList).field("nativeLibraryDirectories");
            File[] nativeLibraryDirectories = reflector.get();
            final int N = nativeLibraryDirectories.length;
            File[] newNativeLibraryDirectories = new File[N + 1];
            System.arraycopy(nativeLibraryDirectories, 0, newNativeLibraryDirectories, 0, N);
            newNativeLibraryDirectories[N] = nativeLibsDir;
            reflector.set(newNativeLibraryDirectories);
        }
    }

}