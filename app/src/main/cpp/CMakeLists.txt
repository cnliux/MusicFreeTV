cmake_minimum_required(VERSION 3.22.1)
project(jsjobpump C)

add_library(jsjobpump SHARED js_job_pump.c)

# 直接 dlsym 进 bundled libquickjs.so（2021-03-27），
# 该库导出了 JS_ExecutePendingJob 但 Java 层从不调用它，
# 导致 Promise/await 的微任务队列永不推进，插件 async 方法永远挂起超时。
target_link_libraries(jsjobpump
    android
    log
    dl
)
