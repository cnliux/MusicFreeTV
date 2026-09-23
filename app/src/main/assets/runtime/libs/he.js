// MusicFreeTV runtime - libs/he.js
// 极简 he 实现：支持 he.decode / he.encode（常用命名实体 + 数字实体）。
(function (module) {
    'use strict';

    var NAMED = {
        amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0',
        copy: '\u00a9', reg: '\u00ae', trade: '\u2122', deg: '\u00b0',
        middot: '\u00b7', bull: '\u2022', hellip: '\u2026', ndash: '\u2013', mdash: '\u2014',
        lsquo: '\u2018', rsquo: '\u2019', ldquo: '\u201c', rdquo: '\u201d',
        laquo: '\u00ab', raquo: '\u00bb', times: '\u00d7', divide: '\u00f7',
        eacute: '\u00e9', egrave: '\u00e8', ecirc: '\u00ea', euml: '\u00eb',
        agrave: '\u00e0', aacute: '\u00e1', acirc: '\u00e2', auml: '\u00e4',
        igrave: '\u00ec', iacute: '\u00ed', ouml: '\u00f6', uuml: '\u00fc',
        szlig: '\u00df', ntilde: '\u00f1', ccedil: '\u00e7', yen: '\u00a5',
        euro: '\u20ac', pound: '\u00a3', cent: '\u00a2', star: '\u2605', hearts: '\u2665'
    };

    function decode(str) {
        if (typeof str !== 'string') return String(str);
        return str.replace(/&(#x?[0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);?/g, function (whole, ent) {
            if (ent.charAt(0) === '#') {
                var num;
                if (ent.charAt(1) === 'x' || ent.charAt(1) === 'X') {
                    num = parseInt(ent.slice(2), 16);
                } else {
                    num = parseInt(ent.slice(1), 10);
                }
                if (isNaN(num)) return whole;
                try { return String.fromCodePoint(num); } catch (e) { return whole; }
            }
            var lower = ent.toLowerCase();
            if (Object.prototype.hasOwnProperty.call(NAMED, lower)) return NAMED[lower];
            return whole;
        });
    }

    function encode(str) {
        if (typeof str !== 'string') return String(str);
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&apos;');
    }

    module.exports = { decode: decode, encode: encode };
})(module);