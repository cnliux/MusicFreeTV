// MusicFreeTV runtime - libs/axios.js
// 精简 axios 兼容实现：把 axios API 映射到原生桥 nativeBridge.httpRequest(method,url,headersJson,body)。
// 支持：axios(config) 函数式调用、axios.get/post/put/patch/delete、params、headers、data、超时忽略。
// 返回结构：{ data, status, statusText, headers }
// 注意：Accept-Encoding 由原生侧统一处理（OkHttp 自动解压 gzip），插件传入的 gzip 头会被忽略。
(function (module) {
    'use strict';

    var qs = require('qs');

    function isObject(v) { return v !== null && typeof v === 'object'; }

    function axiosRequest(method, url, config) {
        config = config || {};
        var fullUrl = String(url || '');
        var params = config.params;
        var paramSerializer = config.paramsSerializer;
        if (params && isObject(params)) {
            var q = qs.stringify(params, paramSerializer);
            if (q) fullUrl += (fullUrl.indexOf('?') >= 0 ? '&' : '?') + q;
        }
        var headers = {};
        if (config.headers) {
            var src = config.headers;
            var h = (typeof src.forEach === 'function') ? src : src;
            for (var k in h) {
                if (Object.prototype.hasOwnProperty.call(h, k)) headers[k] = String(h[k]);
            }
        }
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

        var raw;
        try {
            raw = globalThis.nativeBridge.httpRequest(method, fullUrl, JSON.stringify(headers), body);
        } catch (e) {
            var netErr = new Error('Network Error: ' + e.message);
            netErr.config = config;
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
            err.isNetworkError = true;
            throw err;
        }

        var respHeaders = parsed.headers || {};
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
        if (response.status >= 400) {
            var httpErr = new Error('Request failed with status code ' + response.status);
            httpErr.config = config;
            httpErr.response = response;
            throw httpErr;
        }
        return response;
    }

    var instance = function (config) {
        if (typeof config === 'string') config = { url: config };
        config = config || {};
        var method = (config.method || 'get').toUpperCase();
        return axiosRequest(method, config.url, config);
    };

    instance.get = function (url, config) { return axiosRequest('GET', url, config); };
    instance.post = function (url, data, config) {
        return axiosRequest('POST', url, Object.assign({}, config, { data: data }));
    };
    instance.put = function (url, data, config) {
        return axiosRequest('PUT', url, Object.assign({}, config, { data: data }));
    };
    instance.delete = function (url, data, config) {
        var c = Object.assign({}, config);
        if (data && isObject(data) && !data.data) c.data = data;
        else if (data && isObject(data) && data.data) c = Object.assign({}, c, data);
        return axiosRequest('DELETE', url, c);
    };
    instance.head = function (url, config) { return axiosRequest('HEAD', url, config); };
    instance.patch = function (url, data, config) {
        return axiosRequest('PATCH', url, Object.assign({}, config, { data: data }));
    };
    instance.default = instance; // 兼容 bilibili 等插件里 axios_1.default.get 的写法
    instance.defaults = {};
    instance.isCancel = function () { return false; };
    instance.create = function (defaults) {
        var sub = function (config) {
            if (typeof config === 'string') config = { url: config };
            return instance(String((defaults && defaults.baseURL) || '') + (config && config.url || ''));
        };
        sub.get = instance.get;
        sub.post = instance.post;
        sub.put = instance.put;
        sub.delete = instance.delete;
        sub.head = instance.head;
        sub.patch = instance.patch;
        sub.default = sub;
        sub.defaults = defaults || {};
        return sub;
    };
    // 常量（部分插件会引用 axios 的 状态码 常量，仅提供占位）
    instance.CanceledError = function (msg) { var e = new Error(msg || 'canceled'); e.code = 'ERR_CANCELED'; return e; };

    module.exports = instance;
})(module);