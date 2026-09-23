// MusicFreeTV runtime - libs/qs.js
// 极简 qs 实现：目前插件仅使用 qs.stringify / qs.parse / qs.encode 处理 URL query。
(function (module) {
    'use strict';

    function encode(s) { return encodeURIComponent(s); }
    function decode(s) { return decodeURIComponent(s.replace(/\+/g, ' ')); }

    function walk(prefix, value, parts) {
        if (value === null || value === undefined) return;
        if (value instanceof Date) {
            parts.push(encode(prefix) + '=' + encode(String(value.getTime())));
            return;
        }
        if (Array.isArray(value)) {
            for (var i = 0; i < value.length; i++) {
                walk(prefix + '[]', value[i], parts);
            }
            return;
        }
        if (typeof value === 'object') {
            var keys = Object.keys(value);
            for (var j = 0; j < keys.length; j++) {
                var k = keys[j];
                walk(prefix ? (prefix + '[' + k + ']') : k, value[k], parts);
            }
            return;
        }
        parts.push(encode(prefix) + '=' + encode(String(value)));
    }

    function stringify(obj, serializer) {
        if (obj === null || obj === undefined) return '';
        var parts = [];
        walk('', obj, parts);
        return parts.join('&');
    }

    function parse(str) {
        var out = {};
        if (!str) return out;
        var parts = String(str).replace(/^[?#]/, '').split('&');
        for (var i = 0; i < parts.length; i++) {
            if (!parts[i]) continue;
            var eq = parts[i].indexOf('=');
            var k = decode(eq >= 0 ? parts[i].slice(0, eq) : parts[i]);
            var v = decode(eq >= 0 ? parts[i].slice(eq + 1) : '');
            if (!(k in out)) out[k] = v;
            else if (Array.isArray(out[k])) out[k].push(v);
            else out[k] = [out[k], v];
        }
        return out;
    }

    module.exports = {
        stringify: stringify,
        parse: parse,
        encode: encode
    };
})(module);