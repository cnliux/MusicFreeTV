// MusicFreeTV runtime - libs/webdav.js
// 极简 webdav 客户端实现（PROPFIND 走原生桥，迷你 XML 正则提取）。
// 覆盖 WebDAV.js 插件用到的 createClient / getDirectoryContents / getFileDownloadLink。
(function (module) {
    'use strict';

    var AuthType = { Password: 'password', None: 'none' };

    var AUDIO_EXT = {
        mp3: 'audio/mpeg', flac: 'audio/flac', wav: 'audio/wav', aac: 'audio/aac',
        m4a: 'audio/mp4', ogg: 'audio/ogg', opus: 'audio/opus', wma: 'audio/x-ms-wma',
        ape: 'audio/x-ape', dsf: 'audio/x-dsf', dff: 'audio/x-dff'
    };

    function xmlRe(xml, tag) {
        var re = new RegExp('<(?:[^:>\\s]+:)?' + tag + '\\b[^>]*>([\\s\\S]*?)<\\/(?:[^:>\\s]+:)?' + tag + '>', 'i');
        var m = re.exec(xml);
        return m ? m[1].trim() : '';
    }

    function guessMime(name, declared) {
        if (declared && /^audio\//i.test(declared)) return declared;
        var ext = String(name).split('.').pop().toLowerCase();
        return AUDIO_EXT[ext] || declared || '';
    }

    function parseResponses(xml) {
        var items = [];
        var parts = xml.split(/<(?:[^:>\s]+:)?response\b[^>]*>/i);
        for (var i = 1; i < parts.length; i++) {
            var block = parts[i].split(/<\/(?:[^:>\s]+:)?response>/i)[0];
            if (!block) continue;
            var href = xmlRe(block, 'href');
            try { href = decodeURIComponent(href); } catch (e) { /* keep raw */ }
            var basename = String(href).split('/').filter(Boolean).pop() || '';
            try { basename = decodeURIComponent(basename); } catch (e) { /* keep */ }
            var typeEl = xmlRe(block, 'resourcetype');
            var isDir = /collection/i.test(typeEl) || /<(?:[^:>\s]+:)?collection\b/i.test(block);
            var mime = xmlRe(block, 'getcontenttype');
            var sizeStr = xmlRe(block, 'getcontentlength');
            var size = sizeStr ? parseInt(sizeStr, 10) || 0 : 0;
            if (isDir) {
                items.push({ filename: href, basename: basename, type: 'directory', mime: '', size: size });
            } else {
                items.push({
                    filename: href,
                    basename: basename,
                    type: 'file',
                    mime: guessMime(basename, mime),
                    size: size
                });
            }
        }
        return items;
    }

    var PROPFIND_BODY =
        '<?xml version="1.0" encoding="utf-8"?>' +
        '<d:propfind xmlns:d="DAV:"><d:prop>' +
        '<d:resourcetype/><d:getcontenttype/><d:getcontentlength/><d:displayname/>' +
        '</d:prop></d:propfind>';

    function createClient(url, options) {
        options = options || {};
        var username = options.username || '';
        var password = options.password || '';
        var token = options.token || '';
        var authType = options.authType || (options.token ? 'token' : 'password');
        var base = String(url).replace(/\/+$/, '');

        function authHeader() {
            if (authType === 'token' || token) return { Authorization: 'Bearer ' + token };
            if (!username) return {};
            var raw = username + ':' + password;
            var b64 = globalThis.btoa(raw);
            return { Authorization: 'Basic ' + b64 };
        }

        function originOf(u) {
            var m = String(u).match(/^(https?:\/\/[^/]+)/i);
            return m ? m[1] : u;
        }

        function joinPath(path) {
            var p = path == null ? '' : String(path);
            if (/^https?:\/\//i.test(p)) return p;
            if (p.charAt(0) === '/') return originOf(base) + p;
            return p ? base + '/' + p.replace(/^\/+/, '') : base;
        }

        function withBasicUserinfo(href) {
            if (!username) return href;
            var m = String(href).match(/^(https?):\/\/([^/]+)(\/.*)?$/i);
            if (!m) return href;
            return m[1] + '://' + encodeURIComponent(username) + ':' + encodeURIComponent(password) + '@' + m[2] + (m[3] || '');
        }

        return {
            getDirectoryContents: function (path) {
                var href = joinPath(path);
                var headers = Object.assign({}, authHeader(), {
                    Depth: '1',
                    'Content-Type': 'application/xml; charset=utf-8'
                });
                var raw = globalThis.nativeBridge.httpRequest('PROPFIND', href, JSON.stringify(headers), PROPFIND_BODY);
                var parsed = JSON.parse(raw);
                if (parsed.__error) throw new Error(parsed.__error);
                if (parsed.status && parsed.status >= 400) {
                    throw new Error('PROPFIND ' + parsed.status + ' ' + (parsed.statusText || ''));
                }
                return parseResponses(parsed.body || '');
            },
            getFileDownloadLink: function (path) {
                var href = joinPath(path);
                if (authType === 'token' || token) return href;
                return withBasicUserinfo(href);
            },
            getQuota: function () { return null; }
        };
    }

    module.exports = {
        createClient: createClient,
        AuthType: AuthType
    };
})(module);
