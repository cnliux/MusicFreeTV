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

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <stdint.h>

#define TAG "JsJobPump"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// QuickJS 2021 签名：int JS_ExecutePendingJob(JSRuntime *rt, JSContext **pctx)
typedef int (*JS_ExecutePendingJob_t)(void *rt, void **pctx);

static JS_ExecutePendingJob_t g_execPendingJob = NULL;
static int g_inited = 0;

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
    return JNI_TRUE;
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
