// MusicFreeTV runtime - globals.js
// QuickJS 环境下的全局垫片：console / base64 / 定时器 / URL / process 存根。
// 这些能力由原生侧 nativeBridge 提供：
//   nativeBridge.log(level, msg)
//   nativeBridge.scheduleTimer(id, ms)
//   nativeBridge.httpRequest(method, url, headersJson, body)
//   nativeBridge.getUserVariables()

(function (global) {
    'use strict';

    var bridge = global.nativeBridge;

    // ---------- console ----------
    if (!global.console) {
        var makeLogger = function (level) {
            return function () {
                try {
                    var parts = [];
                    for (var i = 0; i < arguments.length; i++) {
                        var a = arguments[i];
                        parts.push(typeof a === 'string' ? a : safeStringify(a));
                    }
                    bridge.log(level, parts.join(' '));
                } catch (e) { /* 不能输出则忽略 */ }
            };
        };
        function safeStringify(v) {
            try {
                if (v instanceof Error) { return (v && v.stack) ? v.stack : String(v); }
                var s = JSON.stringify(v);
                if (s === undefined) { return String(v); }
                return s;
            } catch (e) {
                return '[object]';
            }
        }
        global.console = {
            log: makeLogger('log'),
            info: makeLogger('info'),
            warn: makeLogger('warn'),
            error: makeLogger('error'),
            debug: makeLogger('debug'),
            count: function () { bridge.log('log', 'console.count'); },
            trace: function () { bridge.log('log', 'console.trace'); }
        };
    }

    // ---------- btoa / atob ----------
    var B64_CHARS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function b64Encode(str) {
        var bytes = utf8ToBytes(str);
        var out = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i], b1 = i + 1 < bytes.length ? bytes[i + 1] : 0, b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            out += B64_CHARS[b0 >> 2];
            out += B64_CHARS[((b0 & 3) << 4) | (b1 >> 4)];
            out += i + 1 < bytes.length ? B64_CHARS[((b1 & 15) << 2) | (b2 >> 6)] : '=';
            out += i + 2 < bytes.length ? B64_CHARS[b2 & 63] : '=';
        }
        return out;
    }
    function b64Decode(str) {
        str = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
        var bytes = [];
        var c = 0, buffer = 0;
        for (var i = 0; i < str.length; i++) {
            var ch = str.charAt(i);
            if (ch === '=') break;
            var val = B64_CHARS.indexOf(ch);
            if (val < 0) continue;
            buffer = (buffer << 6) | val;
            c++;
            if (c === 4) {
                bytes.push((buffer >> 16) & 0xFF, (buffer >> 8) & 0xFF, buffer & 0xFF);
                buffer = 0; c = 0;
            }
        }
        if (c === 2) { bytes.push((buffer >> 4) & 0xFF); }
        else if (c === 3) { bytes.push((buffer >> 10) & 0xFF, (buffer >> 2) & 0xFF); }
        return bytesToUtf8(bytes);
    }
    function utf8ToBytes(str) {
        str = String(str);
        var out = [];
        for (var i = 0; i < str.length; i++) {
            var code = str.charCodeAt(i);
            if (code < 0x80) { out.push(code); }
            else if (code < 0x800) {
                out.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F));
            } else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < str.length) {
                var next = str.charCodeAt(i + 1);
                if (next >= 0xDC00 && next <= 0xDFFF) {
                    var cp = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00);
                    out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
                    i++;
                } else { out.push(0xEF, 0xBF, 0xBD); }
            } else {
                out.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F));
            }
        }
        return out;
    }
    function bytesToUtf8(bytes) {
        var out = '';
        for (var i = 0; i < bytes.length;) {
            var b = bytes[i++];
            if (b < 0x80) { out += String.fromCharCode(b); }
            else if (b < 0xE0) {
                var c1 = b & 0x1F;
                if (i < bytes.length) c1 = (c1 << 6) | (bytes[i++] & 0x3F);
                out += String.fromCharCode(c1);
            }
            else if (b < 0xF0) {
                var c2 = b & 0x0F;
                if (i < bytes.length) c2 = (c2 << 6) | (bytes[i++] & 0x3F);
                if (i < bytes.length) c2 = (c2 << 6) | (bytes[i++] & 0x3F);
                out += String.fromCharCode(c2);
            }
            else {
                var c3 = b & 0x07;
                if (i < bytes.length) c3 = (c3 << 6) | (bytes[i++] & 0x3F);
                if (i < bytes.length) c3 = (c3 << 6) | (bytes[i++] & 0x3F);
                if (i < bytes.length) c3 = (c3 << 6) | (bytes[i++] & 0x3F);
                out += String.fromCharCode(c3);
            }
        }
        return out;
    }
    if (typeof global.btoa !== 'function') global.btoa = b64Encode;
    if (typeof global.atob !== 'function') global.atob = b64Decode;

    // ---------- 定时器 ----------
    global.__timers = {};
    global.__timerSeq = 0;
    function __makeTimer(fn, ms, repeat) {
        var id = ++global.__timerSeq;
        global.__timers[id] = { fn: typeof fn === 'function' ? fn : function () {}, repeat: !!repeat };
        bridge.scheduleTimer(id, Math.max(0, ms | 0), repeat);
        return id;
    }
    if (typeof global.setTimeout !== 'function') {
        global.setTimeout = function (fn, ms) { return __makeTimer(fn, ms, false); };
        global.setInterval = function (fn, ms) { return __makeTimer(fn, ms, true); };
        global.clearTimeout = function (id) { delete global.__timers[id]; };
        global.clearInterval = global.clearTimeout;
        global.__runTimer = function (id) {
            var t = global.__timers[id];
            if (!t) { return; }
            if (!t.repeat) { delete global.__timers[id]; }
            try { t.fn(); } catch (e) {
                if (global.console && global.console.error) global.console.error('timer error: ' + e);
            }
        };
    }

    // ---------- URL / URLSearchParams 垫片（QuickJS 自带） ----------
    if (typeof global.URL === 'undefined') {
        function URLSearchParams(query) {
            this._map = {};
            if (typeof query === 'string' && query.length) {
                var parts = query.replace(/^[?#]/, '').split('&');
                for (var i = 0; i < parts.length; i++) {
                    if (!parts[i]) continue;
                    var kv = parts[i].split('=');
                    var k = decodeURIComponent(kv[0]);
                    var v = kv.length > 1 ? decodeURIComponent(kv.slice(1).join('=')) : '';
                    this._map[k] = (this._map[k] === undefined) ? v : this._map[k] + ',' + v;
                }
            }
        }
        URLSearchParams.prototype.append = function (k, v) {
            this._map[k] = (this._map[k] === undefined) ? String(v) : this._map[k] + ',' + v;
        };
        URLSearchParams.prototype.set = function (k, v) { this._map[k] = String(v); };
        URLSearchParams.prototype.get = function (k) { return (k in this._map) ? this._map[k] : null; };
        URLSearchParams.prototype.has = function (k) { return (k in this._map); };
        URLSearchParams.prototype.toString = function () {
            var out = [];
            for (var k in this._map) {
                if (!Object.prototype.hasOwnProperty.call(this._map, k)) continue;
                var v = this._map[k];
                if (typeof v === 'string' && v.indexOf(',') >= 0 && !this._multiAdded) {
                    var arr = v.split(',');
                    for (var i = 0; i < arr.length; i++) out.push(encodeURIComponent(k) + '=' + encodeURIComponent(arr[i]));
                } else {
                    out.push(encodeURIComponent(k) + '=' + encodeURIComponent(v));
                }
            }
            return out.join('&');
        };

        function URL(url, base) {
            var full = url;
            if (base) {
                var b = new URL(String(base));
                if (/^[a-z][a-z0-9+.-]*:/i.test(url)) { full = url; }
                else if (url.charAt(0) === '/') { full = b.protocol + '//' + b.host + url; }
                else {
                    var path = b.pathname.replace(/[^/]*$/, '');
                    full = b.protocol + '//' + b.host + path + url;
                }
            }
            var m = /^([a-z][a-z0-9+.-]*:)?(\/\/[^/?#]*)?([^?#]*)(\?[^#]*)?(#.*)?$/i.exec(full);
            if (!m) throw new Error('Invalid URL: ' + url);
            this._protocol = m[1] ? m[1] : 'http:';
            this._host = m[2] ? m[2].replace(/^\/\//, '') : '';
            this._pathname = m[3] || '';
            this.search = m[4] || '';
            this._searchParams = new URLSearchParams(this.search);
            this.hash = m[5] || '';
            Object.defineProperty(this, 'searchParams', {
                get: (function () { return this._searchParams; }).bind(this)
            });
        }
        Object.defineProperty(URL.prototype, 'protocol', {
            get: function () { return this._protocol; },
            set: function (v) { this._protocol = String(v); }
        });
        Object.defineProperty(URL.prototype, 'host', {
            get: function () { return this._host; },
            set: function (v) { this._host = String(v); }
        });
        Object.defineProperty(URL.prototype, 'hostname', {
            get: function () { return this._host.split(':')[0]; }
        });
        Object.defineProperty(URL.prototype, 'port', {
            get: function () {
                var m = /:(\d+)$/.exec(this._host);
                return m ? m[1] : '';
            }
        });
        Object.defineProperty(URL.prototype, 'pathname', {
            get: function () { return this._pathname; },
            set: function (v) { this._pathname = String(v); }
        });
        Object.defineProperty(URL.prototype, 'href', {
            get: function () { return this.toString(); },
            set: function (v) {
                var u = new URL(String(v));
                this._protocol = u._protocol; this._host = u._host; this._pathname = u._pathname;
                this.search = u.search; this.hash = u.hash;
                this._searchParams = new URLSearchParams(this.search);
            }
        });
        URL.prototype.toString = function () {
            var out = this._protocol;
            if (this._host) out += '//' + this._host;
            out += this._pathname;
            if (this._searchParams && this._searchParams.toString()) out += '?' + this._searchParams.toString();
            if (this.hash) out += this.hash;
            return out;
        };
        global.URL = URL;
        global.URLSearchParams = URLSearchParams;
    }

    // ---------- process 存根 ----------
    if (typeof global.process === 'undefined') {
        global.process = { env: {}, platform: 'android', nextTick: function (fn) { fn(); } };
    }
})(globalThis);