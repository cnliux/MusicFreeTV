// MusicFreeTV runtime - moduleLoader.js
// 极简 CommonJS 加载器。支持 require / module.exports / env 全局。
// 插件源码由原生侧注册：__registerCommonJS(id, source)，随后 __require(id) 得到其导出对象。

(function (global) {
    'use strict';

    global.__moduleRegistry = {};
    global.__moduleCache = {};

    global.__registerCommonJS = function (id, source) {
        global.__moduleRegistry[id] = source;
        global.__moduleCache[id] = undefined;
    };

    global.__require = function (id) {
        var cached = global.__moduleCache[id];
        if (cached && cached.loaded) { return cached.exports; }
        if (!Object.prototype.hasOwnProperty.call(global.__moduleRegistry, id)) {
            // 尝试 node 风格 index 解析（webdav 等库可能引用内部模块）
            throw new Error('Module not found: ' + id);
        }
        var module = { id: id, exports: {}, loaded: false };
        global.__moduleCache[id] = module;
        var fn;
        try {
            fn = new Function(
                'module', 'exports', 'require', 'env', 'console', 'process',
                'global', 'globalThis',
                global.__moduleRegistry[id]
            );
        } catch (e) {
            throw new Error('Compile module failed: ' + id + ' -> ' + e.message);
        }
        var localRequire = function (name) {
            return global.__require(global.__resolveModule(id, name));
        };
        fn.call(
            module.exports,
            module,
            module.exports,
            localRequire,
            global.env,
            global.console,
            global.process,
            global,
            global
        );
        module.loaded = true;
        return module.exports;
    };

    // 相对路径解析（同目录），npm 库名原样返回
    global.__resolveModule = function (fromId, name) {
        if (name && name.charAt(0) === '.' && typeof fromId === 'string') {
            var parts = fromId.split('/');
            parts.pop();
            var segs = name.split('/');
            for (var i = 0; i < segs.length; i++) {
                var seg = segs[i];
                if (seg === '..') { parts.pop(); }
                else if (seg !== '.') { parts.push(seg); }
            }
            return parts.join('/');
        }
        return name;
    };

    global.__requireExists = function (id) {
        return Object.prototype.hasOwnProperty.call(global.__moduleRegistry, id);
    };
})(globalThis);