// MusicFreeTV runtime - bootstrap.js
// 在 globals.js + moduleLoader.js + libs 之后执行。
// 封装插件协议所需的全局环境与远程调用 RPC。

(function (global) {
    'use strict';

    // ---- 全局第三方库 ----
    // 部分插件不 require 而直接引用全局 axios 等标识符（如 migu.js 的 axios.get）。
    // 已注册的库原样暴露为全局，避免这类插件因 "xxx is not defined" 运行失败。
    ['axios', 'dayjs', 'he', 'qs', 'cheerio', 'crypto-js', 'big-integer', 'webdav'].forEach(function (name) {
        try {
            if (global.__requireExists && global.__requireExists(name) && global[name] === undefined) {
                global[name] = global.__require(name);
            }
        } catch (e) { /* 忽略：库缺失不影响其它功能 */ }
    });

    // ---- env（插件协议里的 env.getUserVariables） ----
    // 按当前插件 platform 取变量，避免 Cookie/password 等同名 key 跨插件覆盖。
    var platStack = [];
    function pushPlatform(platform) {
        platStack.push(platform || '');
        global.__currentPluginPlatform = platStack[platStack.length - 1];
    }
    function popPlatform() {
        platStack.pop();
        global.__currentPluginPlatform = platStack.length ? platStack[platStack.length - 1] : '';
    }
    if (!global.env) {
        global.env = {
            getUserVariables: function () {
                var plat = global.__currentPluginPlatform || '';
                var raw = global.nativeBridge.getUserVariables(plat);
                try { return JSON.parse(raw); } catch (e) { return {}; }
            },
            currentTimeMillis: function () { return Date.now(); }
        };
    }

    // ---- 插件注册表 ----
    global.__plugins = {};

    global.__registerPlugin = function (platform, source) {
        var id = 'plugin:' + platform;
        pushPlatform(platform);
        try {
            global.__registerCommonJS(id, source);
            var exp = global.__require(id);
            // Parcel 打包的插件把插件对象放在 module.exports.default 上，
            // 普通 TS 编译产物直接就是 module.exports。
            var plugin = (exp && exp.default && typeof exp.default === 'object')
                ? exp.default
                : exp;
            global.__plugins[platform] = plugin;
            return !!plugin;
        } finally {
            popPlatform();
        }
    };

    global.__hasPlugin = function (platform) {
        return !!global.__plugins[platform];
    };

    global.__hasMethod = function (platform, method) {
        var p = global.__plugins[platform];
        return !!(p && typeof p[method] === 'function');
    };

    // ---- 插件静态信息（同步读取，供 UI 使用） ----
    global.__readPluginInfo = function (platform) {
        var p = global.__plugins[platform];
        if (!p) return 'null';
        function pick(o, key) {
            try { return o[key] === undefined ? null : o[key]; } catch (e) { return null; }
        }
        return JSON.stringify({
            platform: pick(p, 'platform'),
            version: pick(p, 'version'),
            author: pick(p, 'author'),
            srcUrl: pick(p, 'srcUrl'),
            cacheControl: pick(p, 'cacheControl'),
            appVersion: pick(p, 'appVersion'),
            description: pick(p, 'description'),
            primaryKey: pick(p, 'primaryKey'),
            hints: pick(p, 'hints'),
            supportedSearchType: pick(p, 'supportedSearchType'),
            userVariables: pick(p, 'userVariables')
        });
    };

    // ---- 异步调用 RPC ----
    // 插件方法多为 async，调用后返回 Promise；由 Promise.then 把结果回吐给原生：
    //   global.nativeBridge.onPluginResult(cbId, json)
    // 或
    //   global.nativeBridge.onPluginError(cbId, message)
    global.__invokeSeq = 0;

    global.__invoke = function (platform, method, argsJson, cbId) {
        var p = global.__plugins[platform];
        if (!p) { global.nativeBridge.onPluginError(cbId, 'plugin not loaded: ' + platform); return; }
        var fn = p[method];
        if (typeof fn !== 'function') {
            // 未实现的方法统一回 __notImplemented（UI 层静默跳过）
            global.nativeBridge.onPluginResult(cbId, JSON.stringify({ __notImplemented: true }));
            return;
        }
        var args;
        try {
            args = JSON.parse(argsJson);
        } catch (e) {
            global.nativeBridge.onPluginError(cbId, 'bad args: ' + e.message);
            return;
        }
        pushPlatform(platform);
        try {
            var ret = fn.apply(p, args.map(function (a) {
                try { return typeof a === 'string' ? JSON.parse(a) : a; } catch (e2) { return a; }
            }));
            Promise.resolve(ret).then(function (res) {
                popPlatform();
                var json;
                try { json = JSON.stringify(res === undefined ? null : res); }
                catch (e3) { json = JSON.stringify({ __serializeError: String(e3) }); }
                global.nativeBridge.onPluginResult(cbId, json);
            }, function (err) {
                popPlatform();
                global.nativeBridge.onPluginError(cbId, String((err && err.stack) || err));
            });
        } catch (e) {
            popPlatform();
            global.nativeBridge.onPluginError(cbId, String((e && e.stack) || e));
        }
    };

})(globalThis);