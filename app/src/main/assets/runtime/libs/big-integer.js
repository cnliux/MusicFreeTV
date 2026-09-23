// MusicFreeTV runtime - libs/big-integer.js
// 极简 big-integer 实现：以 JS 原生 BigInt 为底层，提供插件用到的链式方法。
//   插件用法示例：
//     bigInt(hexText, 16).modPow(bigInt(d, 16), bigInt(e, 16)).toString(16)
(function (module) {
    'use strict';

    function parseBigInt(input, radix) {
        if (typeof input === 'bigint') return input;
        if (typeof input === 'number') return BigInt(Math.trunc(input));
        if (typeof input === 'boolean') return input ? 1n : 0n;
        if (input === null || input === undefined) return 0n;
        if (typeof input === 'object' && typeof input._v === 'bigint') return input._v;
        var str = String(input).trim();
        if (!radix || radix === 10) {
            try { return BigInt(str); } catch (e) { return 0n; }
        }
        try {
            if (radix === 2) return BigInt('0b' + str.replace(/^[-+]/, function (m) { return m === '-' ? '-' : ''; }));
            if (radix === 16) return BigInt('0x' + str);
            return BigInt(parseInt(str, radix));
        } catch (e) {
            return 0n;
        }
    }

    function W(value) {
        this._v = value;
    }

    function wrap(v) { return new W(v); }
    function toV(x) {
        if (x instanceof W) return x._v;
        if (typeof x === 'bigint') return x;
        return BigInt(x == null ? 0 : (typeof x === 'number' ? Math.trunc(x) : x));
    }

    W.prototype.add = function (x) { return wrap(this._v + toV(x)); };
    W.prototype.plus = W.prototype.add;
    W.prototype.subtract = function (x) { return wrap(this._v - toV(x)); };
    W.prototype.minus = W.prototype.subtract;
    W.prototype.multiply = function (x) { return wrap(this._v * toV(x)); };
    W.prototype.times = W.prototype.multiply;
    W.prototype.divide = function (x) { return wrap(this._v / toV(x)); };
    W.prototype.over = W.prototype.divide;
    W.prototype.mod = function (x) {
        var v = toV(x);
        if (v === 0n) return wrap(this._v);
        var r = this._v % v;
        if (r < 0n) r += v < 0n ? -v : v;
        return wrap(r);
    };
    W.prototype.remainder = W.prototype.mod;
    W.prototype.pow = function (x) {
        var exp = toV(x);
        if (exp > 100000n) throw new Error('big-integer shim: exponent too large');
        return wrap(this._v ** exp);
    };
    W.prototype.modPow = function (exp, m) {
        var base = this._v % toV(m);
        var e = toV(exp);
        var mod = toV(m);
        if (mod <= 0n) throw new Error('big-integer shim: invalid modulus');
        var result = 1n;
        base = ((base % mod) + mod) % mod;
        while (e > 0n) {
            if (e & 1n) result = (result * base) % mod;
            e >>= 1n;
            base = (base * base) % mod;
        }
        return wrap(result);
    };
    W.prototype.and = function (x) { return wrap(this._v & toV(x)); };
    W.prototype.or = function (x) { return wrap(this._v | toV(x)); };
    W.prototype.xor = function (x) { return wrap(this._v ^ toV(x)); };
    W.prototype.not = function () { return wrap(~this._v); };
    W.prototype.shiftLeft = function (n) { return wrap(this._v << BigInt(n)); };
    W.prototype.shiftRight = function (n) { return wrap(this._v >> BigInt(n)); };
    W.prototype.negate = function () { return wrap(-this._v); };
    W.prototype.abs = function () { return wrap(this._v < 0n ? -this._v : this._v); };
    W.prototype.toString = function (radix) {
        if (!radix || radix === 10) return this._v.toString();
        return this._v.toString(radix);
    };
    W.prototype.toJSNumber = function () { return Number(this._v); };
    W.prototype.value = function () { return this._v; };
    W.prototype.equals = function (x) { return this._v === toV(x); };
    W.prototype.eq = W.prototype.equals;
    W.prototype.greater = function (x) { return this._v > toV(x); };
    W.prototype.gt = W.prototype.greater;
    W.prototype.lesser = function (x) { return this._v < toV(x); };
    W.prototype.lt = W.prototype.lesser;
    W.prototype.greaterOrEquals = function (x) { return this._v >= toV(x); };
    W.prototype.geq = W.prototype.greaterOrEquals;
    W.prototype.lesserOrEquals = function (x) { return this._v <= toV(x); };
    W.prototype.leq = W.prototype.lesserOrEquals;
    W.prototype.isNegative = function () { return this._v < 0n; };
    W.prototype.isPositive = function () { return this._v > 0n; };
    W.prototype.isZero = function () { return this._v === 0n; };

    function bigInt(input, radix) {
        return wrap(parseBigInt(input, radix));
    }
    bigInt.zero = function () { return wrap(0n); };
    bigInt.one = function () { return wrap(1n); };
    bigInt.minusOne = function () { return wrap(-1n); };

    module.exports = bigInt;
})(module);