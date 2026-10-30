// MusicFreeTV runtime - libs/axios.js
// 精简 axios 兼容实现：把 axios API 映射到原生桥 nativeBridge.httpRequest(method,url,headersJson,body)。
// 支持：axios(config) 函数式调用、axios.get/post/put/patch/delete/head/request、params、headers、
//       data、defaults.headers、axios.create({baseURL})、超时忽略。
// 返回结构：{ data, status, statusText, headers, config }
//
// 【关键兼容点】真实 axios 返回 Promise，插件普遍写成 `axios.get(url).then(...)` 或
// `await axios.get(url)`。本垫片受原生桥限制只能同步拿到响应，因此响应对象做成
// “混合 thenable”：既保留同步字段（res.data / res.status 直接可读），又提供
// then/catch/finally —— Promise.resolve(snapshot) 保证不会因 thenable 自引用递归。
// 这是大量插件在聚合搜索里报 `TypeError: not a function` 的根因修复。
//
// 注意：Accept-Encoding 由原生侧统一处理（OkHttp 自动解压 gzip），插件传入的 gzip 头会被忽略。
(function (module) {
    'use strict';

    var qs = require('qs');

    function isObject(v) { return v !== null && typeof v === 'object'; }

    function hasOwn(o, k) { return Object.prototype.hasOwnProperty.call(o, k); }

    // 头合并：真实 axios 的 defaults.headers 形如 {common:{},get:{},post:{}}，
    // 部分插件直接写 axios.defaults.headers['User-Agent']，两种形态都兼容——
    // 值为对象时按“分组”展开一层，extra 覆盖 base。
    function mergeHeaders(baseHeaders, extraHeaders) {
        var out = {};
        function add(src, override) {
            if (!src || typeof src !== 'object') return;
            for (var k in src) {
                if (!hasOwn(src, k) || src[k] === undefined || src[k] === null) continue;
                var v = src[k];
                if (v && typeof v === 'object') {
                    for (var k2 in v) {
                        if (hasOwn(v, k2) && v[k2] !== undefined && v[k2] !== null &&
                            (override || out[k2] === undefined)) out[k2] = String(v[k2]);
                    }
                } else if (override || out[k] === undefined) {
                    out[k] = String(v);
                }
            }
        }
        add(baseHeaders, false);
        add(extraHeaders, true);
        return out;
    }

    // 全局默认头（axios.defaults.headers）
    var defaults = { headers: {} };

    /**
     * 把同步响应包装成 thenable：真实 axios 的 .then/.catch/.finally 语义齐备，
     * 同时保留同步字段访问（老插件直接读 res.data）。
     * then 解析的是快照副本（不含 then），避免 Promise 解析时递归调用自身。
     */
    function makeThenable(response) {
        function snapshot() {
            return {
                data: response.data,
                status: response.status,
                statusText: response.statusText,
                headers: response.headers,
                config: response.config
            };
        }
        function define(name, fn) {
            try {
                Object.defineProperty(response, name, {
                    value: fn,
                    enumerable: false,
                    configurable: true,
                    writable: true
                });
            } catch (e) {
                // 极端环境（无 defineProperty）退化为直接赋值
                response[name] = fn;
            }
        }
        define('then', function (onFulfilled, onRejected) {
            return Promise.resolve(snapshot()).then(onFulfilled, onRejected);
        });
        define('catch', function (onRejected) {
            return Promise.resolve(snapshot()).catch(onRejected);
        });
        define('finally', function (onFinally) {
            return Promise.resolve(snapshot()).then(function (v) {
                if (typeof onFinally === 'function') onFinally();
                return v;
            }, function (err) {
                if (typeof onFinally === 'function') { try { onFinally(); } catch (e) {} }
                throw err;
            });
        });
        return response;
    }

    function axiosRequest(method, url, config) {
        config = config || {};
        var fullUrl = String(url || '');
        var params = config.params;
        var paramSerializer = config.paramsSerializer;
        if (params && isObject(params)) {
            var q = qs.stringify(params, paramSerializer);
            if (q) fullUrl += (fullUrl.indexOf('?') >= 0 ? '&' : '?') + q;
        }
        var headers = mergeHeaders(defaults.headers, config.headers);
        var data = config.data;
        var body = '';
        if (data === null || data === undefined) {
            body = '';
        } else if (typeof data === 'string') {
            body = data;
        } else {
            try {
                body = String(JSON.stringify(data));
                if (headers['Content-Type'] === undefined && headers['content-type'] === undefined) {
                    headers['Content-Type'] = 'application/json';
                }
            } catch (e) {
                body = String(data);
            }
        }
        if (/^(GET|HEAD)$/i.test(method)) body = '';

        var raw;
        try {
            raw = globalThis.nativeBridge.httpRequest(method, fullUrl, JSON.stringify(headers), body);
        } catch (e) {
            var netErr = new Error('Network Error: ' + (e && e.message ? e.message : e));
            netErr.config = config;
            netErr.isAxiosError = true;
            netErr.isNetworkError = true;
            throw netErr;
        }
        var parsed;
        try {
            parsed = JSON.parse(raw);
        } catch (e) {
            parsed = { status: 0, statusText: '', headers: {}, body: raw };
        }
        if (parsed.__error) {
            var err = new Error('Network Error: ' + parsed.__error);
            err.config = config;
            err.isAxiosError = true;
            err.isNetworkError = true;
            throw err;
        }

        // 响应头名统一小写：真实 axios（Node 与服务端实现）交给插件的头名都是小写，
        // 插件普遍写 `res.headers['set-cookie']` / `['content-type']`。旧实现原样透传
        // 服务端的 `Set-Cookie`（大写 S/C），插件取不到 Cookie → 会话/挑战流程整条断掉
        // （真实案例：SpaceDJ 的 challengeGet 拿不到 set-cookie，后续搜索恒 403）。
        // 同时保留原样键，兼容少数写全大写头名的插件。
        var rawHeaders = parsed.headers || {};
        var respHeaders = {};
        for (var hk in rawHeaders) {
            if (!hasOwn(rawHeaders, hk)) continue;
            var lk = String(hk).toLowerCase();
            if (respHeaders[lk] === undefined) respHeaders[lk] = rawHeaders[hk];
            if (lk !== hk) respHeaders[hk] = rawHeaders[hk];
        }
        // 单值 set-cookie 也按数组暴露（Node 的 axios 恒为数组），插件一律写
        // `.forEach(...)`；老原生桥把多值拼成字符串时这里至少不会整体崩掉。
        if (typeof respHeaders['set-cookie'] === 'string') {
            respHeaders['set-cookie'] = [respHeaders['set-cookie']];
        }
        if (typeof respHeaders['Set-Cookie'] === 'string') {
            respHeaders['Set-Cookie'] = [respHeaders['Set-Cookie']];
        }
        var respData = parsed.body;
        var contentType = String(respHeaders['Content-Type'] || respHeaders['content-type'] || respHeaders['CONTENT-TYPE'] || '').toLowerCase();
        if (typeof respData === 'string') {
            var looksJson = contentType.indexOf('json') >= 0 || /^\s*[\[{]/.test(respData);
            if (looksJson) {
                try { respData = JSON.parse(respData); } catch (e) {}
            }
        }
        var response = {
            data: respData,
            status: typeof parsed.status === 'number' ? parsed.status : 0,
            statusText: parsed.statusText || '',
            headers: respHeaders,
            config: config
        };
        var status = response.status;
        // 与真实 axios 一致：默认把 4xx/5xx 视为失败；但插件显式传 validateStatus
        // （典型 `validateStatus: () => true`，用于读取挑战页 403 的 set-cookie）必须
        // 交给它裁决。旧实现无条件 throw，插件拿不到响应，会话/挑战链路直接断。
        var validate = typeof config.validateStatus === 'function' ? config.validateStatus : null;
        var statusOk;
        if (validate) {
            try { statusOk = !!validate(status); } catch (e) { statusOk = false; }
        } else {
            statusOk = status < 400;
        }
        if (!statusOk) {
            var httpErr = new Error('Request failed with status code ' + status);
            httpErr.config = config;
            httpErr.response = response;
            httpErr.isAxiosError = true;
            throw httpErr;
        }
        return makeThenable(response);
    }

    function mergeConfig(base, config) {
        var out = {};
        var k;
        if (base) for (k in base) { if (hasOwn(base, k)) out[k] = base[k]; }
        if (config) for (k in config) {
            if (!hasOwn(config, k)) continue;
            if (k === 'headers') {
                out.headers = mergeHeaders(base && base.headers, config.headers);
            } else {
                out[k] = config[k];
            }
        }
        return out;
    }

    function makeInstance(baseConfig) {
        var base = baseConfig || {};
        var baseURL = String(base.baseURL || '');

        function resolveUrl(u) {
            u = String(u === undefined || u === null ? '' : u);
            if (!baseURL || /^[a-z][a-z0-9+.-]*:\/\//i.test(u)) return u;
            return baseURL.replace(/\/+$/, '') + '/' + u.replace(/^\/+/, '');
        }

        var instance = function (config) {
            if (typeof config === 'string') config = { url: config };
            config = mergeConfig(base, config || {});
            config.url = resolveUrl(config.url);
            var method = (config.method || 'get').toUpperCase();
            return axiosRequest(method, config.url, config);
        };

        function methodFn(method, withData) {
            return function (url, dataOrConfig, config) {
                var cfg = mergeConfig(base, (withData ? config : dataOrConfig) || {});
                cfg.url = resolveUrl(url);
                if (withData) cfg.data = dataOrConfig;
                return axiosRequest(method, cfg.url, cfg);
            };
        }

        instance.get = methodFn('GET', false);
        instance.head = methodFn('HEAD', false);
        instance.delete = methodFn('DELETE', false);
        instance.post = methodFn('POST', true);
        instance.put = methodFn('PUT', true);
        instance.patch = methodFn('PATCH', true);
        // axios.request(config) / axios.request(url, config)
        instance.request = function (config) {
            return typeof config === 'string' ? instance({ url: config }) : instance(config);
        };
        instance.default = instance; // 兼容 bilibili 等插件里 axios_1.default.get 的写法
        instance.defaults = base;
        instance.isCancel = function () { return false; };
        instance.isAxiosError = function (e) { return !!(e && e.isAxiosError); };
        instance.create = function (childDefaults) { return makeInstance(childDefaults || {}); };
        // 常量（部分插件会引用 axios 的状态码常量，仅提供占位）
        instance.CanceledError = function (msg) {
            var e = new Error(msg || 'canceled');
            e.code = 'ERR_CANCELED';
            e.isAxiosError = true;
            return e;
        };
        return instance;
    }

    var instance = makeInstance(defaults);

    module.exports = instance;
})(module);
