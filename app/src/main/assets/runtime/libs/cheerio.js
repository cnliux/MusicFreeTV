// MusicFreeTV runtime - libs/cheerio.js
// 精简 cheerio 兼容实现：轻量 HTML 解析 + 常用选择器子集。
// 覆盖插件常用 API：
//   cheerio.load(html) -> $(sel)
//   $(sel) / $(el) / .find() / .children() / .first() / .eq() / .slice() / .toArray()
//   .text() / .html() / .attr() / .data()
//   .map(fn) / .each(fn) / .length
// 选择器支持：标签、#id、.class、[attr]、[attr=val]、[attr^=/=$=/*=val]、
//            后代(空格)、子元素(>)、逗号组合。
// 说明：如需完整 CSS 能力请替换为官方 cheerio bundle。
(function (module) {
    'use strict';

    var VOID_TAGS = { br: 1, img: 1, input: 1, meta: 1, link: 1, hr: 1, area: 1, base: 1, col: 1,
        embed: 1, source: 1, track: 1, wbr: 1, param: 1, frame: 1 };
    var RAW_TAGS = { script: 1, style: 1, textarea: 1, title: 1 };

    function Element(tag, attrs, parent, isText, text) {
        this.tag = tag;
        this.attrs = attrs || {};
        this.children = [];
        this.parent = parent || null;
        this.isText = !!isText;
        this.text = isText ? text : null;
    }

    function parseAttrs(rest) {
        var attrs = {};
        var re = /([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))|([\w:-]+)/g;
        var m;
        while ((m = re.exec(rest)) !== null) {
            if (m[1] !== undefined) {
                attrs[m[1]] = m[2] !== undefined ? m[2] : (m[3] !== undefined ? m[3] : (m[4] !== undefined ? m[4] : ''));
            } else if (m[5] !== undefined) {
                attrs[m[5]] = '';
            }
        }
        return attrs;
    }

    function parseHtml(html) {
        var root = new Element('#root', {}, null, false, null);
        var stack = [root];
        var i = 0;
        var len = html.length;
        while (i < len) {
            var ch = html.charAt(i);
            if (ch === '<') {
                if (html.charAt(i + 1) === '/') {
                    var cm = /^<\/([\w-]+)[^>]*>/i.exec(html.slice(i));
                    if (cm) {
                        var tagName = cm[1].toLowerCase();
                        var idx = stack.length - 1;
                        while (idx > 0 && stack[idx].tag !== tagName) idx--;
                        if (idx > 0) {
                            stack.length = idx;
                        }
                        i += cm[0].length;
                        continue;
                    }
                }
                if (html.charAt(i + 1) === '!') {
                    var comment = /^<!--([\s\S]*?)-->/.exec(html.slice(i));
                    if (comment) { i += comment[0].length; continue; }
                    if (/^<!DOCTYPE/i.test(html.slice(i))) { var dt = html.indexOf('>', i); i = dt < 0 ? len : dt + 1; continue; }
                    i++; continue;
                }
                var tagM = /^<([\w-]+)([^>]*)>/i.exec(html.slice(i));
                if (tagM) {
                    var tag = tagM[1].toLowerCase();
                    var rest = tagM[2];
                    // HTML 规范：非 void 元素上的自闭合斜杠会被忽略（浏览器/cheerio 都把
                    // `<a href="x" />` 当开标签）。不少站点（如 73DJ，ASP.NET 输出）正是
                    // 这么写，旧实现按 XHTML 语义自闭合，导致 <a/> 内的标题全被丢成兄弟
                    // 文本节点，插件解析出 0 首（真实案例：73DJ 搜索 12 条只回 0 条）。
                    var selfClose = VOID_TAGS[tag] === 1 && /\/\s*$/.test(rest);
                    var parent = stack.length ? stack[stack.length - 1] : root;
                    var el = new Element(tag, parseAttrs(rest), parent, false, null);
                    parent.children.push(el);
                    i += tagM[0].length;
                    if (RAW_TAGS[tag] && !selfClose) {
                        var closeIdx = html.indexOf('</' + tag + '>', i);
                        if (closeIdx >= 0) {
                            el.children.push(new Element('#text', {}, el, true, html.slice(i, closeIdx)));
                            i = closeIdx + tag.length + 3;
                        } else {
                            el.children.push(new Element('#text', {}, el, true, html.slice(i)));
                            i = len;
                        }
                    } else if (!selfClose && !VOID_TAGS[tag]) {
                        stack.push(el);
                    }
                    continue;
                }
                i++;
            } else {
                var textEnd = html.indexOf('<', i);
                if (textEnd < 0) textEnd = len;
                if (textEnd > i && stack.length) {
                    var tn = new Element('#text', {}, stack[stack.length - 1], true, html.slice(i, textEnd));
                    stack[stack.length - 1].children.push(tn);
                }
                i = textEnd;
            }
        }
        return root;
    }

    // ---------------- 元素文本/序列化 ----------------
    function collectText(el, out) {
        if (!el) return;
        if (el.isText) { out.push(el.text); return; }
        if (el.tag === 'script' || el.tag === 'style') return;
        for (var i = 0; i < el.children.length; i++) collectText(el.children[i], out);
    }
    function serialize(el) {
        if (el.isText) return htmlEscapeText(el.text);
        var s = '<' + el.tag;
        for (var k in el.attrs) {
            if (Object.prototype.hasOwnProperty.call(el.attrs, k) && k) {
                s += ' ' + k + '="' + String(el.attrs[k]).replace(/"/g, '&quot;') + '"';
            }
        }
        if (VOID_TAGS[el.tag]) return s + ' />';
        var inner = '';
        for (var j = 0; j < el.children.length; j++) inner += serialize(el.children[j]);
        return s + '>' + inner + '</' + el.tag + '>';
    }
    function htmlEscapeText(t) {
        return String(t).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    }

    // ---------------- 选择器 ----------------
    function tokenizeSelector(sel) {
        var tokens = [];
        var re = /([\w-]+|\.\w[\w-]*|#\w[\w-]*|\*|\[[^\]]*\])|(\s*>\s*)|(\s+)/g;
        var m;
        while ((m = re.exec(sel)) !== null) {
            if (m[1]) tokens.push({ type: 'simple', value: m[1] });
            else if (m[2]) tokens.push({ type: 'comb', value: '>' });
            else if (m[3]) tokens.push({ type: 'comb', value: ' ' });
        }
        return tokens;
    }
    // 关键：两个 simple token 之间没有显式组合符（既无空格也无 >）时必须并入
    // 同一个复合选择器，而不是当成后代关系。旧实现把 `a[href]` 拆成 `a`（祖先）
    // + `[href]`（后代），而 li.wq_8 / a[href*="/play/"] 这类写法在插件里极常见，
    // 结果一律静默返回 0 条（真实案例：73DJ 搜 `a[href*="/play/"]` 命中 0 而
    // 页面里有 20 条 <a href="/play/…">）。
    function buildSteps(tokens) {
        var steps = [];
        var cur = null;
        var comb = '';
        var explicit = false;
        for (var i = 0; i < tokens.length; i++) {
            var t = tokens[i];
            if (t.type === 'comb') { comb = t.value; explicit = true; continue; }
            if (cur && !explicit) { cur.simple.push(t.value); continue; }
            cur = { comb: comb, simple: [t.value] };
            steps.push(cur);
            explicit = false;
            comb = ' ';
        }
        return steps;
    }
    function parseAttrSel(v) {
        var m = /^\[([\w:-]+)(?:([^=\]]*)=["']?([^"'\]]*)["']?)?\]$/.exec(v);
        if (!m) return null;
        var name = m[1];
        var op = m[2] || '';
        var val = m[3] !== undefined ? m[3] : '';
        return { name: name, op: op, val: val };
    }
    function hasClass(el, cls) {
        var c = el.attrs['class'];
        if (!c) return false;
        var list = String(c).split(/\s+/);
        for (var i = 0; i < list.length; i++) if (list[i] === cls) return true;
        return false;
    }
    function matchSimple(el, value) {
        if (value === '*') return true;
        var v = value;
        if (v.charAt(0) === '.') { if (el.isText) return false; return hasClass(el, v.slice(1)); }
        if (v.charAt(0) === '#') { if (el.isText) return false; return el.attrs['id'] === v.slice(1); }
        if (v.charAt(0) === '[') {
            if (el.isText) return false;
            var ps = parseAttrSel(v);
            if (!ps) return true;
            var actual = el.attrs[ps.name];
            if (actual === undefined || actual === null) return false;
            if (!ps.op) return true;
            actual = String(actual);
            if (ps.op === '=') return actual === ps.val;
            if (ps.op === '^=') return actual.indexOf(ps.val) === 0;
            if (ps.op === '$=') return actual.lastIndexOf(ps.val) === actual.length - ps.val.length;
            if (ps.op === '*=') return actual.indexOf(ps.val) >= 0;
            if (ps.op === '~=') return actual.split(/\s+/).indexOf(ps.val) >= 0;
            return true;
        }
        return (!el.isText) && el.tag === v.toLowerCase();
    }
    function matchCompound(el, compound) {
        if (el.isText) return false;
        for (var i = 0; i < compound.length; i++) {
            if (!matchSimple(el, compound[i])) return false;
        }
        return true;
    }
    function matchesSelector(el, steps, idx) {
        if (!el || el.isText) return false;
        if (idx === 0) return matchCompound(el, steps[idx].simple);
        if (!matchCompound(el, steps[idx].simple)) return false;
        var comb = steps[idx].comb;
        if (comb === '>') {
            var p = el.parent;
            if (!p) return false;
            return matchesSelector(p, steps, idx - 1);
        }
        var anc = el.parent;
        while (anc && anc.tag !== '#root') {
            if (matchesSelector(anc, steps, idx - 1)) return true;
            anc = anc.parent;
        }
        return false;
    }

    function queryAll(sel, roots) {
        var steps = buildSteps(tokenizeSelector(sel));
        if (steps.length === 0) return [];
        var out = [];
        for (var r = 0; r < roots.length; r++) {
            (function walk(el) {
                if (el.isText) return;
                if (el.tag !== '#root' && matchesSelector(el, steps, steps.length - 1)) out.push(el);
                for (var i = 0; i < el.children.length; i++) walk(el.children[i]);
            })(roots[r]);
        }
        return dedupe(out);
    }
    function dedupe(arr) {
        var seen = [];
        var out = [];
        for (var i = 0; i < arr.length; i++) {
            if (seen.indexOf(arr[i]) < 0) { seen.push(arr[i]); out.push(arr[i]); }
        }
        return out;
    }
    // 选择器列表（逗号分隔）：拆顶层逗号（跳过 [] 内与引号内的逗号）。
    // 插件里 closest('li,tr')、find('a,b')、is('.a,.b') 很常见；旧实现只把逗号
    // 拼进一个复合选择器（li AND tr），等于永不相中。
    function selectorList(sel) {
        var text = String(sel);
        var parts = [];
        var cur = '';
        var depth = 0;
        var quote = '';
        for (var i = 0; i < text.length; i++) {
            var ch = text.charAt(i);
            if (quote) {
                cur += ch;
                if (ch === quote) quote = '';
                continue;
            }
            if (ch === '"' || ch === "'") { quote = ch; cur += ch; continue; }
            if (ch === '[') { depth++; cur += ch; continue; }
            if (ch === ']') { if (depth > 0) depth--; cur += ch; continue; }
            if (ch === ',' && depth === 0) {
                if (cur.replace(/\s+/g, '')) parts.push(cur.trim());
                cur = '';
                continue;
            }
            cur += ch;
        }
        if (cur.replace(/\s+/g, '')) parts.push(cur.trim());
        return parts.length ? parts : [text.trim()];
    }
    function queryAllList(sel, roots) {
        var parts = selectorList(sel);
        var out = [];
        for (var i = 0; i < parts.length; i++) out = out.concat(queryAll(parts[i], roots));
        return dedupe(out);
    }
    function matchesAny(el, sel) {
        if (!el || el.isText) return false;
        var parts = selectorList(sel);
        for (var i = 0; i < parts.length; i++) {
            var steps = buildSteps(tokenizeSelector(parts[i]));
            if (steps.length && matchesSelector(el, steps, steps.length - 1)) return true;
        }
        return false;
    }

    // ---------------- Cheerio 对象 ----------------
    function Cheerio(elements) {
        this.elements = elements || [];
    }
    Object.defineProperty(Cheerio.prototype, 'length', {
        get: function () { return this.elements.length; }
    });
    Cheerio.prototype.get = function (i) { return this.elements[i]; };
    Cheerio.prototype.toArray = function () { return this.elements.slice(); };
    Cheerio.prototype.first = function () { return new Cheerio(this.elements.slice(0, 1)); };
    Cheerio.prototype.last = function () { return this.elements.length ? new Cheerio(this.elements.slice(-1)) : new Cheerio(); };
    Cheerio.prototype.eq = function (i) { return new Cheerio(this.elements.length > i && i >= 0 ? [this.elements[i]] : []); };
    Cheerio.prototype.slice = function (start, end) { return new Cheerio(this.elements.slice(start, end)); };
    Cheerio.prototype.each = function (fn) {
        for (var i = 0; i < this.elements.length; i++) {
            if (fn.call(this.elements[i], i, this.elements[i]) === false) break;
        }
        return this;
    };
    Cheerio.prototype.map = function (fn) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var r = fn.call(this.elements[i], i, this.elements[i]);
            if (r !== null && r !== undefined) out.push(r);
        }
        return new Cheerio(out);
    };
    Cheerio.prototype.text = function () {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) collectText(this.elements[i], out);
        return out.join('');
    };
    Cheerio.prototype.html = function () {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (el.isText) out.push(el.text);
            else for (var j = 0; j < el.children.length; j++) out.push(serialize(el.children[j]));
        }
        return out.join('');
    };
    Cheerio.prototype.attr = function (name) {
        if (this.elements.length === 0 || this.elements[0].isText) return undefined;
        return this.elements[0].attrs[name];
    };
    Cheerio.prototype.data = function (name) {
        return this.attr('data-' + name);
    };
    // .val()：表单控件的值（真实 cheerio/jQuery 都有，插件常用 $(sel).val() 取输入框）。
    // 缺它会让插件直接抛 "TypeError: not a function"（我是DJ 的搜索解析即栽在这里）。
    Cheerio.prototype.val = function (value) {
        if (value === undefined) {
            var el = this.elements[0];
            if (!el || el.isText) return undefined;
            if (el.tag === 'textarea') {
                var t = [];
                for (var j = 0; j < el.children.length; j++) collectText(el.children[j], t);
                return t.join('');
            }
            return el.attrs['value'] !== undefined ? el.attrs['value'] : '';
        }
        for (var i = 0; i < this.elements.length; i++) {
            if (!this.elements[i].isText) this.elements[i].attrs['value'] = String(value);
        }
        return this;
    };
    Cheerio.prototype.hasClass = function (cls) {
        for (var i = 0; i < this.elements.length; i++) {
            if (!this.elements[i].isText && hasClass(this.elements[i], cls)) return true;
        }
        return false;
    };
    Cheerio.prototype.removeAttr = function (name) {
        for (var i = 0; i < this.elements.length; i++) {
            if (!this.elements[i].isText) delete this.elements[i].attrs[name];
        }
        return this;
    };
    Cheerio.prototype.find = function (sel) {
        var roots = [];
        for (var i = 0; i < this.elements.length; i++) {
            roots.push(this.elements[i]);
        }
        return new Cheerio(queryAllList(sel, roots));
    };
    Cheerio.prototype.children = function (sel) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            for (var j = 0; j < el.children.length; j++) {
                var c = el.children[j];
                if (!c.isText) out.push(c);
            }
        }
        if (sel) out = out.filter(function (el) { return matchesAny(el, sel); });
        return new Cheerio(dedupe(out));
    };
    Cheerio.prototype.parent = function () {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            if (this.elements[i].parent && this.elements[i].parent.tag !== '#root') out.push(this.elements[i].parent);
        }
        return new Cheerio(dedupe(out));
    };
    Cheerio.prototype.parents = function (sel) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var p = this.elements[i].parent;
            while (p && p.tag !== '#root') { out.push(p); p = p.parent; }
        }
        out = dedupe(out);
        if (sel) out = out.filter(function (el) { return matchesAny(el, sel); });
        return new Cheerio(out);
    };
    // closest(sel)：从元素自身开始向上找第一个匹配的祖先（含自身）。
    // 缺它是 36DJ 搜索必炸的点（TypeError: not a function）：插件写
    //   $(input).parent().closest('li,tr').find('a[href*="/play/"]')...
    // 旧垫片既没有 closest，也不支持逗号选择器列表。
    Cheerio.prototype.closest = function (sel) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (el.isText) continue;
            var cur = el;
            while (cur && cur.tag !== '#root') {
                if (matchesAny(cur, sel)) { out.push(cur); break; }
                cur = cur.parent;
            }
        }
        return new Cheerio(dedupe(out));
    };
    Cheerio.prototype.siblings = function (sel) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (!el.parent) continue;
            for (var j = 0; j < el.parent.children.length; j++) {
                var sib = el.parent.children[j];
                if (sib !== el && !sib.isText) out.push(sib);
            }
        }
        out = dedupe(out);
        if (sel) out = out.filter(function (el) { return matchesAny(el, sel); });
        return new Cheerio(out);
    };
    Cheerio.prototype.next = function () {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (!el.parent) continue;
            var idx = el.parent.children.indexOf(el);
            var j = idx + 1;
            while (j < el.parent.children.length && el.parent.children[j].isText) j++;
            if (j < el.parent.children.length) out.push(el.parent.children[j]);
        }
        return new Cheerio(dedupe(out));
    };
    Cheerio.prototype.prev = function () {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (!el.parent) continue;
            var idx = el.parent.children.indexOf(el);
            var j = idx - 1;
            while (j >= 0 && el.parent.children[j].isText) j--;
            if (j >= 0) out.push(el.parent.children[j]);
        }
        return new Cheerio(dedupe(out));
    };
    Cheerio.prototype.filter = function (arg) {
        var out = [];
        if (typeof arg === 'function') {
            for (var i = 0; i < this.elements.length; i++) {
                if (arg.call(this.elements[i], i, this.elements[i])) out.push(this.elements[i]);
            }
        } else if (typeof arg === 'string') {
            for (var j = 0; j < this.elements.length; j++) {
                if (matchesAny(this.elements[j], arg)) out.push(this.elements[j]);
            }
        } else {
            out = this.elements.slice();
        }
        return new Cheerio(out);
    };
    Cheerio.prototype.not = function (arg) {
        var out = [];
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            var hit = typeof arg === 'string' ? matchesAny(el, arg) : (arg && typeof arg === 'function' ? arg.call(el, i, el) : false);
            if (!hit) out.push(el);
        }
        return new Cheerio(out);
    };
    Cheerio.prototype.is = function (sel) {
        if (this.elements.length === 0) return false;
        // jQuery 语义：任意一个元素命中即 true（旧实现只看第一个，插件解析易漏）。
        for (var i = 0; i < this.elements.length; i++) {
            if (matchesAny(this.elements[i], sel)) return true;
        }
        return false;
    };
    Cheerio.prototype.addClass = function (cls) {
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (el.isText) continue;
            var cur = el.attrs['class'] || '';
            if ((' ' + cur + ' ').indexOf(' ' + cls + ' ') < 0) el.attrs['class'] = (cur + ' ' + cls).trim();
        }
        return this;
    };
    Cheerio.prototype.removeClass = function (cls) {
        for (var i = 0; i < this.elements.length; i++) {
            var el = this.elements[i];
            if (el.isText) continue;
            var cur = String(el.attrs['class'] || '');
            var list = cur.split(/\s+/).filter(function (c) { return c && c !== cls; });
            el.attrs['class'] = list.join(' ');
        }
        return this;
    };

    function load(html) {
        var root = parseHtml(String(html));

        function $(sel, ctx) {
            if (typeof sel === 'function') throw new Error('cheerio shim: $(fn) not supported');
            if (sel instanceof Element) return new Cheerio([sel]);
            if (sel instanceof Cheerio) return sel;
            var roots = [];
            if (ctx instanceof Cheerio) {
                roots = ctx.elements;
            } else if (ctx instanceof Element) {
                roots = [ctx];
            } else {
                roots = [root];
            }
            if (sel === undefined || sel === null || sel === '') return new Cheerio([]);
            var parts = selectorList(sel);
            var out = [];
            for (var i = 0; i < parts.length; i++) {
                out = out.concat(queryAll(parts[i], roots));
            }
            return new Cheerio(dedupe(out));
        }
        $._root = root;
        $.root = function () { return new Cheerio([root]); };
        $.html = function () {
            var s = '';
            for (var i = 0; i < root.children.length; i++) s += serialize(root.children[i]);
            return s;
        };
        return $;
    }
    load.load = load;

    module.exports = load;
})(module);