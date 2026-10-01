// MusicFreeTV - js_job_pump
//
// 背景：io.github.taoweiji.quickjs 1.4.6 内嵌 QuickJS 2021-03-27，
// 该引擎导出了 JS_ExecutePendingJob，但其 Java/JNI 封装从不调用它。
// 结果：Promise / async-await 的微任务（job）队列永不推进——
// 插件的 async 方法在第一个 await 处挂起后，.then 续体永远不执行，
// 表现为「HTTP 请求明明成功返回，但插件调用一直超时」。
//
// 本文件在运行时 dlopen bundled libquickjs.so，取出 JS_ExecutePendingJob，
// 暴露一个 native 方法给 Kotlin，由 Kotlin 在 QuickJS 的 EventQueue 线程上
// 周期性调用，把待处理 job 跑完。
//
// 线程约定：nativePumpJobs 必须在 QuickJS runtime 所属线程（EventQueue 线程）
// 上调用；Kotlin 侧通过 QuickJS.postEventQueue 保证这一点。
//
// P0-7：JS 执行可中断。QuickJS 2021 导出 JS_SetInterruptHandler，但 taoweiji 的
// Java 封装从不安装它，也没有暴露任何取消 API —— 插件里一个 while(true) 就把
// JS 线程永久占住，jsBlock/invoke 的超时只是"放弃等待"，脚本仍在跑，
// 该 lane 之后再也不会执行任何调用（6 台引擎全被占死 = 整个 App 所有音源失效）。
// 这里补上中断回调 + 线程级执行时限：超时后 QuickJS 从解释器内部抛出
// InternalError: interrupted，JS 线程立即回到空闲状态，lane 可以继续服务。

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdint.h>
#include <time.h>

#define TAG "JsJobPump"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// QuickJS 2021 签名：int JS_ExecutePendingJob(JSRuntime *rt, JSContext **pctx)
typedef int (*JS_ExecutePendingJob_t)(void *rt, void **pctx);
// int JS_SetInterruptHandler(JSRuntime *rt, JSInterruptHandler *cb, void *opaque)
typedef int (*JS_SetInterruptHandler_t)(void *rt, void *cb, void *opaque);

static JS_ExecutePendingJob_t g_execPendingJob = NULL;
static JS_SetInterruptHandler_t g_setInterruptHandler = NULL;
static int g_inited = 0;

/**
 * 执行时限（CLOCK_MONOTONIC 毫秒），线程局部。
 *
 * 为什么必须是线程局部而不是全局：多台 QuickJsEngine 共用同一个 .so，
 * 每台引擎有自己的 JS 线程（HandlerThread）与 EventQueue 线程，
 * 全局变量会让 A 引擎的执行时限被 B 引擎的设置互相覆盖。
 * 回调只会在"正在解释 JS 的那个线程"上被调用，线程局部正好一一对应。
 *
 * 0 = 不限时限（脚本正常执行期间）。
 */
static __thread int64_t g_deadline_ms = 0;

static int64_t now_monotonic_ms(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return (int64_t) ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

// 返回 1 = 立刻中断当前脚本（QuickJS 抛 InternalError: interrupted），0 = 继续。
static int js_interrupt_handler(void *rt, void *opaque) {
    (void) rt;
    (void) opaque;
    if (g_deadline_ms <= 0) return 0;
    if (now_monotonic_ms() > g_deadline_ms) {
        // 只在真正打断（插件死循环）时打一行，属低频事件，留作诊断线索。
        LOGE("interrupt: fired (js execution deadline exceeded)");
        return 1;
    }
    return 0;
}

static void *resolve_symbol(const char *sym) {
    // 优先从已加载的模块拿（RTLD_NOLOAD），避免重复加载。
    void *h = dlopen("libquickjs.so", RTLD_NOW | RTLD_NOLOAD);
    if (!h) h = dlopen("libquickjs.so", RTLD_NOW);
    if (!h) {
        // 有些打包形态下 .so 名字带前缀，兜底再从全局符号表拿。
        return dlsym(RTLD_DEFAULT, sym);
    }
    void *p = dlsym(h, sym);
    // 不 dlclose：保持模块驻留，符号才有效。
    return p;
}

JNIEXPORT jboolean JNICALL
Java_com_tvmusic_runtime_QuickJsEngine_nativeInitPump(JNIEnv *env, jobject thiz) {
    if (g_inited) return g_execPendingJob != NULL ? JNI_TRUE : JNI_FALSE;
    g_inited = 1;
    g_execPendingJob = (JS_ExecutePendingJob_t) resolve_symbol("JS_ExecutePendingJob");
    if (!g_execPendingJob) {
        LOGE("JS_ExecutePendingJob not found: %s", dlerror());
        return JNI_FALSE;
    }
    LOGI("JS_ExecutePendingJob resolved OK");
    // 中断回调是可选能力：符号缺失只降级（超时后靠引擎重建兜底），不影响 job pump。
    g_setInterruptHandler = (JS_SetInterruptHandler_t) resolve_symbol("JS_SetInterruptHandler");
    LOGI("JS_SetInterruptHandler %s", g_setInterruptHandler ? "resolved OK" : "not found (deadloop guard degraded)");
    return JNI_TRUE;
}

/**
 * 给指定 runtime 安装中断回调。必须在持有该 runtime 的线程上调用（安装后即生效）。
 * 符号缺失或 runtime 指针非法时返回 false，Kotlin 侧据此走"不装中断 + 引擎重建"降级路径。
 */
JNIEXPORT jboolean JNICALL
Java_com_tvmusic_runtime_QuickJsEngine_nativeInstallInterrupt(JNIEnv *env, jobject thiz, jlong runtimePtr) {
    if (!g_setInterruptHandler || runtimePtr == 0) return JNI_FALSE;
    void *rt = (void *) (intptr_t) runtimePtr;
    g_setInterruptHandler(rt, (void *) js_interrupt_handler, NULL);
    LOGI("interrupt handler installed rt=%p", rt);
    return JNI_TRUE;
}

/**
 * 设置/清除当前线程的 JS 执行时限（绝对毫秒，<=0 表示不限）。
 * 必须由"正在执行 JS 的那个线程"调用（JS 线程或 EventQueue 线程），因为时限是线程局部。
 */
JNIEXPORT void JNICALL
Java_com_tvmusic_runtime_QuickJsEngine_nativeSetInterruptDeadline(JNIEnv *env, jobject thiz, jlong deadlineMs) {
    g_deadline_ms = (int64_t) deadlineMs;
}

// 把当前所有待处理 job 跑完，返回执行掉的 job 数量；<0 表示出错。
// 必须在 QuickJS runtime 所属线程（EventQueue 线程）上调用。
JNIEXPORT jint JNICALL
Java_com_tvmusic_runtime_QuickJsEngine_nativePumpJobs(JNIEnv *env, jobject thiz, jlong runtimePtr) {
    if (!g_execPendingJob || runtimePtr == 0) return -1;
    void *rt = (void *) (intptr_t) runtimePtr;
    int total = 0;
    for (;;) {
        void *ctx = NULL;              // 2021 版会无条件写 *pctx，绝不能传 NULL
        int ret = g_execPendingJob(rt, &ctx);
        if (ret > 0) {
            total += ret;
            if (total > 200000) {      // 安全阀，防死循环 job
                LOGE("pump: too many jobs, bail");
                break;
            }
            continue;
        }
        if (ret < 0) {
            LOGE("pump: JS_ExecutePendingJob error");
            return total > 0 ? total : -1;
        }
        break; // ret == 0，无更多 job
    }
    return total;
}
