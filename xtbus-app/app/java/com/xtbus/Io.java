package com.xtbus;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全局工作线程入口。
 *
 * 请求不再绑定单个 Activity：页面销毁只影响回调里的 isFinishing() 判断，
 * 不会因为 shutdownNow 让后续 execute 抛 RejectedExecutionException。
 */
final class Io {

    private static final ExecutorService QUERY = Executors.newFixedThreadPool(2);
    private static final ExecutorService REALTIME = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Io() {
    }

    /** 普通查询（附近、搜索、详情、规划）。 */
    static void query(Runnable task) {
        QUERY.execute(task);
    }

    /** 实时到站单独一条线程，避免拖慢方案加载。 */
    static void realtime(Runnable task) {
        REALTIME.execute(task);
    }

    static void ui(Runnable task) {
        MAIN.post(task);
    }
}
