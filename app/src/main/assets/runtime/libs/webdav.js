// MusicFreeTV runtime - libs/webdav.js
// 极简 webdav 客户端实现（PROPFIND 走原生桥，迷你 XML 正则提取）。
// 覆盖 WebDAV.js 插件用到的 createClient / getDirectoryContents / getFileDownloadLink。
(function (module) {
    'use strict';

    var AuthType = { Password: 'password', None: 'none' };

    function stripPrefix(name) {
        return String(name).replace(/^[^:>]*:/, '');
    }

    function xmlRe(xml, tag) {
        var re = new RegExp('<' + tag + '\\b[^>]*>([\\s\\S]*?)<\\/' + tag + '>', 'i');
        var m = re.exec(xml);
        return m ? m[1].trim() : '';
    }

    function parseResponses(xml) {
        var items = [];
        var parts = xml.split(/<[^:>]*:?response\b[^>]*>/i);
        for (var i = 1; i < parts.length; i++) {
            var block = parts[i].split(/<\/[^:>]*:?response>/i)[0];
            if (!block) continue;
            var href = xmlRe(block, 'href');
            var basename = decodeURIComponent(String(href).split('/').filter(Boolean).pop() || '');
            var typeEl = xmlRe(block, 'resourcetype');
            var isDir = /collection/i.test(typeEl);
            var mime = xmlRe(block, 'getcontenttype');
            var sizeStr = xmlRe(block, 'getcontentlength');
            var size = sizeStr ? parseInt(sizeStr, 10) || 0 : 0;
            if (isDir) {
                items.push({ filename: href, basename: basename, type: 'directory', mime: '', size: size });
            } else {
                items.push({ filename: href, basename: basename, type: 'file', mime: mime, size: size });
            }
        }
        return items;
    }

    function createClient(url, options) {
        options = options || {};
        var username = options.username || '';
        var password = options.password || '';
        var base = String(url).replace(/\/+$/, '');

        function authHeader() {
            if (!username) return {};
            var raw = username + ':' + password;
            var b64 = globalThis.btoa(raw);
            return { Authorization: 'Basic ' + b64 };
        }

        function joinPath(path) {
            var p = path ? String(path).replace(/^\/+/, '') : '';
            return p ? base + '/' + p : base;
        }

        return {
            getDirectoryContents: function (path) {
                var href = joinPath(path);
                var headers = Object.assign({}, authHeader(), { Depth: '1' });
                try {
                    var raw = globalThis.nativeBridge.httpRequest('PROPFIND', href, JSON.stringify(headers), '');
                    var parsed = JSON.parse(raw);
                    if (parsed.__error) throw new Error(parsed.__error);
                    return parseResponses(parsed.body);
                } catch (e) {
                    // 原生桥不支持自定义 method 时回退：直接返回空
                    throw e;
                }
            },
            getFileDownloadLink: function (path) {
                var href = joinPath(path);
                if (username) {
                    var scheme = base.indexOf('https://') === 0 ? 'https://' : 'http://';
                    var rest = base.slice(scheme.length);
                    var hostPart = rest.split('/')[0];
                    var after = rest.slice(hostPart.length);
                    var pathPart = String(path) ? '/' + String(path).replace(/^\/+/, '') : '';
                    return scheme + encodeURIComponent(username) + ':' + encodeURIComponent(password) + '@' + hostPart + after + pathPart;
                }
                return href;
            },
            getQuota: function () { return null; }
        };
    }

    module.exports = {
        createClient: createClient,
        AuthType: AuthType
    };
})(module);