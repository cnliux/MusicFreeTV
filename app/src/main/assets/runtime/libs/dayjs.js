// MusicFreeTV runtime - libs/dayjs.js
// 极简 dayjs 实现：仅支持 dayjs(input) / dayjs.unix(ts) / .format(fmt模板) / .valueOf() / .toDate()。
(function (module) {
    'use strict';

    function toDate(input) {
        if (input === null || input === undefined || input === '') return new Date();
        if (input instanceof Date) return new Date(input.getTime());
        if (typeof input === 'number') return new Date(input);
        if (typeof input === 'string') {
            if (/^\d+$/.test(input.trim())) return new Date(parseInt(input.trim(), 10));
            var d = new Date(input);
            if (!isNaN(d.getTime())) return d;
            var m = /^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})(?:[ T](\d{1,2}):(\d{1,2})(?::(\d{1,2}))?)?/.exec(input);
            if (m) return new Date(+m[1], (+m[2]) - 1, +m[3], m[4] ? +m[4] : 0, m[5] ? +m[5] : 0, m[6] ? +m[6] : 0);
        }
        return new Date();
    }

    function pad2(n) { return n < 10 ? '0' + n : String(n); }

    function formatDate(date, fmt) {
        fmt = fmt || 'YYYY-MM-DDTHH:mm:ssZ';
        var map = {
            YYYY: String(date.getFullYear()),
            MM: pad2(date.getMonth() + 1),
            DD: pad2(date.getDate()),
            HH: pad2(date.getHours()),
            mm: pad2(date.getMinutes()),
            ss: pad2(date.getSeconds()),
            D: String(date.getDate()),
            M: String(date.getMonth() + 1),
            H: String(date.getHours())
        };
        var out = fmt;
        var keys = ['YYYY', 'MM', 'DD', 'HH', 'mm', 'ss', 'D', 'M', 'H'];
        for (var i = 0; i < keys.length; i++) {
            var re = new RegExp(keys[i], 'g');
            out = out.replace(re, map[keys[i]]);
        }
        return out;
    }

    function dayjs(input) {
        var date = toDate(input);
        var wrapper = {
            format: function (fmt) { return formatDate(date, fmt); },
            valueOf: function () { return date.getTime(); },
            unix: function () { return Math.floor(date.getTime() / 1000); },
            toDate: function () { return new Date(date.getTime()); },
            getTime: function () { return date.getTime(); }
        };
        return wrapper;
    }
    dayjs.unix = function (ts) { return dayjs(new Date(ts * 1000)); };
    dayjs.extend = function () {};

    module.exports = dayjs;
})(module);