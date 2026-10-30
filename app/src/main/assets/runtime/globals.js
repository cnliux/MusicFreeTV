// MusicFreeTV runtime - globals.js
// QuickJS 环境下的全局垫片：console / base64 / 定时器 / URL / process 存根。
// 这些能力由原生侧 nativeBridge 提供：
//   nativeBridge.log(level, msg)
//   nativeBridge.scheduleTimer(id, ms)
//   nativeBridge.httpRequest(method, url, headersJson, body)
//   nativeBridge.getUserVariables(platform)

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

    // ---------- URL / URLSearchParams 垫片 ----------
    // QuickJS(2021) 完全没有 WHATWG URL（实测 typeof URL === 'undefined'），两个都得自实现。
    // 【坑】URLSearchParams 必须支持「对象 / 键值对数组 / 另一个 URLSearchParams」入参：
    // 插件里 36DJ、快音写 `new URLSearchParams({classid:1,keyboard:kw,show:'…'}).toString()`，
    // 旧垫片只解析字符串 query，传对象得到空串 → POST body 为空 → 站点回"信息提示"页，
    // 搜索永远 0 条（是垫片不足，不是插件问题）。空格按表单规范编码为 '+'。
    function uspNeedsShim() {
        if (typeof global.URLSearchParams === 'undefined') return true;
        try { return String(new global.URLSearchParams({ a: 1 })) !== 'a=1'; } catch (e) { return true; }
    }
    if (typeof global.URL === 'undefined' || uspNeedsShim()) {
        // application/x-www-form-urlencoded 编码：encodeURIComponent 基础上把空格转 +，
        // 并按规范补转 ! ' ( ) *（encodeURIComponent 对这些不转义）。
        function encForm(s) {
            return encodeURIComponent(String(s)).replace(/%20/g, '+')
                .replace(/[!'()*]/g, function (c) {
                    return '%' + c.charCodeAt(0).toString(16).toUpperCase();
                });
        }
        function decForm(s) {
            var t = String(s).replace(/\+/g, ' ');
            try { return decodeURIComponent(t); } catch (e) { return t; }
        }
        // 有序键值对列表（同名 key 允许重复，符合规范；旧垫片用","拼接同名值是错的）。
        function URLSearchParams(init) {
            this._list = [];
            if (init === undefined || init === null) return;
            if (typeof init === 'string') {
                var q = init.replace(/^[?#]/, '');
                if (!q.length) return;
                var parts = q.split('&');
                for (var i = 0; i < parts.length; i++) {
                    if (!parts[i]) continue;
                    var eq = parts[i].indexOf('=');
                    if (eq < 0) this.append(decForm(parts[i]), '');
                    else this.append(decForm(parts[i].slice(0, eq)), decForm(parts[i].slice(eq + 1)));
                }
                return;
            }
            if (init instanceof URLSearchParams) {
                for (var a = 0; a < init._list.length; a++) {
                    this.append(init._list[a][0], init._list[a][1]);
                }
                return;
            }
            var isSeq = Array.isArray(init) ||
                (typeof Symbol !== 'undefined' && Symbol.iterator && typeof init[Symbol.iterator] === 'function');
            if (isSeq) {
                var pairs = Array.isArray(init) ? init : Array.from(init);
                for (var j = 0; j < pairs.length; j++) {
                    var pair = pairs[j];
                    if (pair === undefined || pair === null) continue;
                    this.append(String(pair[0]), String(pair[1]));
                }
                return;
            }
            if (typeof init === 'object') {
                var keys = Object.keys(init);
                for (var n = 0; n < keys.length; n++) {
                    var val = init[keys[n]];
                    if (Array.isArray(val)) {
                        for (var m = 0; m < val.length; m++) this.append(keys[n], String(val[m]));
                    } else {
                        this.append(keys[n], String(val));
                    }
                }
            }
        }
        URLSearchParams.prototype.append = function (k, v) { this._list.push([String(k), String(v)]); };
        URLSearchParams.prototype.delete = function (k) {
            k = String(k);
            var keep = [];
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] !== k) keep.push(this._list[i]);
            }
            this._list = keep;
        };
        URLSearchParams.prototype.set = function (k, v) {
            k = String(k);
            var first = -1;
            var keep = [];
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] === k) {
                    if (first < 0) { first = keep.length; keep.push([k, String(v)]); }
                } else {
                    keep.push(this._list[i]);
                }
            }
            if (first < 0) keep.push([k, String(v)]);
            this._list = keep;
        };
        URLSearchParams.prototype.get = function (k) {
            k = String(k);
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] === k) return this._list[i][1];
            }
            return null;
        };
        URLSearchParams.prototype.getAll = function (k) {
            k = String(k);
            var out = [];
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] === k) out.push(this._list[i][1]);
            }
            return out;
        };
        URLSearchParams.prototype.has = function (k) {
            k = String(k);
            for (var i = 0; i < this._list.length; i++) {
                if (this._list[i][0] === k) return true;
            }
            return false;
        };
        URLSearchParams.prototype.sort = function () {
            this._list.sort(function (x, y) {
                if (x[0] < y[0]) return -1;
                if (x[0] > y[0]) return 1;
                if (x[1] < y[1]) return -1;
                if (x[1] > y[1]) return 1;
                return 0;
            });
        };
        function makeIter(list) {
            var i = 0;
            var it = {
                next: function () {
                    return i < list.length
                        ? { value: list[i++], done: false }
                        : { value: undefined, done: true };
                }
            };
            if (typeof Symbol !== 'undefined' && Symbol.iterator) it[Symbol.iterator] = function () { return it; };
            return it;
        }
        URLSearchParams.prototype.entries = function () { return makeIter(this._list); };
        URLSearchParams.prototype.keys = function () {
            var arr = [];
            for (var i = 0; i < this._list.length; i++) arr.push(this._list[i][0]);
            return makeIter(arr);
        };
        URLSearchParams.prototype.values = function () {
            var arr = [];
            for (var i = 0; i < this._list.length; i++) arr.push(this._list[i][1]);
            return makeIter(arr);
        };
        URLSearchParams.prototype.forEach = function (cb, thisArg) {
            if (typeof cb !== 'function') return;
            for (var i = 0; i < this._list.length; i++) {
                cb.call(thisArg, this._list[i][1], this._list[i][0], this);
            }
        };
        if (typeof Symbol !== 'undefined' && Symbol.iterator) {
            URLSearchParams.prototype[Symbol.iterator] = function () { return this.entries(); };
        }
        Object.defineProperty(URLSearchParams.prototype, 'size', {
            get: function () { return this._list.length; }
        });
        URLSearchParams.prototype.toString = function () {
            var out = [];
            for (var i = 0; i < this._list.length; i++) {
                out.push(encForm(this._list[i][0]) + '=' + encForm(this._list[i][1]));
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
        // 只在缺失时覆盖：某些引擎自带 URL 但 URLSearchParams 能力不全，
        // 这种情况下只换掉 URLSearchParams，不能把原生 URL 覆盖成简化实现。
        if (typeof global.URL === 'undefined') global.URL = URL;
        if (typeof global.URLSearchParams === 'undefined' || uspNeedsShim()) {
            global.URLSearchParams = URLSearchParams;
        }
    }

    // ---------- TextEncoder / TextDecoder 垫片 ----------
    // QuickJS(2021) 不带 WHATWG Encoding API，但插件常用 TextDecoder 解响应、
    // TextEncoder 造请求体（如 73DJ 报 "TextDecoder is not defined"）。
    // 复用上面的 utf8ToBytes / bytesToUtf8 实现 UTF-8 往返；其它编码标签（gbk 等）
    // 无原生支持，按 UTF-8 处理——比直接 ReferenceError 崩掉更可用。
    function bytesOf(input) {
        if (input === undefined || input === null) return [];
        if (typeof ArrayBuffer !== 'undefined' && input instanceof ArrayBuffer) {
            var view = new Uint8Array(input);
            var out = [];
            for (var i = 0; i < view.length; i++) out.push(view[i]);
            return out;
        }
        if (ArrayBuffer.isView && ArrayBuffer.isView(input)) {
            var o2 = [];
            for (var j = 0; j < input.length; j++) o2.push(input[j] & 0xFF);
            return o2;
        }
        if (typeof input === 'string') return utf8ToBytes(input);
        var o3 = [];
        for (var k = 0; k < input.length; k++) o3.push(input[k] & 0xFF);
        return o3;
    }
    if (typeof global.TextEncoder === 'undefined') {
        var TextEncoder = function () {
            this.encoding = 'utf-8';
        };
        TextEncoder.prototype.encode = function (str) {
            var bytes = utf8ToBytes(str === undefined ? '' : String(str));
            var arr = new Uint8Array(bytes.length);
            for (var i = 0; i < bytes.length; i++) arr[i] = bytes[i];
            return arr;
        };
        TextEncoder.prototype.encodeInto = function (str, dest) {
            var bytes = utf8ToBytes(str === undefined ? '' : String(str));
            var n = Math.min(bytes.length, dest.length);
            for (var i = 0; i < n; i++) dest[i] = bytes[i];
            return { read: n, written: n };
        };
        global.TextEncoder = TextEncoder;
    }
    if (typeof global.TextDecoder === 'undefined') {
        var TextDecoder = function (label) {
            this.encoding = (label ? String(label) : 'utf-8').toLowerCase();
            this.fatal = false;
            this.ignoreBOM = false;
        };
        TextDecoder.prototype.decode = function (input) {
            if (input === undefined || input === null) return '';
            var text = bytesToUtf8(bytesOf(input));
            // UTF-8 BOM（EF BB BF）按规范默认剥掉
            if (text.charCodeAt(0) === 0xFEFF) text = text.slice(1);
            return text;
        };
        global.TextDecoder = TextDecoder;
    }

    // ---------- process 存根 ----------
    if (typeof global.process === 'undefined') {
        global.process = { env: {}, platform: 'android', nextTick: function (fn) { fn(); } };
    }
})(globalThis);