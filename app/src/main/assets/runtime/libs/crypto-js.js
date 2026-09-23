// MusicFreeTV runtime - libs/crypto-js.js
// 精简版 crypto-js 兼容实现（字节级、无依赖），覆盖本仓库插件用到的：
//   MD5 / SHA1 / SHA256 / HmacSHA1 / HmacSHA256 / HmacMD5
//   AES.encrypt / AES.decrypt（ECB / CBC + PKCS7 / NoPadding）
//   enc.Hex / enc.Base64 / enc.Utf8 / enc.Latin1
//   mode.CBC / mode.ECB / pad.Pkcs7 / pad.NoPadding
// 说明：为插件协议编写的精简实现；如需完整能力可整体替换为官方 crypto-js bundle。
(function (module) {
    'use strict';

    // ---------------- 字节工具 ----------------
    function utf8ToBytes(str) {
        str = String(str); var out = [];
        for (var i = 0; i < str.length; i++) {
            var code = str.charCodeAt(i);
            if (code < 0x80) out.push(code);
            else if (code < 0x800) out.push(0xC0 | (code >> 6), 0x80 | (code & 0x3F));
            else if (code >= 0xD800 && code <= 0xDBFF && i + 1 < str.length) {
                var next = str.charCodeAt(i + 1);
                if (next >= 0xDC00 && next <= 0xDFFF) {
                    var cp = 0x10000 + ((code - 0xD800) << 10) + (next - 0xDC00);
                    out.push(0xF0 | (cp >> 18), 0x80 | ((cp >> 12) & 0x3F), 0x80 | ((cp >> 6) & 0x3F), 0x80 | (cp & 0x3F));
                    i++;
                } else out.push(0xEF, 0xBF, 0xBD);
            } else out.push(0xE0 | (code >> 12), 0x80 | ((code >> 6) & 0x3F), 0x80 | (code & 0x3F));
        }
        return out;
    }
    function bytesToUtf8(bytes) {
        var out = ''; var i = 0;
        while (i < bytes.length) {
            var b = bytes[i++];
            if (b < 0x80) out += String.fromCharCode(b);
            else if (b < 0xE0) out += String.fromCharCode(((b & 0x1F) << 6) | (i < bytes.length ? (bytes[i++] & 0x3F) : 0));
            else if (b < 0xF0) {
                var c = (b & 0x0F) << 12;
                if (i < bytes.length) c |= (bytes[i++] & 0x3F) << 6;
                if (i < bytes.length) c |= (bytes[i++] & 0x3F);
                out += String.fromCharCode(c);
            } else out += String.fromCharCode(0xFFFD);
        }
        return out;
    }
    var BASE64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
    function toHex(bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) { var h = bytes[i].toString(16); s += (bytes[i] < 16 ? '0' : '') + h; } return s; }
    function fromHex(str) {
        str = String(str).replace(/^0x/i, '').replace(/[^0-9a-fA-F]/g, '');
        if (str.length % 2) str = '0' + str;
        var out = [];
        for (var i = 0; i < str.length; i += 2) out.push(parseInt(str.substr(i, 2), 16));
        return out;
    }
    function toBase64(bytes) {
        var out = '';
        for (var i = 0; i < bytes.length; i += 3) {
            var b0 = bytes[i], b1 = i + 1 < bytes.length ? bytes[i + 1] : 0, b2 = i + 2 < bytes.length ? bytes[i + 2] : 0;
            out += BASE64[b0 >> 2];
            out += BASE64[((b0 & 3) << 4) | (b1 >> 4)];
            out += i + 1 < bytes.length ? BASE64[((b1 & 15) << 2) | (b2 >> 6)] : '=';
            out += i + 2 < bytes.length ? BASE64[b2 & 63] : '=';
        }
        return out;
    }
    function fromBase64(str) {
        str = String(str).replace(/[^A-Za-z0-9+/=]/g, '');
        var out = []; var buffer = 0; var c = 0;
        for (var i = 0; i < str.length; i++) {
            var ch = str.charAt(i);
            if (ch === '=') break;
            var val = BASE64.indexOf(ch);
            if (val < 0) continue;
            buffer = (buffer << 6) | val; c++;
            if (c === 4) {
                out.push((buffer >> 16) & 0xFF, (buffer >> 8) & 0xFF, buffer & 0xFF);
                buffer = 0; c = 0;
            }
        }
        if (c === 2) out.push((buffer >> 4) & 0xFF);
        else if (c === 3) out.push((buffer >> 10) & 0xFF, (buffer >> 2) & 0xFF);
        return out;
    }
    function latin1ToBytes(str) { var out = []; for (var i = 0; i < str.length; i++) out.push(str.charCodeAt(i) & 0xFF); return out; }
    function bytesToLatin1(bytes) { var s = ''; for (var i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]); return s; }

    function toUint32(arr, off) {
        return ((arr[off] << 24) | (arr[off + 1] << 16) | (arr[off + 2] << 8) | arr[off + 3]) >>> 0;
    }
    function fromUint32(arr, off, v) {
        arr[off] = (v >>> 24) & 0xFF; arr[off + 1] = (v >>> 16) & 0xFF; arr[off + 2] = (v >>> 8) & 0xFF; arr[off + 3] = v & 0xFF;
    }

    // ---------------- WordArray 兼容 ----------------
    function WordArray(bytes) {
        this.words = bytes;       // 底层为字节数组
        this.sigBytes = bytes.length;
    }
    WordArray.prototype.toString = function (formatter) {
        formatter = formatter || CryptoJs.enc.Hex;
        return formatter.stringify(this);
    };

    // ---------------- 编码器 ----------------
    var enc = {
        Hex: {
            stringify: function (wa) { return toHex(wa.words); },
            parse: function (str) { return new WordArray(fromHex(str)); }
        },
        Base64: {
            stringify: function (wa) { return toBase64(wa.words); },
            parse: function (str) { return new WordArray(fromBase64(str)); }
        },
        Utf8: {
            stringify: function (wa) { return bytesToUtf8(wa.words); },
            parse: function (str) { return new WordArray(utf8ToBytes(str)); }
        },
        Latin1: {
            stringify: function (wa) { return bytesToLatin1(wa.words); },
            parse: function (str) { return new WordArray(latin1ToBytes(str)); }
        }
    };

    function asBytes(input) {
        if (input instanceof WordArray) return input.words;
        if (typeof input === 'string') return utf8ToBytes(input);
        if (typeof input === 'number') return [input & 0xFF];
        if (input && typeof input === 'object' && input.words && typeof input.sigBytes === 'number') return input.words;
        return utf8ToBytes(String(input));
    }

    // ---------------- MD5 ----------------
    var MD5_S = [7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21];
    var MD5_K = [];
    for (var kk = 0; kk < 64; kk++) MD5_K[kk] = Math.floor(Math.abs(Math.sin(kk + 1)) * 4294967296);
    function md5(msgBytes) {
        var bytes = msgBytes.slice();
        var bitLen = bytes.length * 8;
        bytes.push(0x80);
        while (bytes.length % 64 !== 56) bytes.push(0);
        for (var bi = 0; bi < 8; bi++) bytes.push((bitLen >>> (bi * 8)) & 0xFF);

        var h0 = 0x67452301, h1 = 0xefcdab89, h2 = 0x98badcfe, h3 = 0x10325476;
        for (var off = 0; off < bytes.length; off += 64) {
            var M = new Array(16);
            for (var j = 0; j < 16; j++) {
                M[j] = bytes[off + j * 4] | (bytes[off + j * 4 + 1] << 8) |
                    (bytes[off + j * 4 + 2] << 16) | (bytes[off + j * 4 + 3] << 24);
            }
            var A = h0, B = h1, C = h2, D = h3;
            for (var k = 0; k < 64; k++) {
                var F, g;
                if (k < 16) { F = (B & C) | (~B & D); g = k; }
                else if (k < 32) { F = (D & B) | (~D & C); g = (5 * k + 1) % 16; }
                else if (k < 48) { F = B ^ C ^ D; g = (3 * k + 5) % 16; }
                else { F = C ^ (B | ~D); g = (7 * k) % 16; }
                var e = D, sb = D, sc = C;
                // 位旋转
                var rot = (A + F + MD5_K[k] + M[g]) | 0;
                var s = MD5_S[k];
                var tmp = ((rot << s) | (rot >>> (32 - s))) | 0;
                D = C; C = B;
                B = (B + tmp) | 0;
                A = e;
            }
            h0 = (h0 + A) | 0; h1 = (h1 + B) | 0; h2 = (h2 + C) | 0; h3 = (h3 + D) | 0;
        }
        var out = new Array(16);
        fromUint32(out, 0, h0 >>> 0); fromUint32(out, 4, h1 >>> 0);
        fromUint32(out, 8, h2 >>> 0); fromUint32(out, 12, h3 >>> 0);
        return out;
    }

    // ---------------- SHA1 ----------------
    function sha1(msgBytes) {
        var bytes = msgBytes.slice();
        var bitLen = bytes.length * 8;
        bytes.push(0x80);
        while (bytes.length % 64 !== 56) bytes.push(0);
        for (var bi = 0; bi < 8; bi++) bytes.push((bitLen >>> (56 - bi * 8)) & 0xFF);
        var h0 = 0x67452301, h1 = 0xEFCDAB89, h2 = 0x98BADCFE, h3 = 0x10325476, h4 = 0xC3D2E1F0;
        for (var off = 0; off < bytes.length; off += 64) {
            var w = new Array(80);
            for (var i = 0; i < 16; i++) w[i] = toUint32(bytes, off + i * 4);
            for (var t = 16; t < 80; t++) {
                var x = w[t - 3] ^ w[t - 8] ^ w[t - 14] ^ w[t - 16];
                w[t] = ((x << 1) | (x >>> 31)) >>> 0;
            }
            var a = h0, b = h1, c = h2, d = h3, e = h4;
            for (var t2 = 0; t2 < 80; t2++) {
                var f, k;
                if (t2 < 20) { f = (b & c) | (~b & d); k = 0x5A827999; }
                else if (t2 < 40) { f = b ^ c ^ d; k = 0x6ED9EBA1; }
                else if (t2 < 60) { f = (b & c) | (b & d) | (c & d); k = 0x8F1BBCDC; }
                else { f = b ^ c ^ d; k = 0xCA62C1D6; }
                var temp = ((((a << 5) | (a >>> 27)) + f + e + k + w[t2]) | 0) >>> 0;
                e = d; d = c;
                c = ((b << 30) | (b >>> 2)) >>> 0;
                b = a; a = temp;
            }
            h0 = (h0 + a) | 0; h1 = (h1 + b) | 0; h2 = (h2 + c) | 0; h3 = (h3 + d) | 0; h4 = (h4 + e) | 0;
        }
        var out = new Array(20);
        fromUint32(out, 0, h0 >>> 0); fromUint32(out, 4, h1 >>> 0);
        fromUint32(out, 8, h2 >>> 0); fromUint32(out, 12, h3 >>> 0); fromUint32(out, 16, h4 >>> 0);
        return out;
    }

    // ---------------- SHA256 ----------------
    var SHA256_K = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2];
    function rotr32(x, n) { return ((x >>> n) | (x << (32 - n))) >>> 0; }
    function sha256(msgBytes) {
        var bytes = msgBytes.slice();
        var bitLen = bytes.length * 8;
        bytes.push(0x80);
        while (bytes.length % 64 !== 56) bytes.push(0);
        for (var bi = 7; bi >= 0; bi--) bytes.push((bitLen >>> (bi * 8)) & 0xFF);
        var H = [
            0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
            0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
        for (var off = 0; off < bytes.length; off += 64) {
            var w = new Array(64);
            for (var i = 0; i < 16; i++) w[i] = toUint32(bytes, off + i * 4);
            for (var t = 16; t < 64; t++) {
                var s0 = rotr32(w[t - 15], 7) ^ rotr32(w[t - 15], 18) ^ (w[t - 15] >>> 3);
                var s1 = rotr32(w[t - 2], 17) ^ rotr32(w[t - 2], 19) ^ (w[t - 2] >>> 10);
                w[t] = (w[t - 16] + s0 + w[t - 7] + s1) | 0;
            }
            var a = H[0], b = H[1], c = H[2], d = H[3],
                e = H[4], f = H[5], g = H[6], h = H[7];
            for (var t2 = 0; t2 < 64; t2++) {
                var S1 = rotr32(e, 6) ^ rotr32(e, 11) ^ rotr32(e, 25);
                var ch = (e & f) ^ (~e & g);
                var t1 = (h + S1 + ch + SHA256_K[t2] + w[t2]) | 0;
                var S0 = rotr32(a, 2) ^ rotr32(a, 13) ^ rotr32(a, 22);
                var maj = (a & b) ^ (a & c) ^ (b & c);
                var t2v = (S0 + maj) | 0;
                h = g; g = f; f = e; e = (d + t1) | 0;
                d = c; c = b; b = a; a = (t1 + t2v) | 0;
            }
            H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
            H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
        }
        var out = new Array(32);
        for (var q = 0; q < 8; q++) fromUint32(out, q * 4, H[q] >>> 0);
        return out;
    }

    // ---------------- HMAC ----------------
    function hmac(hashFn, blockSize, keyBytes, msgBytes) {
        var key = keyBytes.slice();
        if (key.length > blockSize) key = hashFn(key);
        var ipad = [];
        var opad = [];
        for (var i = 0; i < blockSize; i++) {
            var b = i < key.length ? key[i] : 0;
            ipad.push(b ^ 0x36);
            opad.push(b ^ 0x5c);
        }
        var inner = ipad.concat(msgBytes);
        return hashFn(opad.concat(hashFn(inner)));
    }

    // ---------------- AES (FIPS-197, 字节级) ----------------
    var SBOX = [
        0x63, 0x7c, 0x77, 0x7b, 0xf2, 0x6b, 0x6f, 0xc5, 0x30, 0x01, 0x67, 0x2b, 0xfe, 0xd7, 0xab, 0x76,
        0xca, 0x82, 0xc9, 0x7d, 0xfa, 0x59, 0x47, 0xf0, 0xad, 0xd4, 0xa2, 0xaf, 0x9c, 0xa4, 0x72, 0xc0,
        0xb7, 0xfd, 0x93, 0x26, 0x36, 0x3f, 0xf7, 0xcc, 0x34, 0xa5, 0xe5, 0xf1, 0x71, 0xd8, 0x31, 0x15,
        0x04, 0xc7, 0x23, 0xc3, 0x18, 0x96, 0x05, 0x9a, 0x07, 0x12, 0x80, 0xe2, 0xeb, 0x27, 0xb2, 0x75,
        0x09, 0x83, 0x2c, 0x1a, 0x1b, 0x6e, 0x5a, 0xa0, 0x52, 0x3b, 0xd6, 0xb3, 0x29, 0xe3, 0x2f, 0x84,
        0x53, 0xd1, 0x00, 0xed, 0x20, 0xfc, 0xb1, 0x5b, 0x6a, 0xcb, 0xbe, 0x39, 0x4a, 0x4c, 0x58, 0xcf,
        0xd0, 0xef, 0xaa, 0xfb, 0x43, 0x4d, 0x33, 0x85, 0x45, 0xf9, 0x02, 0x7f, 0x50, 0x3c, 0x9f, 0xa8,
        0x51, 0xa3, 0x40, 0x8f, 0x92, 0x9d, 0x38, 0xf5, 0xbc, 0xb6, 0xda, 0x21, 0x10, 0xff, 0xf3, 0xd2,
        0xcd, 0x0c, 0x13, 0xec, 0x5f, 0x97, 0x44, 0x17, 0xc4, 0xa7, 0x7e, 0x3d, 0x64, 0x5d, 0x19, 0x73,
        0x60, 0x81, 0x4f, 0xdc, 0x22, 0x2a, 0x90, 0x88, 0x46, 0xee, 0xb8, 0x14, 0xde, 0x5e, 0x0b, 0xdb,
        0xe0, 0x32, 0x3a, 0x0a, 0x49, 0x06, 0x24, 0x5c, 0xc2, 0xd3, 0xac, 0x62, 0x91, 0x95, 0xe4, 0x79,
        0xe7, 0xc8, 0x37, 0x6d, 0x8d, 0xd5, 0x4e, 0xa9, 0x6c, 0x56, 0xf4, 0xea, 0x65, 0x7a, 0xae, 0x08,
        0xba, 0x78, 0x25, 0x2e, 0x1c, 0xa6, 0xb4, 0xc6, 0xe8, 0xdd, 0x74, 0x1f, 0x4b, 0xbd, 0x8b, 0x8a,
        0x70, 0x3e, 0xb5, 0x66, 0x48, 0x03, 0xf6, 0x0e, 0x61, 0x35, 0x57, 0xb9, 0x86, 0xc1, 0x1d, 0x9e,
        0xe1, 0xf8, 0x98, 0x11, 0x69, 0xd9, 0x8e, 0x94, 0x9b, 0x1e, 0x87, 0xe9, 0xce, 0x55, 0x28, 0xdf,
        0x8c, 0xa1, 0x89, 0x0d, 0xbf, 0xe6, 0x42, 0x68, 0x41, 0x99, 0x2d, 0x0f, 0xb0, 0x54, 0xbb, 0x16];
    var INV_SBOX = [
        0x52, 0x09, 0x6a, 0xd5, 0x30, 0x36, 0xa5, 0x38, 0xbf, 0x40, 0xa3, 0x9e, 0x81, 0xf3, 0xd7, 0xfb,
        0x7c, 0xe3, 0x39, 0x82, 0x9b, 0x2f, 0xff, 0x87, 0x34, 0x8e, 0x43, 0x44, 0xc4, 0xde, 0xe9, 0xcb,
        0x54, 0x7b, 0x94, 0x32, 0xa6, 0xc2, 0x23, 0x3d, 0xee, 0x4c, 0x95, 0x0b, 0x42, 0xfa, 0xc3, 0x4e,
        0x08, 0x2e, 0xa1, 0x66, 0x28, 0xd9, 0x24, 0xb2, 0x76, 0x5b, 0xa2, 0x49, 0x6d, 0x8b, 0xd1, 0x25,
        0x72, 0xf8, 0xf6, 0x64, 0x86, 0x68, 0x98, 0x16, 0xd4, 0xa4, 0x5c, 0xcc, 0x5d, 0x65, 0xb6, 0x92,
        0x6c, 0x70, 0x48, 0x50, 0xfd, 0xed, 0xb9, 0xda, 0x5e, 0x15, 0x46, 0x57, 0xa7, 0x8d, 0x9d, 0x84,
        0x90, 0xd8, 0xab, 0x00, 0x8c, 0xbc, 0xd3, 0x0a, 0xf7, 0xe4, 0x58, 0x05, 0xb8, 0xb3, 0x45, 0x06,
        0xd0, 0x2c, 0x1e, 0x8f, 0xca, 0x3f, 0x0f, 0x02, 0xc1, 0xaf, 0xbd, 0x03, 0x01, 0x13, 0x8a, 0x6b,
        0x3a, 0x91, 0x11, 0x41, 0x4f, 0x67, 0xdc, 0xea, 0x97, 0xf2, 0xcf, 0xce, 0xf0, 0xb4, 0xe6, 0x73,
        0x96, 0xac, 0x74, 0x22, 0xe7, 0xad, 0x35, 0x85, 0xe2, 0xf9, 0x37, 0xe8, 0x1c, 0x75, 0xdf, 0x6e,
        0x47, 0xf1, 0x1a, 0x71, 0x1d, 0x29, 0xc5, 0x89, 0x6f, 0xb7, 0x62, 0x0e, 0xaa, 0x18, 0xbe, 0x1b,
        0xfc, 0x56, 0x3e, 0x4b, 0xc6, 0xd2, 0x79, 0x20, 0x9a, 0xdb, 0xc0, 0xfe, 0x78, 0xcd, 0x5a, 0xf4,
        0x1f, 0xdd, 0xa8, 0x33, 0x88, 0x07, 0xc7, 0x31, 0xb1, 0x12, 0x10, 0x59, 0x27, 0x80, 0xec, 0x5f,
        0x60, 0x51, 0x7f, 0xa9, 0x19, 0xb5, 0x4a, 0x0d, 0x2d, 0xe5, 0x7a, 0x9f, 0x93, 0xc9, 0x9c, 0xef,
        0xa0, 0xe0, 0x3b, 0x4d, 0xae, 0x2a, 0xf5, 0xb0, 0xc8, 0xeb, 0xbb, 0x3c, 0x83, 0x53, 0x99, 0x61,
        0x17, 0x2b, 0x04, 0x7e, 0xba, 0x77, 0xd6, 0x26, 0xe1, 0x69, 0x14, 0x63, 0x55, 0x21, 0x0c, 0x7d];
    var RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36, 0x6c, 0xd8, 0xab, 0x4d, 0x9a, 0x2f, 0x5e, 0xbc, 0x63, 0xc6];

    function xtime(a) { a = a & 0xFF; var r = (a << 1) & 0xFF; return (a & 0x80) ? (r ^ 0x1b) : r; }

    function keyExpansion(key) {
        var Nk = key.length / 4;
        var Nr = Nk + 6;
        var w = [];  // Nr*4 words, each word = 4 bytes
        for (var i = 0; i < Nk; i++) w.push([key[i * 4], key[i * 4 + 1], key[i * 4 + 2], key[i * 4 + 3]]);
        for (var j = Nk; j < (Nr + 1) * 4; j++) {
            var temp = w[j - 1].slice();
            if (j % Nk === 0) {
                temp = [SBOX[temp[1]], SBOX[temp[2]], SBOX[temp[3]], SBOX[temp[0]]];
                temp[0] ^= RCON[(j / Nk) - 1];
            } else if (Nk > 6 && j % Nk === 4) {
                temp = [SBOX[temp[0]], SBOX[temp[1]], SBOX[temp[2]], SBOX[temp[3]]];
            }
            var prev = w[j - Nk];
            w.push([prev[0] ^ temp[0], prev[1] ^ temp[1], prev[2] ^ temp[2], prev[3] ^ temp[3]]);
        }
        return { Nr: Nr, w: w };
    }

    function addRoundKey(state, w, round) {
        for (var c = 0; c < 4; c++) {
            var word = w[round * 4 + c];
            for (var r = 0; r < 4; r++) state[r][c] ^= word[r];
        }
    }
    function subBytes(state) {
        for (var r = 0; r < 4; r++) for (var c = 0; c < 4; c++) state[r][c] = SBOX[state[r][c]];
    }
    function invSubBytes(state) {
        for (var r = 0; r < 4; r++) for (var c = 0; c < 4; c++) state[r][c] = INV_SBOX[state[r][c]];
    }
    function shiftRows(state) {
        var t;
        t = state[1][0]; state[1][0] = state[1][1]; state[1][1] = state[1][2]; state[1][2] = state[1][3]; state[1][3] = t;
        t = state[2][0]; state[2][0] = state[2][2]; state[2][2] = t;
        t = state[2][1]; state[2][1] = state[2][3]; state[2][3] = t;
        t = state[3][0]; state[3][0] = state[3][3]; state[3][3] = state[3][2]; state[3][2] = state[3][1]; state[3][1] = t;
    }
    function invShiftRows(state) {
        var t;
        t = state[1][3]; state[1][3] = state[1][2]; state[1][2] = state[1][1]; state[1][1] = state[1][0]; state[1][0] = t;
        t = state[2][0]; state[2][0] = state[2][2]; state[2][2] = t;
        t = state[2][1]; state[2][1] = state[2][3]; state[2][3] = t;
        t = state[3][3]; state[3][3] = state[3][0]; state[3][0] = state[3][1]; state[3][1] = state[3][2]; state[3][2] = t;
    }
    function mixColumns(state) {
        for (var c = 0; c < 4; c++) {
            var a0 = state[0][c], a1 = state[1][c], a2 = state[2][c], a3 = state[3][c];
            state[0][c] = xtime(a0) ^ (xtime(a1) ^ a1) ^ a2 ^ a3;
            state[1][c] = a0 ^ xtime(a1) ^ (xtime(a2) ^ a2) ^ a3;
            state[2][c] = a0 ^ a1 ^ xtime(a2) ^ (xtime(a3) ^ a3);
            state[3][c] = (xtime(a0) ^ a0) ^ a1 ^ a2 ^ xtime(a3);
        }
    }
    function invMixColumns(state) {
        for (var c = 0; c < 4; c++) {
            var a0 = state[0][c], a1 = state[1][c], a2 = state[2][c], a3 = state[3][c];
            state[0][c] = gmul(a0, 14) ^ gmul(a1, 11) ^ gmul(a2, 13) ^ gmul(a3, 9);
            state[1][c] = gmul(a0, 9) ^ gmul(a1, 14) ^ gmul(a2, 11) ^ gmul(a3, 13);
            state[2][c] = gmul(a0, 13) ^ gmul(a1, 9) ^ gmul(a2, 14) ^ gmul(a3, 11);
            state[3][c] = gmul(a0, 11) ^ gmul(a1, 13) ^ gmul(a2, 9) ^ gmul(a3, 14);
        }
    }
    function gmul(a, b) {
        var p = 0; a = a & 0xFF; b = b & 0xFF;
        while (b > 0) {
            if (b & 1) p ^= a;
            a = xtime(a);
            b >>= 1;
        }
        return p & 0xFF;
    }

    function aesBlockEncrypt(input, ctx) {
        var state = [[], [], [], []];
        for (var r = 0; r < 4; r++) for (var c = 0; c < 4; c++) state[r][c] = input[c * 4 + r];
        addRoundKey(state, ctx.w, 0);
        for (var round = 1; round < ctx.Nr; round++) {
            subBytes(state); shiftRows(state); mixColumns(state); addRoundKey(state, ctx.w, round);
        }
        subBytes(state); shiftRows(state); addRoundKey(state, ctx.w, ctx.Nr);
        var out = new Array(16);
        for (var r2 = 0; r2 < 4; r2++) for (var c2 = 0; c2 < 4; c2++) out[c2 * 4 + r2] = state[r2][c2];
        return out;
    }
    function aesBlockDecrypt(input, ctx) {
        var state = [[], [], [], []];
        for (var r = 0; r < 4; r++) for (var c = 0; c < 4; c++) state[r][c] = input[c * 4 + r];
        addRoundKey(state, ctx.w, ctx.Nr);
        for (var round = ctx.Nr - 1; round > 0; round--) {
            invShiftRows(state); invSubBytes(state); addRoundKey(state, ctx.w, round); invMixColumns(state);
        }
        invShiftRows(state); invSubBytes(state); addRoundKey(state, ctx.w, 0);
        var out = new Array(16);
        for (var r2 = 0; r2 < 4; r2++) for (var c2 = 0; c2 < 4; c2++) out[c2 * 4 + r2] = state[r2][c2];
        return out;
    }

    // PKCS7
    function pkcs7Pad(bytes, blockSize) {
        var padLen = blockSize - (bytes.length % blockSize);
        if (padLen === 0) padLen = blockSize;
        var out = bytes.slice();
        for (var i = 0; i < padLen; i++) out.push(padLen);
        return out;
    }
    function pkcs7Unpad(bytes) {
        if (bytes.length === 0) return bytes;
        var padLen = bytes[bytes.length - 1];
        if (padLen < 1 || padLen > 16) return bytes;
        for (var i = bytes.length - padLen; i < bytes.length; i++) {
            if (bytes[i] !== padLen) return bytes;
        }
        return bytes.slice(0, bytes.length - padLen);
    }

    function aesCipher(data, ctx, iv, isEncrypt) {
        var block = function (b) { return isEncrypt ? aesBlockEncrypt(b, ctx) : aesBlockDecrypt(b, ctx); };
        var out = [];
        var prev = iv ? iv.slice() : new Array(16).fill(0);
        for (var off = 0; off < data.length; off += 16) {
            var input = data.slice(off, off + 16);
            if (iv) {
                for (var i = 0; i < 16; i++) input[i] = isEncrypt ? (input[i] ^ prev[i]) : input[i];
            }
            var result = block(input);
            if (iv && !isEncrypt) {
                for (var j = 0; j < 16; j++) result[j] = result[j] ^ prev[j];
            }
            prev = isEncrypt ? result.slice() : data.slice(off, off + 16);
            out = out.concat(result);
        }
        return out;
    }

    var mode = { CBC: 'CBC', ECB: 'ECB', CFB: 'CFB' };
    var pad = {
        Pkcs7: 'pkcs7',
        NoPadding: 'nopadding',
        ZeroPadding: 'zeropadding',
        AnsiX923: 'ansix923',
        Iso10126: 'iso10126',
        Iso97971: 'iso97971'
    };

    function doAes(message, key, cfg, isEncrypt) {
        var data = asBytes(message);
        var keyBytes = asBytes(key);
        if (keyBytes.length !== 16 && keyBytes.length !== 24 && keyBytes.length !== 32) {
            // crypto-js 会对过短 key 做调整，这里简单拒绝
            throw new Error('crypto-js shim: AES key must be 16/24/32 bytes');
        }
        var cryptoMode = (cfg && cfg.mode) || mode.CBC;
        var padding = (cfg && cfg.padding) || pad.Pkcs7;
        var iv = (cfg && cfg.iv) ? asBytes(cfg.iv) : null;
        var ctx = keyExpansion(keyBytes);

        var useIv;
        if (cryptoMode === mode.ECB) {
            useIv = null;
        } else {
            useIv = iv || new Array(16).fill(0);
        }
        var input = data;
        if (isEncrypt) {
            input = padding === pad.Pkcs7 ? pkcs7Pad(data, 16) : data;
            if (input.length % 16 !== 0) throw new Error('crypto-js shim: invalid AES block'); 
        }
        var r = aesCipher(input, ctx, useIv, isEncrypt);
        if (!isEncrypt) {
            r = padding === pad.Pkcs7 ? pkcs7Unpad(r) : r;
        }
        return r;
    }

    // ---------------- CipherParams 兼容 ----------------
    function CipherParams(cipherBytes) {
        this.ciphertext = new WordArray(cipherBytes);
    }
    CipherParams.prototype.toString = function (formatter) {
        formatter = formatter || enc.Base64;
        return formatter.stringify(this.ciphertext);
    };

    // ---------------- 对外 API ----------------
    var CryptoJs = {
        enc: enc,
        mode: mode,
        pad: pad,

        MD5: function (msg) { return new WordArray(md5(asBytes(msg))); },
        SHA1: function (msg) { return new WordArray(sha1(asBytes(msg))); },
        SHA256: function (msg) { return new WordArray(sha256(asBytes(msg))); },

        HmacMD5: function (msg, key) { return new WordArray(hmac(md5, 64, asBytes(key), asBytes(msg))); },
        HmacSHA1: function (msg, key) { return new WordArray(hmac(sha1, 64, asBytes(key), asBytes(msg))); },
        HmacSHA256: function (msg, key) { return new WordArray(hmac(sha256, 64, asBytes(key), asBytes(msg))); },

        AES: {
            encrypt: function (message, key, cfg) {
                return new CipherParams(doAes(message, key, cfg || {}, true));
            },
            decrypt: function (ciphertext, key, cfg) {
                var bytes = asBytes(ciphertext);
                return new WordArray(doAes(bytes, key, cfg || {}, false));
            }
        },

        lib: {
            WordArray: WordArray,
            CipherParams: CipherParams
        }
    };

    module.exports = CryptoJs;
})(module);