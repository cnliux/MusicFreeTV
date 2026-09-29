package com.tvmusic.remote

/**
 * 远程管理页面的完整 HTML/JS/CSS（单文件内嵌）。
 * 与 RemoteConfigService 分离，避免 3169 行单文件难以维护。
 * 浏览器端所有交互逻辑、样式、模板都在这里，修改 UI 只动本文件。
 */
internal val PAGE_HTML = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta http-equiv="Cache-Control" content="no-cache, no-store, must-revalidate">
<meta http-equiv="Pragma" content="no-cache">
<meta http-equiv="Expires" content="0">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>MusicFree TV 远程管理</title>
<style>
  :root {
    color-scheme: dark;
    --bg: #0d0f14; --card: #171a21; --card2: #1e222b; --line: #262b36;
    --text: #eef1f6; --muted: #8b93a5; --accent: #5b8cff; --accent2: #7aa5ff;
    --danger: #e06c6c; --ok: #58c97b; --radius: 14px;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0; font-family: system-ui, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif;
    background: var(--bg); color: var(--text); min-height: 100vh;
    padding-bottom: calc(64px + env(safe-area-inset-bottom));
  }
  header {
    position: sticky; top: 0; z-index: 10;
    background: rgba(13,15,20,.88); backdrop-filter: blur(10px);
    padding: 14px 16px 10px; border-bottom: 1px solid var(--line);
  }
  header h1 { font-size: 17px; margin: 0; letter-spacing: .5px; }
  header .sub { color: var(--muted); font-size: 12px; margin-top: 3px; }
  main { max-width: 720px; margin: 0 auto; padding: 14px 14px 0; }
  .page { display: none; }
  .page.on { display: block; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: var(--radius); padding: 14px; margin-bottom: 14px; }
  .card h2 { font-size: 13px; margin: 0 0 10px; color: var(--muted); font-weight: 600; letter-spacing: 1px; }
  /* 歌单名/榜单名可能很长，标题行必须限宽截断，否则长标题把整页横向撑破（乱码溢出） */
  .card h2 #pgTitle, .card h2 #pgDetailTitle { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; display: inline-block; vertical-align: bottom; }
  /* 描述/统计是长文本，最多两行，隐藏溢出 */
  #pgDetailDesc { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; word-break: break-word; }
  .row { display: flex; align-items: center; gap: 10px; padding: 9px 0; border-bottom: 1px solid var(--line); flex-wrap: wrap; }
  .row:last-child { border-bottom: none; }
  .grow { flex: 1; min-width: 0; }
  .ellip { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .muted { color: var(--muted); font-size: 12px; }
  /* 窄屏自适应：行内的按钮/下拉/数字输入是固定尺寸控件，禁止被长文案 flex 挤瘪
     （实测「开」被压到 37px、下拉 22px 完全不可用）。放不下时整颗控件换行，
     长文案在 flex:1 的 span 里自行折行。 */
  .row > button, .row > select, .row > input[type=number] { flex: none; }
  .row > .muted, .row > span { min-width: 0; }
  input[type=text] {
    flex: 1; min-width: 0; background: var(--card2); color: var(--text); border: 1px solid var(--line);
    border-radius: 10px; padding: 10px 12px; font-size: 15px; outline: none;
  }
  input[type=text]:focus { border-color: var(--accent); }
  button {
    background: var(--accent); color: #fff; border: none; border-radius: 10px; padding: 10px 16px;
    font-size: 14px; cursor: pointer; touch-action: manipulation;
  }
  button:active { filter: brightness(.85); }
  button.ghost { background: var(--card2); color: var(--text); border: 1px solid var(--line); }
  button.danger { background: transparent; color: var(--danger); border: 1px solid #4a2c2c; }
  button.small { padding: 6px 12px; font-size: 13px; border-radius: 8px; }
  .badge { display: inline-block; padding: 2px 9px; border-radius: 20px; font-size: 11px; }
  .badge.on { background: rgba(88,201,123,.15); color: var(--ok); }
  .badge.off { background: rgba(224,108,108,.15); color: var(--danger); }
  /* 底部导航 */
  nav {
    position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
    display: flex; background: rgba(19,22,29,.96); backdrop-filter: blur(12px);
    border-top: 1px solid var(--line); padding-bottom: env(safe-area-inset-bottom);
  }
  nav button {
    flex: 1; background: none; border: none; color: var(--muted); padding: 9px 0 7px;
    font-size: 11px; display: flex; flex-direction: column; align-items: center; gap: 3px; border-radius: 0;
  }
  nav button .ic { font-size: 20px; line-height: 1; }
  nav button.on { color: var(--accent); }
  /* 播放器 */
  .nowart { display: flex; justify-content: center; margin: 8px 0 16px; }
  .nowart img {
    width: 200px; height: 200px; border-radius: 18px; object-fit: cover;
    background: var(--card2); box-shadow: 0 12px 40px rgba(0,0,0,.55);
  }
  .nowtitle { text-align: center; font-size: 19px; font-weight: 700; }
  .nowartist { text-align: center; color: var(--muted); font-size: 14px; margin-top: 5px; }
  .seekwrap { padding: 14px 2px 0; }
  input[type=range] {
    -webkit-appearance: none; appearance: none; width: 100%; height: 26px; background: transparent; outline: none;
  }
  input[type=range]::-webkit-slider-runnable-track {
    height: 5px; border-radius: 3px;
    background: linear-gradient(to right, var(--accent) var(--fill,0%), #2c313d var(--fill,0%));
  }
  input[type=range]::-webkit-slider-thumb {
    -webkit-appearance: none; appearance: none; width: 18px; height: 18px; border-radius: 50%;
    background: #fff; margin-top: -6.5px; box-shadow: 0 1px 6px rgba(0,0,0,.5); border: none;
  }
  input[type=range]::-moz-range-track { height: 5px; border-radius: 3px; background: #2c313d; }
  input[type=range]::-moz-range-progress { height: 5px; border-radius: 3px; background: var(--accent); }
  input[type=range]::-moz-range-thumb { width: 18px; height: 18px; border-radius: 50%; background: #fff; border: none; }
  .times { display: flex; justify-content: space-between; color: var(--muted); font-size: 12px; margin-top: 2px; }
  .ctrls { display: flex; align-items: center; justify-content: center; gap: 18px; margin: 14px 0 4px; flex-wrap: wrap; }
  .ctrl {
    flex: none; width: 56px !important; height: 56px !important; border-radius: 50%;
    background: radial-gradient(circle at 35% 30%, #333a49, var(--card2) 70%);
    border: none; box-shadow: 0 4px 14px rgba(0,0,0,.45);
    color: var(--text); font-size: 20px; display: flex; align-items: center; justify-content: center; padding: 0;
    transition: transform .12s; cursor: pointer;
  }
  .ctrl:active { transform: scale(.92); }
  .ctrl.main {
    color: #fff;
    background: radial-gradient(circle at 35% 30%, var(--accent2), var(--accent) 75%);
    box-shadow: 0 6px 22px rgba(0,0,0,.5);
  }
  .ctrl.favon { color: var(--accent2); }
  .ctrls .side { flex: none; display: flex; flex-direction: column; align-items: center; gap: 2px; color: var(--muted); font-size: 10px; }
  .qitem { display: flex; align-items: center; gap: 10px; padding: 10px 4px; border-bottom: 1px solid var(--line); }
  .qitem:last-child { border-bottom: none; }
  .qitem.cur { color: var(--accent2); }
  .qitem .n { color: var(--muted); font-size: 12px; width: 26px; flex: none; }
  .chip {
    display: inline-flex; align-items: center; gap: 6px; background: var(--card2); color: var(--text);
    border: 1px solid var(--line); border-radius: 18px; padding: 7px 14px; font-size: 13px; cursor: pointer;
  }
  .chip.on { background: var(--accent); border-color: var(--accent); color: #fff; }
  .chip .x { color: inherit; opacity: .65; padding: 0 2px; }
  .chips { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
  /* 音源卡：一行里同时管「启用/顺序/变量」，模块名做成 chip 而不是大标题，省页面高度 */
  .srcitem { display: flex; align-items: center; gap: 8px; padding: 7px 0; border-bottom: 1px solid var(--line); }
  .srcitem:last-child { border-bottom: none; }
  .srcno { width: 22px; flex: none; text-align: center; color: var(--muted); font-size: 12px; }
  .srcitem .name { flex: 1; min-width: 0; font-size: 15px; }
  .srcitem .act { display: flex; align-items: center; gap: 8px; flex: none; }
  .actb { background: none; border: none; color: var(--accent); font-size: 12px; padding: 4px 2px; cursor: pointer; }
  .actb.dim { color: var(--muted); }
  .actb.warn { color: var(--danger); }
  .varpanel { padding: 2px 0 10px 30px; border-bottom: 1px solid var(--line); }
  .varpanel .vrow { display: flex; align-items: center; gap: 8px; padding: 4px 0; }
  .varpanel .vrow span { width: 148px; flex: none; color: var(--muted); font-size: 12px; }
  #toast {
    position: fixed; left: 50%; bottom: calc(80px + env(safe-area-inset-bottom)); transform: translateX(-50%);
    background: #262b36; color: var(--text); border: 1px solid var(--line);
    padding: 9px 18px; border-radius: 22px; font-size: 13px; opacity: 0; transition: opacity .25s;
    pointer-events: none; z-index: 30; max-width: 86vw;
  }
  #toast.show { opacity: 1; }
  #favModal, #collectModal {
    position: fixed; inset: 0; background: rgba(0,0,0,.6); z-index: 40;
    display: none; align-items: flex-end; justify-content: center;
  }
  #favModal.show, #collectModal.show { display: flex; }
  #favModal .sheet, #collectModal .sheet {
    width: 100%; max-width: 520px; background: var(--card); border-radius: 18px 18px 0 0;
    padding: 18px 18px calc(18px + env(safe-area-inset-bottom)); max-height: 70vh; overflow-y: auto;
  }
  #favModal h3, #collectModal h3 { margin: 0 0 4px; font-size: 16px; }
  #favModal .fitem, #collectModal .fitem {
    display: flex; align-items: center; gap: 10px; padding: 13px 6px; border-bottom: 1px solid var(--line);
    font-size: 15px; cursor: pointer;
  }
  #favModal .fitem .ck, #collectModal .fitem .ck { width: 24px; color: var(--accent2); font-size: 16px; flex: none; }
  #favModal .fitem .cnt, #collectModal .fitem .cnt { margin-left: auto; color: var(--muted); font-size: 12px; }
  #favModal .newrow, #collectModal .newrow { display: flex; gap: 8px; margin-top: 12px; }
  #favModal .newrow input, #collectModal .newrow input { flex: 1; }
  .pager { display: flex; align-items: center; gap: 8px; margin-top: 10px; font-size: 12px; color: var(--muted); }
  .pager button { padding: 6px 12px; font-size: 12px; flex: none; }
  .empty { color: var(--muted); font-size: 13px; text-align: center; padding: 18px 0; }
  .volrow { display: flex; align-items: center; gap: 10px; padding: 4px 10px 0; }
  .volrow input { flex: 1; }
  /* 插件板块调试 */
  textarea {
    width: 100%; min-height: 74px; background: var(--card2); color: var(--text);
    border: 1px solid var(--line); border-radius: 10px; padding: 9px 11px; font-size: 12px;
    font-family: ui-monospace, Menlo, Consolas, monospace; outline: none; resize: vertical;
  }
  textarea:focus { border-color: var(--accent); }
  .jsonbox {
    margin-top: 8px; background: #0a0c11; border: 1px solid var(--line); border-radius: 10px;
    padding: 10px; font-size: 11px; line-height: 1.5; color: #a8d8b9;
    font-family: ui-monospace, Menlo, Consolas, monospace;
    white-space: pre-wrap; word-break: break-all; max-height: 320px; overflow: auto;
  }
  /* 插件浏览：与电视端一致的两级 chip + 卡片网格 */
  .crumb { cursor: pointer; color: var(--accent); }
  /* 平台/分类 chip：横向依次排列、排满自动换行、胶囊形状、横纵间距一致 8px，
     文字强制单行（容器变窄时是整颗按钮换行，不是文字在按钮内折行）。
     作用域只限插件页两行（#pgPlatforms / #pgTags），不影响其它地方的 .chip。 */
  .chips { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 0 10px; }
  .chips .chip {
    flex: 0 0 auto; max-width: 100%; white-space: nowrap; border-radius: 999px;
    overflow: hidden; text-overflow: ellipsis;
  }
  /* flex-basis 而非 min-width：宽屏两枚等分铺满，窄屏自动换行堆叠，不横向溢出 */
  .entry {
    flex: 1 1 150px; min-width: 0; text-align: left; font-size: 15px; padding: 12px 14px;
    background: var(--card2); border: 1px solid var(--line); border-radius: 12px; color: var(--text);
  }
  .entry span {
    display: block; color: var(--muted); font-size: 11px; margin-top: 3px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .entry:active { border-color: var(--accent); }
  .gname { font-size: 15px; padding: 12px 0 4px; }
  /* 排行榜分组与推荐歌单共用定宽网格：排满自动换行、无横向滚动条。
     列宽固定 132px 而非 1fr——1fr 会撑宽列而卡片仍固定宽，间距不均。 */
  .strip { display: grid; grid-template-columns: repeat(auto-fill, 132px); gap: 14px 12px; }
  .mgrid { display: grid; grid-template-columns: repeat(auto-fill, 132px); gap: 14px 12px; }
  /* 页面不显示滚动条（用户要求），仍可滚动 */
  ::-webkit-scrollbar { display: none; width: 0; height: 0; }
  * { scrollbar-width: none; -ms-overflow-style: none; }
  /* 卡片：封面固定 132x132 正方形，object-fit 裁切不撑破布局 */
  .mcard {
    width: 132px; position: relative; text-align: left; cursor: pointer;
    color: var(--text); background: none;
  }
  .mcard img, .mcard .ph {
    width: 132px; height: 132px; border-radius: 10px; object-fit: cover;
    background: var(--card2); display: flex; align-items: center; justify-content: center;
    font-size: 26px; color: var(--muted);
  }
  /* 标题/副标题都锁死行高 + 限高截断。行高必须写死：中日韩字形上下伸展比拉丁字母大，
     用 normal 时同一行里 CJK 与英文的 line box 高度不同，卡片就会高低不齐（实测 184/188px 抖动）。 */
  .mcard .t {
    font-size: 12px; line-height: 1.35; margin-top: 6px; height: 2.7em; overflow: hidden;
    display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  }
  .mcard .s {
    color: var(--muted); font-size: 11px; line-height: 16px; height: 16px; margin-top: 2px;
    white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  }
  .songrow { display: flex; align-items: center; gap: 10px; padding: 8px 4px; min-height: 58px; border-bottom: 1px solid var(--line); cursor: pointer; }
  .songrow:last-child { border-bottom: none; }
  .songrow:active { background: var(--card2); }
  .songrow .no { width: 22px; color: var(--muted); font-size: 12px; text-align: right; flex: none; }
  /* 歌曲行封面必须定尺：不定就是原图宽度（部分图床给 1500px），一行直接把布局撑爆 */
  .songrow img { width: 42px; height: 42px; border-radius: 6px; object-fit: cover; background: var(--card2); flex: none; }
  /* 歌名/歌手必须限宽截断：这两条规则以前只存在于 .bit 下，歌曲行里的长歌名会把整行撑破 */
  .songrow .g { flex: 1; min-width: 0; }
  .songrow .t { font-size: 13px; line-height: 18px; height: 18px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .songrow .s { color: var(--muted); font-size: 11px; line-height: 16px; height: 16px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rowbtns { display: flex; gap: 6px; flex: none; }
  .rowbtn {
    width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--line); background: var(--card2);
    color: var(--text); font-size: 13px; line-height: 1; display: flex; align-items: center; justify-content: center;
  }
  .rowbtn:active { border-color: var(--accent); }
  .rowbtn.faved { color: var(--accent2); }
  .spin { display: inline-block; width: 15px; height: 15px; border: 2px solid var(--line); border-top-color: var(--accent); border-radius: 50%; animation: spin .8s linear infinite; }
  @keyframes spin { to { transform: rotate(360deg); } }
</style>
</head>
<body>
<header>
  <h1>MusicFree TV</h1>
  <div class="sub" id="statusLine">连接中…</div>
</header>

<main>
  <!-- 播放 -->
  <section class="page on" id="page-player">
    <div class="card">
      <div class="nowart"><img id="pArt" alt="" onerror="this.onerror=null;this.removeAttribute('src')"></div>
      <div class="nowtitle ellip" id="pTitle">未在播放</div>
      <div class="nowartist ellip" id="pArtist"></div>
      <div class="seekwrap">
        <input type="range" id="seekBar" min="0" max="1000" value="0" step="1">
        <div class="times"><span id="pPos">0:00</span><span id="pDur">0:00</span></div>
      </div>
      <div class="ctrls">
        <div class="side"><button class="ctrl" id="pMode" onclick="cycleMode()">⇄</button><span id="pModeName">顺序</span></div>
        <button class="ctrl" onclick="playerCmd('prev')">⏮︎</button>
        <button class="ctrl main" id="pToggle" onclick="playerCmd('playpause')">▶︎</button>
        <button class="ctrl" onclick="playerCmd('next')">⏭︎</button>
        <div class="side"><button class="ctrl" id="pFav" onclick="toggleCurFav()">♡</button><span>收藏</span></div>
        <div class="side"><button class="ctrl" onclick="showVol()">♪</button><span>音量</span></div>
      </div>
      <div class="volrow" id="volRow" style="display:none;">
        <button class="ghost small" onclick="volume(-0.1)">−</button>
        <input type="range" id="volBar" min="0" max="100" value="50">
        <button class="ghost small" onclick="volume(0.1)">＋</button>
        <span class="muted" id="pVol" style="width:38px;text-align:right;">50%</span>
      </div>
      <div class="muted" id="pErr" style="text-align:center;margin-top:6px;color:var(--danger);"></div>
    </div>
    <div class="card">
      <h2>播放列表 <span id="qCount" class="muted"></span></h2>
      <div id="queueBox"></div>
    </div>
  </section>

  <!-- 搜索 -->
  <section class="page" id="page-search">
    <div class="card">
      <div class="row" style="border:none;padding:0;">
        <input type="text" id="searchQ" placeholder="搜索歌曲，回车开始" onkeydown="if(event.key==='Enter')doSearch()">
        <button onclick="doSearch()">搜索</button>
      </div>
      <div class="row" style="border:none;padding:10px 0 6px;">
        <button class="ghost small" id="playAllBtn" onclick="playAllSearch()" style="display:none;">▶ 播放全部结果</button>
        <button class="ghost small" id="collectAllBtn" onclick="openCollectAll()" style="display:none;">♡ 全部收藏</button>
        <span class="muted" id="searchInfo"></span>
      </div>
      <div id="searchFilters">
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">音源</span>
          <div class="chips" id="srcBar" style="flex:1;"></div>
        </div>
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">时长</span>
          <input type="number" id="minD" placeholder="≥秒" style="width:72px;">
          <span class="muted">—</span>
          <input type="number" id="maxD" placeholder="≤秒" style="width:72px;">
          <label style="margin-left:16px;display:flex;align-items:center;"><input type="checkbox" id="needArt" style="margin-right:5px;"> 必须有封面</label>
        </div>
        <div class="row" style="border:none;padding:4px 0;">
          <span class="muted" style="width:64px;">排序</span>
          <select id="sortSel" style="width:132px;"></select>
          <select id="ascSel" style="width:90px;margin-left:8px;">
            <option value="1">升序</option>
            <option value="0">降序</option>
          </select>
          <button class="ghost small" style="margin-left:14px;" onclick="saveCfgInline()">存为新默认</button>
        </div>
      </div>
    </div>
    <div class="card" id="searchCard" style="display:none;">
      <h2>搜索结果</h2>
      <div class="chips" id="searchPluginBar" style="display:none;"></div>
      <div id="searchBox"></div>
      <div class="pager" id="searchPager"></div>
    </div>
  </section>

  <!-- 推荐（与电视端 APK 同一套操作：排行榜 / 推荐歌单） -->
  <section class="page" id="page-plugin">
    <div class="card" id="pgHome">
      <h2>推荐</h2>
      <div class="muted" style="padding:0 0 10px;">和电视端完全一样的操作：选平台 → 点歌单或榜单 → 点歌直接播放。</div>
      <div class="row" style="border:none;padding:0;gap:8px;flex-wrap:wrap;">
        <button class="entry" onclick="pgOpen('recommend')">🔥 推荐歌单<span>按标签发现好歌单</span></button>
        <button class="entry" onclick="pgOpen('toplist')">🏆 排行榜<span>各平台权威榜单</span></button>
      </div>
    </div>

    <div class="card" id="pgBrowse" style="display:none;">
      <h2><span class="crumb" onclick="pgHome()">‹ 返回</span> <span id="pgTitle"></span> <span class="muted" id="pgInfo"></span></h2>
      <div class="chips" id="pgPlatforms"></div>
      <div class="chips" id="pgTags" style="display:none;"></div>
      <div id="pgBody"></div>
      <div class="row" style="border:none;padding:12px 0 0;gap:8px;">
        <button class="ghost small" id="pgMoreBtn" style="display:none;" onclick="pgMore()">加载更多</button>
        <button class="ghost small" style="margin-left:auto;" onclick="pgRaw()">原始 JSON</button>
      </div>
      <div id="pgRawBox" style="display:none;">
        <div class="muted" style="padding:8px 0 2px;">插件原始返回（未加工）</div>
        <div class="jsonbox" id="pgRaw"></div>
      </div>
    </div>

    <div class="card" id="pgDetailCard" style="display:none;">
      <h2><span class="crumb" onclick="pgBack()">‹ 返回</span> <span id="pgDetailTitle"></span></h2>
      <div class="row" style="border:none;padding:0 0 6px;gap:10px;flex-wrap:wrap;">
        <img id="pgDetailArt" style="width:64px;height:64px;border-radius:10px;object-fit:cover;background:var(--card2);flex:none;" alt="">
        <div style="flex:1;min-width:120px;">
          <div id="pgDetailDesc" class="muted"></div>
          <div class="muted" id="pgDetailCount" style="padding-top:4px;"></div>
        </div>
        <div style="display:flex;gap:8px;flex:none;">
          <button class="small" onclick="pgPlayAll()">▶ 播放全部</button>
          <button class="ghost small" id="pgFavSheetBtn" onclick="pgFavSheet()">♡ 收藏</button>
        </div>
      </div>
      <div id="pgSongs"></div>
      <div class="row" style="border:none;padding:12px 0 0;gap:8px;">
        <button class="ghost small" id="pgSongMore" style="display:none;" onclick="pgMore()">加载更多</button>
      </div>
    </div>
  </section>

  <!-- 收藏 -->
  <section class="page" id="page-fav">
    <div class="card">
      <h2>收藏专辑</h2>
      <div class="chips" id="favListBar"></div>
      <div class="row" style="border:none;padding:0 0 10px;">
        <input type="text" id="favNewName" placeholder="新专辑名称">
        <button class="small" onclick="createFavList()">新建</button>
      </div>
      <div id="favActions"></div>
      <div id="favItems"></div>
      <div class="pager" id="favPager"></div>
    </div>
  </section>

  <!-- 播放历史已并入收藏页（🕘 历史 虚拟专辑），独立页签已删除 -->

  <!-- 管理 -->
  <section class="page" id="page-manage">
    <div class="card">
      <h2>界面主题</h2>
      <div class="chips" id="themeBar"></div>
      <div class="muted">选择后立即应用到电视端与本页。</div>
    </div>
    <div class="card">
      <h2>播放页歌词</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">播放页逐行歌词的显示效果</span>
      </div>
      <div class="row" style="border:none;padding:6px 0;">
        <span class="muted" style="flex:1;">字体大小</span>
        <button class="ghost small" onclick="stepLyricSize(-2)">－</button>
        <span id="lyricSize" style="min-width:44px;text-align:center;"></span>
        <button class="ghost small" onclick="stepLyricSize(2)">＋</button>
      </div>
      <div class="row" style="border:none;padding:6px 0;">
        <span class="muted" style="flex:1;">颜色</span>
        <input type="color" id="lyricColor" style="width:44px;height:30px;border:none;background:none;padding:0;" onchange="setLyricColor(this.value)">
      </div>
      <div class="chips" id="lyricColorBar" style="margin-top:2px;"></div>
    </div>
    <div class="card">
      <h2>歌词/封面补全</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">歌曲缺少歌词或封面时，按 曲名/歌手 从 lrc.cx 在线补齐</span>
        <button class="small" id="metaToggle" onclick="toggleMeta()">开</button>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">当前音源无法播放时，自动尝试其他插件播放同一首歌（不改变歌单）</span>
        <button class="small" id="metaFallbackToggle" onclick="toggleMetaFallback()">开</button>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">最低播放时长（秒，0=关闭）：播放不足该时长就自动结束视为版权受限，换其他插件重播同一首</span>
        <input type="number" id="minPlaySeconds" min="0" max="300" style="width:78px;flex:none;">
        <button class="small" onclick="saveMinPlay()">保存</button>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">优先换源插件：无法播放/受限时最先尝试该插件；「聚合搜索」= 全源并行，最快符合时长的先播</span>
        <select id="preferPlugin" onchange="savePrefer()" style="max-width:220px;flex:none;"></select>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">换源策略：控制候选插件的启动与竞速方式（用于对比测速）</span>
        <select id="fallbackStrategy" onchange="saveFallbackStrategy()" style="max-width:240px;flex:none;">
          <option value="staggered">分批错峰（默认，兼顾并发与资源）</option>
          <option value="allParallel">全候选同时启动（最快，吃资源）</option>
          <option value="sequential">严格顺序（前一个成功即返回）</option>
          <option value="preferFirst">只试第 1 名（低延迟，成功率低）</option>
        </select>
      </div>
    </div>
    <div class="card">
      <h2>播放页封面</h2>
      <div class="muted" style="padding:0 0 8px;">大封面的形状与旋转效果。改动会立刻写入配置，播放页即时生效（无需重进）。</div>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">封面形状</span>
        <select id="coverShape" onchange="saveCoverShape()" style="max-width:200px;flex:none;">
          <option value="circle">圆形（默认）</option>
          <option value="square">方形圆角</option>
        </select>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">旋转方向</span>
        <select id="coverSpinDir" onchange="saveCoverSpinDir()" style="max-width:200px;flex:none;">
          <option value="cw">顺时针（默认）</option>
          <option value="ccw">逆时针</option>
        </select>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">转一圈耗时（秒，0=不转）</span>
        <input type="number" id="coverSpinSec" min="0" max="120" style="width:78px;flex:none;">
        <button class="small" onclick="saveCoverSpin()">保存</button>
      </div>
      <div class="chips" style="padding-top:8px;">
        <span class="chip" onclick="quickCoverSpin(0)">不转</span>
        <span class="chip" onclick="quickCoverSpin(10)">10 秒</span>
        <span class="chip" onclick="quickCoverSpin(20)">20 秒</span>
        <span class="chip" onclick="quickCoverSpin(30)">30 秒</span>
      </div>
    </div>
    <div class="card">
      <h2>待机显示</h2>
      <div class="row" style="border:none;padding:0 0 6px;">
        <span class="muted" style="flex:1;">无操作达设定时长且正在播放时，电视自动进入播放器页</span>
        <button class="small" id="idleToggle" onclick="toggleIdle()">开</button>
      </div>
      <div class="row" style="border:none;padding:6px 0 0;">
        <span class="muted" style="flex:1;">无操作时长（分钟，1-60）</span>
        <input type="number" id="idleMinutes" min="1" max="60" style="width:78px;flex:none;">
        <button class="small" onclick="saveIdleMinutes()">保存</button>
      </div>
    </div>
    <div class="card">
      <h2>音源与插件 <span id="pluginCount" class="muted"></span></h2>
      <div class="muted" style="padding:0 0 8px;">每行一个音源插件：启用/停用、配置登录变量、调整搜索优先级（越靠上越先搜索、结果越靠前），都在这一行里完成。</div>
      <div class="row" style="border:none;padding:0 0 8px;flex-wrap:wrap;gap:8px;">
        <span class="muted" style="flex:none;">默认排序</span>
        <select id="cfgSortBy" style="width:132px;"></select>
        <select id="cfgSortAsc" style="width:80px;">
          <option value="1">升序</option>
          <option value="0">降序</option>
        </select>
        <span class="muted" style="flex:none;margin-left:6px;">最多结果</span>
        <input type="number" id="cfgMaxTotal" min="20" max="200" step="10" style="width:78px;flex:none;">
      </div>
      <div id="srcList"></div>
      <div class="row" style="border:none;padding:8px 0 0;flex-wrap:wrap;gap:8px;">
        <button class="small" onclick="saveSearchCfg()">保存设置</button>
        <button class="ghost small" onclick="resetCfgOrder()">重置顺序</button>
        <button class="ghost small" onclick="syncAll()">⟳ 同步订阅</button>
        <button class="ghost small" id="uninstallAllBtn" onclick="uninstallAll(this)" style="margin-left:auto;">✕ 全部卸载</button>
      </div>
    </div>
    <div class="card">
      <h2>订阅源</h2>
      <div id="subList"></div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <input type="text" id="subUrl" placeholder="plugins.json 或 .js 直链">
        <button class="small" onclick="addSub()">添加</button>
      </div>
    </div>
    <div class="card">
      <h2>配置</h2>
      <div class="muted">导出/导入主题、歌词设置与收藏专辑，用于备份或迁移到其他设备。</div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <button class="small" onclick="exportConfig()">⬇ 导出配置</button>
        <button class="ghost small" onclick="el('importFile').click()">⬆ 导入配置</button>
        <input type="file" id="importFile" accept="application/json,.json" style="display:none;" onchange="importConfig(this)">
      </div>
      <div class="row" style="border:none;padding:10px 0 0;">
        <button class="small" onclick="exportPluginsBackup()">⬇ 导出插件备份</button>
        <button class="ghost small" onclick="el('pluginImportFile').click()">⬆ 导入插件备份</button>
        <input type="file" id="pluginImportFile" accept="application/json,.json" style="display:none;" onchange="importPluginsBackup(this)">
      </div>
      <div class="muted" style="padding-top:4px;">插件备份含每个插件各自的 js 地址与音源顺序（不含源码、不含订阅合集），恢复时按地址逐个重新拉取安装。</div>
    </div>
  </section>
</main>

<div id="toast"></div>

<div id="favModal" onclick="if(event.target===this)closeFavModal()">
  <div class="sheet">
    <h3 id="favModalTitle">收藏到…</h3>
    <div id="favAlbumList"></div>
    <div class="newrow">
      <input type="text" id="favAlbumNew" placeholder="新专辑名称">
      <button class="small" onclick="createFavAlbumFromModal()">新建</button>
    </div>
    <div class="newrow"><button class="ghost small" style="flex:1;" onclick="closeFavModal()">关闭</button></div>
  </div>
</div>

<div id="collectModal" onclick="if(event.target===this)closeCollectAll()">
  <div class="sheet">
    <h3 id="collectModalTitle">全部收藏到…</h3>
    <div class="muted" id="collectModalSub"></div>
    <div id="collectList"></div>
    <div class="newrow">
      <input type="text" id="collectNewName" placeholder="新收藏夹名称">
      <button class="small" onclick="createCollectAndAdd()">新建并收藏</button>
    </div>
    <div class="newrow"><button class="ghost small" style="flex:1;" onclick="closeCollectAll()">关闭</button></div>
  </div>
</div>

<nav>
  <button class="on" data-tab="player" onclick="switchTab('player')"><span class="ic">🎵</span>播放</button>
  <button data-tab="search" onclick="switchTab('search')"><span class="ic">🔍</span>搜索</button>
  <button data-tab="plugin" onclick="switchTab('plugin')"><span class="ic">✨</span>推荐</button>
  <button data-tab="fav" onclick="switchTab('fav')"><span class="ic">❤️</span>收藏</button>
  <button data-tab="manage" onclick="switchTab('manage')"><span class="ic">⚙️</span>管理</button>
</nav>

<script>
function api(path, opts) {
  return fetch(path, opts).then(function (r) { return r.json(); });
}
function el(id) { return document.getElementById(id); }
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
  });
}
var toastTimer = null;
function toast(t) {
  var x = el('toast'); x.textContent = t; x.className = 'show';
  clearTimeout(toastTimer);
  toastTimer = setTimeout(function () { x.className = ''; }, 2200);
}
function fmtPos(ms) {
  if (!ms || ms < 0) ms = 0;
  var s = Math.floor(ms / 1000), m = Math.floor(s / 60); s = s % 60;
  return m + ':' + (s < 10 ? '0' : '') + s;
}
function post(path, body) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
}

function switchTab(name) {
  var pages = document.querySelectorAll('.page');
  for (var i = 0; i < pages.length; i++) pages[i].className = 'page';
  el('page-' + name).className = 'page on';
  var btns = document.querySelectorAll('nav button');
  for (var j = 0; j < btns.length; j++) btns[j].className = btns[j].getAttribute('data-tab') === name ? 'on' : '';
  if (name === 'player') loadPlayer();
  if (name === 'fav') loadFavLists();
  if (name === 'search') loadSearchCfg();
  if (name === 'plugin') pgHome();
  if (name === 'manage') { loadSubs(); loadSearchCfg(); loadLyric(); loadMeta(); loadIdle(); }
}

/* ---------------- 播放器 ---------------- */
var MODES = ['ORDER', 'LOOP_ONE', 'SHUFFLE'];
var MODE_NAMES = { ORDER: '⇅ 顺序', LOOP_ONE: '🔂 单曲', SHUFFLE: '🔀 随机' };
var MODE_IC = { ORDER: '⇅', LOOP_ONE: '🔂︎', SHUFFLE: '🔀︎' };
var curMode = 'ORDER';
var lastStatus = null;
var seeking = false;

function cycleMode() {
  var i = MODES.indexOf(curMode);
  var next = MODES[(i + 1) % MODES.length];
  post('/api/player/mode', { mode: next }).then(function () {
    curMode = next; loadPlayer();
  }).catch(function () { toast('操作失败'); });
}

var playerFetching = false;
/* 播放器状态渲染：轮询与 SSE 推送共用同一条渲染路径，只换数据来源 */
function handlePlayerData(d) {
  lastStatus = d;
  if (!d.title) {
    el('pTitle').textContent = '未在播放';
    el('pArtist').textContent = '';
    el('pErr').textContent = '';
    el('pToggle').textContent = '▶︎';
    renderQueue(d, -1);
    return;
  }
  el('pTitle').textContent = d.title;
  el('pArtist').textContent = (d.artist || '') + (d.album ? ' · ' + d.album : '') + ' · ' + (d.index + 1) + '/' + d.queueSize + (d.buffering ? ' · 缓冲中' : '');
  var art = el('pArt');
  var want = d.artwork || '';
  var wantSrc = want ? '/api/img?url=' + encodeURIComponent(want) : '';
  // JS 里给 img.src 赋空字符串会被解析为当前页 URL 并再次触发 onerror，
  // 旧代码 onerror="this.src=''" 与之叠加形成无限请求循环（封面加载失败时
  // 浏览器每秒反复拉整页 HTML，CPU/网络双风暴导致页面卡死）。
  // 修复：无封面时移除 src 属性；onerror 首次触发后自毁并不再重试。
  if (art.getAttribute('src') !== wantSrc) {
    if (wantSrc) art.src = wantSrc; else art.removeAttribute('src');
  }
  art.style.visibility = want ? 'visible' : 'hidden';
  el('pToggle').textContent = d.playing ? '⏸︎' : '▶︎';
  var favBtn = el('pFav');
  if (favBtn) {
    favBtn.textContent = d.favorite ? '♥' : '♡';
    favBtn.className = 'ctrl' + (d.favorite ? ' favon' : '');
  }
  el('pErr').textContent = d.error ? String(d.error) : '';
  el('pVol').textContent = (d.volume || 0) + '%';
  el('volBar').value = d.volume || 0;
  if (d.playMode) {
    curMode = d.playMode;
    el('pMode').textContent = MODE_IC[d.playMode] || '⇅';
    el('pModeName').textContent = (MODE_NAMES[d.playMode] || '顺序').replace(/^[^ ]+ /, '');
  }
  el('pDur').textContent = fmtPos(d.duration);
  if (!seeking) {
    var f = d.duration > 0 ? Math.round(d.position / d.duration * 1000) : 0;
    var bar = el('seekBar');
    bar.value = f;
    bar.style.setProperty('--fill', (f / 10) + '%');
    el('pPos').textContent = fmtPos(d.position);
  }
  renderQueue(d, d.index);
}
function loadPlayer() {
  // 同一时刻只允许一条 /api/player 在途：慢网下响应未回时跳过本轮，避免请求堆积
  if (playerFetching) return;
  playerFetching = true;
  api('/api/player').then(function (d) {
    playerFetching = false;
    handlePlayerData(d);
  }).catch(function () { playerFetching = false; });
}

/* 进度条拖动：拖动中本地预览，松手提交 seek */
(function () {
  var bar = el('seekBar');
  function fill() {
    bar.style.setProperty('--fill', (bar.value / 10) + '%');
    if (lastStatus) el('pPos').textContent = fmtPos(bar.value / 1000 * lastStatus.duration);
  }
  bar.addEventListener('input', function () { seeking = true; fill(); });
  bar.addEventListener('change', function () {
    seeking = false;
    if (lastStatus && lastStatus.duration > 0) {
      post('/api/player/seek', { pos: Math.round(bar.value / 1000 * lastStatus.duration) })
        .then(function () { setTimeout(loadPlayer, 400); })
        .catch(function () { toast('跳转失败'); loadPlayer(); });
    }
  });
})();

function showVol() {
  var r = el('volRow');
  r.style.display = r.style.display === 'none' ? 'flex' : 'none';
}
function playerCmd(cmd) {
  api('/api/player/' + cmd, { method: 'POST' }).then(function () { loadPlayer(); });
}
/* 播放页收藏当前曲：弹出收藏夹选择 */
var favCtx = null; /* { mode:'player' } | { mode:'queue', idx } | { mode:'search', idx } | { mode:'song', idx, platform, item } | { mode:'hist', idx, item } */
function toggleCurFav() {
  favCtx = { mode: 'player' };
  el('favModalTitle').textContent = '收藏到…（' + (el('pTitle').textContent || '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}
function favResult(i) {
  favCtx = { mode: 'search', idx: i };
  var it = searchResults[i];
  el('favModalTitle').textContent = '收藏到…（' + (it ? it.title : '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}
function closeFavModal() { el('favModal').className = ''; }
function loadFavAlbums() {
  api('/api/player/fav-albums').then(function (d) {
    var box = el('favAlbumList');
    var arr = d.albums || [];
    var html = '';
    arr.forEach(function (a, i) {
      html += '<div class="fitem" onclick="pickFavAlbum(' + i + ')">' +
        '<span class="ck">' + (a.inList ? '♥' : '♡') + '</span>' +
        '<span class="ellip">' + esc(a.name) + '</span>' +
        '<span class="cnt">' + a.count + ' 首</span></div>';
    });
    box.innerHTML = html || '<div class="empty">还没有收藏专辑</div>';
    window._favAlbums = arr;
  }).catch(function () { toast('加载专辑失败'); });
}
function pickFavAlbum(i) {
  var a = (window._favAlbums || [])[i];
  if (!a || !favCtx) return;
  if (favCtx.mode === 'player') {
    post('/api/player/favorite', { listId: a.id }).then(function (d) {
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadPlayer(); loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  } else if (favCtx.mode === 'song') {
    var m = favCtx.item;
    if (!m) return;
    post('/api/fav/toggle', { listId: a.id, plugin: favCtx.platform, raw: m.raw || m }).then(function (d) {
      var b = el('pgfav' + favCtx.idx);
      if (b) { b.textContent = d.favorited ? '♥' : '♡'; b.className = 'rowbtn' + (d.favorited ? ' faved' : ''); }
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  } else if (favCtx.mode === 'queue') {
    post('/api/player/favAt', { index: favCtx.idx, listId: a.id }).then(function (d) {
      /* 与 song 模式一致：收藏成功后把该曲所有 ♡ 按钮（播放页队列行 + 正在播放页行）翻成 ♥ */
      document.querySelectorAll('button[onclick*="qfav(' + favCtx.idx + ')"]').forEach(function (b) {
        b.textContent = d.favorited ? '♥' : '♡';
        if (b.classList.contains('rowbtn')) b.className = 'rowbtn' + (d.favorited ? ' faved' : '');
      });
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  } else if (favCtx.mode === 'hist') {
    /* 历史行 ♡：条目自带 platform+raw，直接 toggle */
    var h = favCtx.item;
    if (!h) return;
    post('/api/fav/toggle', { listId: a.id, plugin: h.platform, raw: h.raw || h }).then(function (d) {
      var b = el('hfav' + favCtx.idx);
      if (b) { b.textContent = d.favorited ? '♥' : '♡'; b.style.color = d.favorited ? 'var(--accent2)' : ''; }
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  } else if (favCtx.mode === 'sheet') {
    /* 整张歌单/榜单收藏：服务端翻页取全量后批量入库（裸条目，按主键自动去重）。
       大歌单要翻多页，加载期间把按钮置灰防重复提交。 */
    var btn = el('pgFavSheetBtn');
    if (btn) { btn.disabled = true; btn.textContent = '⏳ 收藏中…'; }
    post('/api/plugin/collect', { platform: favCtx.platform, kind: favCtx.kind, item: favCtx.item, listId: a.id })
      .then(function (d) {
        if (btn) { btn.disabled = false; btn.textContent = d.ok ? '♥ 已收藏' : '♡ 收藏'; }
        if (d.ok) {
          toast('已收藏 ' + d.added + ' 首到「' + a.name + '」' + (d.added < d.total ? '（共 ' + d.total + ' 首，' + (d.total - d.added) + ' 首已存在）' : ''));
          loadFavLists(); loadFavAlbums();
        } else toast(d.error || '收藏失败');
      })
      .catch(function () {
        if (btn) { btn.disabled = false; btn.textContent = '♡ 收藏'; }
        toast('收藏失败');
      });
  } else {
    var it = searchResults[favCtx.idx];
    if (!it) return;
    post('/api/fav/toggle', { listId: a.id, plugin: it.plugin, raw: it.raw }).then(function (d) {
      var b = el('sfav' + favCtx.idx);
      if (b) { b.textContent = d.favorited ? '♥' : '♡'; b.style.color = d.favorited ? 'var(--accent2)' : ''; }
      toast(d.favorited ? '已收藏到「' + a.name + '」' : '已从「' + a.name + '」取消');
      loadFavAlbums();
    }).catch(function () { toast('操作失败'); });
  }
}
function createFavAlbumFromModal() {
  var name = (el('favAlbumNew').value || '').trim();
  if (!name) { toast('请输入专辑名'); return; }
  post('/api/fav/lists/create', { name: name }).then(function () {
    el('favAlbumNew').value = '';
    loadFavAlbums();
  }).catch(function () { toast('新建失败'); });
}
function volume(delta) {
  post('/api/player/volume', { delta: delta }).then(function () { loadPlayer(); });
}

var lastQueueSig = '';
function renderQueue(d, currentIdx) {
  var q = d.queue || [];
  el('qCount').textContent = q.length ? '· ' + q.length + ' 首' : '';
  var box = el('queueBox');
  if (!q.length) { lastQueueSig = ''; box.innerHTML = '<div class="empty">队列为空，去搜索推歌吧</div>'; return; }
  // 队列内容播放期间不变，仅高亮行移动：签名相同就只切换 .cur，避免每 2 秒整表重建 DOM 卡顿
  var sig = q.map(function (it) { return it.title + '\u0000' + it.artist; }).join('\u0001');
  if (sig === lastQueueSig && box.children.length === q.length) {
    for (var k = 0; k < box.children.length; k++) {
      var c = box.children[k];
      var isCur = k === currentIdx;
      if (isCur !== c.classList.contains('cur')) {
        c.className = 'qitem' + (isCur ? ' cur' : '');
        c.children[0].textContent = isCur ? '▶' : (k + 1);
      }
    }
    return;
  }
  lastQueueSig = sig;
  var html = '';
  q.forEach(function (it) {
    html += '<div class="qitem' + (it.index === currentIdx ? ' cur' : '') + '" onclick="skipTo(' + it.index + ')">' +
      '<span class="n">' + (it.index === currentIdx ? '▶' : (it.index + 1)) + '</span>' +
      '<span class="grow ellip">' + esc(it.title) + '</span>' +
      '<span class="muted ellip" style="max-width:35%;">' + esc(it.artist) + '</span>' +
      '<button class="rowbtn' + (it.faved ? ' faved' : '') + '" title="收藏" onclick="event.stopPropagation();qfav(' + it.index + ')">' + (it.faved ? '♥' : '♡') + '</button></div>';
  });
  box.innerHTML = html;
}
function skipTo(i) {
  post('/api/player/skip', { index: i }).then(function () { setTimeout(loadPlayer, 300); });
}
/* 收藏队列中的指定歌曲：选收藏夹后按索引在服务端取 raw 落库 */
function qfav(i) {
  var q = (lastStatus && lastStatus.queue) || [];
  var it = q[i];
  if (!it) return;
  favCtx = { mode: 'queue', idx: i };
  el('favModalTitle').textContent = '收藏到…（' + (it.title || '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}

/* ---------------- 搜索配置与筛选 ---------------- */
var cfgOrder = [], srcNames = [], knownNames = [], srcSel = {}, orderUI = [];
var plugins = [], pluginMap = {};
var SORT_OPTS = [['default', '默认（音源顺序）'], ['duration', '时长'], ['title', '歌名'], ['artist', '歌手']];
function bindOpts(sel, opts, val) {
  if (!sel) return null;
  sel.innerHTML = '';
  opts.forEach(function (o) {
    var op = document.createElement('option');
    op.value = o[0];
    op.textContent = o[1];
    sel.appendChild(op);
  });
  sel.value = val;
  return sel.value;
}
function srcOrderFull() {
  var out = cfgOrder.slice();
  knownNames.forEach(function (n) { if (out.indexOf(n) < 0) out.push(n); });
  return out;
}
function loadSearchCfg() {
  var cfgP = api('/api/search/config');
  return Promise.all([api('/api/plugins'), api('/api/plugins/vars')]).then(function (arr) {
    var list = arr[0].plugins || [];
    var vlist = (arr[1] && arr[1].plugins) || [];
    pluginMap = {};
    vlist.forEach(function (vp) { pluginMap[vp.platform] = vp; });
    plugins = list;
    srcNames = list.filter(function (p) { return p.enabled && !p.loadError; })
      .map(function (p) { return p.platform || p.name; })
      .filter(function (n, i, a) { return n && a.indexOf(n) === i; });
    knownNames = list.map(function (p) { return p.platform || p.name; })
      .filter(function (n, i, a) { return n && a.indexOf(n) === i; });
    return cfgP;
  }).then(function (d) {
    cfgOrder = d.sourceOrder || [];
    bindOpts(el('cfgSortBy'), SORT_OPTS, d.sortBy);
    if (el('cfgSortAsc')) el('cfgSortAsc').value = d.asc ? '1' : '0';
    if (el('cfgMaxTotal')) el('cfgMaxTotal').value = d.maxTotal || 60;
    bindOpts(el('sortSel'), SORT_OPTS, d.sortBy);
    if (el('ascSel')) el('ascSel').value = d.asc ? '1' : '0';
    orderUI = srcOrderFull();
    renderSrcBar();
    renderSrcList();
  }).catch(function () { });
}
function renderSrcBar() {
  var bar = el('srcBar');
  if (!bar) return;
  bar.innerHTML = '';
  var mk = function (label, on, cb) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (on ? ' on' : '');
    chip.textContent = label;
    chip.onclick = cb;
    bar.appendChild(chip);
  };
  var sorted = srcNames.slice().sort(function (a, b) {
    var ia = orderUI.indexOf(a), ib = orderUI.indexOf(b);
    return (ia < 0 ? 999 : ia) - (ib < 0 ? 999 : ib);
  });
  mk('全部', Object.keys(srcSel).length === 0, function () { srcSel = {}; renderSrcBar(); });
  sorted.forEach(function (n) {
    mk(n, !!srcSel[n], function () {
      if (srcSel[n]) delete srcSel[n]; else srcSel[n] = 1;
      renderSrcBar();
    });
  });
}
/* 音源与插件合并列表：每行按「优先级顺序」排，同时带停用/启用、变量配置、卸载。 */
function renderSrcList() {
  var box = el('srcList');
  if (!box) return;
  var cnt = el('pluginCount');
  if (cnt) cnt.textContent = plugins.length ? '· ' + plugins.length + ' 个' : '';
  if (!orderUI.length) { box.innerHTML = '<div class="empty">还没有音源插件，先在下方添加订阅源</div>'; return; }
  /* 兜底：插件库里存在但不在配置顺序中的音源（如 js 直链导入后未触发配置保存）
     追加到末尾显示——绝不隐藏已安装的插件，否则用户会误以为导入失败。 */
  var list = orderUI.slice();
  plugins.forEach(function (p) {
    var key = p.platform || p.name;
    if (key && list.indexOf(key) < 0) list.push(key);
  });
  var html = '';
  list.forEach(function (pk, i) {
    var p = null;
    for (var k = 0; k < plugins.length; k++) {
      if ((plugins[k].platform || plugins[k].name) === pk) { p = plugins[k]; break; }
    }
    var vp = pluginMap[pk];
    var defs = (vp && vp.userVariables) || [];
    var ghost = !p;
    var enabled = !!p && !!p.enabled && !p.loadError;
    var tag = '';
    if (ghost) tag = '<span class="badge off">已卸载</span>';
    else if (p.loadError) tag = '<span class="badge off">载入失败</span>';
    else if (!p.enabled) tag = '<span class="badge off">已停用</span>';
    else tag = '<span class="badge on">已启用</span>';
    var open = !!window._openVarPlatform && window._openVarPlatform === pk;
    html += '<div class="srcitem">' +
      '<span class="srcno">' + (i + 1) + '</span>' +
      '<div class="grow"><div class="ellip name">' + esc(pk) + ' ' + tag + '</div>' +
      '<div class="muted ellip">' + (ghost ? '订阅已移除' : ('v' + esc(p.version || '-'))) + (defs.length ? ' · ' + defs.length + ' 项登录变量' : '') + '</div></div>' +
      '<div class="act">' +
      (defs.length && !ghost ? '<button class="actb" onclick="toggleVars(' + i + ')">' + (open ? '收起变量' : '变量') + '</button>' : '') +
      (ghost ? '<button class="actb warn" onclick="removeOrderEntry(' + i + ')" title="从优先级中移除">✕</button>'
             : '<button class="actb' + (enabled ? ' dim' : '') + '" onclick="togglePlugin(' + i + ')">' + (enabled ? '停用' : '启用') + '</button>' +
               '<button class="actb warn" onclick="uninstallPlugin(' + i + ',this)">卸载</button>') +
      '<button class="actb dim" onclick="moveCfgOrder(' + i + ',-1)">↑</button>' +
      '<button class="actb dim" onclick="moveCfgOrder(' + i + ',1)">↓</button>' +
      '</div></div>';
    if (defs.length && !ghost) {
      html += '<div class="varpanel" id="varPanel' + i + '" style="display:' + (open ? 'block' : 'none') + ';">';
      defs.forEach(function (d) {
        var val = (vp.values && vp.values[d.key]) || '';
        var isPw = (d.type || '').toLowerCase() === 'password';
        html += '<div class="vrow"><span class="ellip">' + esc(d.name || d.key) + '</span>' +
          '<input ' + (isPw ? 'type="password"' : 'type="text"') + ' data-platform="' + esc(pk) + '" data-varkey="' + esc(d.key) + '" placeholder="' + esc(d.key) + '" value="' + esc(val) + '" style="flex:1;min-width:0;"></div>';
      });
      html += '<div class="vrow"><span></span><button class="small" onclick="savePluginVars(' + i + ')">保存变量</button></div></div>';
    }
  });
  box.innerHTML = html;
}
function moveCfgOrder(i, dir) {
  var j = i + dir;
  if (j < 0 || j >= orderUI.length) return;
  var t = orderUI[i]; orderUI[i] = orderUI[j]; orderUI[j] = t;
  renderSrcList();
}
function removeOrderEntry(i) {
  orderUI.splice(i, 1);
  renderSrcList();
}
function resetCfgOrder() {
  orderUI = knownNames.slice();
  renderSrcList();
  toast('已重置为插件顺序（尚未保存，请点「保存设置」）');
}
function toggleVars(i) {
  var pk = orderUI[i];
  if (!pk) return;
  var panel = el('varPanel' + i);
  if (!panel) return;
  var willOpen = panel.style.display === 'none';
  panel.style.display = willOpen ? 'block' : 'none';
  window._openVarPlatform = willOpen ? pk : '';
  renderSrcList();
}
function savePluginVars(i) {
  var pk = orderUI[i];
  if (!pk) return;
  var panel = el('varPanel' + i);
  if (!panel) return;
  var vars = {};
  var inputs = panel.querySelectorAll('[data-varkey]');
  for (var n = 0; n < inputs.length; n++) vars[inputs[n].getAttribute('data-varkey')] = inputs[n].value;
  post('/api/plugins/vars', { platform: pk, vars: vars }).then(function (d) {
    toast(d.message || '已保存');
    loadSearchCfg();
  }).catch(function () { toast('保存失败'); });
}
function togglePlugin(i) {
  var pk = orderUI[i];
  if (!pk) return;
  post('/api/plugins/toggle', { name: pk, enabled: !isPluginEnabled(pk) }).then(function (d) {
    if (d && d.ok === false) { toast(d.error || '操作失败'); return; }
    toast(isPluginEnabled(pk) ? '已停用' : '已启用');
    loadSearchCfg();
  }).catch(function () { toast('操作失败'); });
}
/* 两步确认：不用原生 confirm()——部分手机 WebView 会吞掉弹窗直接返回 false，
   表现为"点卸载没反应"。第一次点变红显示确认文案，再点执行，超时自动复原。 */
function armConfirm(btn, msg, fn) {
  if (btn._armed) {
    btn._armed = false;
    clearTimeout(btn._armT);
    btn.textContent = btn._t0;
    btn.style.color = '';
    fn();
    return;
  }
  btn._armed = true;
  btn._t0 = btn.textContent;
  btn.textContent = msg;
  btn.style.color = 'var(--danger)';
  btn._armT = setTimeout(function () {
    btn._armed = false;
    btn.textContent = btn._t0;
    btn.style.color = '';
  }, 2600);
}
function uninstallPlugin(i, btn) {
  var pk = orderUI[i];
  if (!pk) return;
  armConfirm(btn, '确认卸载?', function () {
    post('/api/plugins/uninstall', { name: pk }).then(function (d) {
      if (d && d.ok === false) { toast(d.error || '卸载失败'); return; }
      toast('已卸载 ' + pk);
      loadSearchCfg();
    }).catch(function () { toast('卸载失败'); });
  });
}
function uninstallAll(btn) {
  if (!plugins.length && !orderUI.length) { toast('当前没有插件'); return; }
  armConfirm(btn, '确认全部卸载?', function () {
    btn.disabled = true;
    post('/api/plugins/uninstallAll', {}).then(function (d) {
      if (d && d.ok === false) { toast(d.error || '卸载失败'); return; }
      toast(d.message || '已全部卸载');
      loadSearchCfg();
    }).catch(function () { toast('卸载失败'); });
    setTimeout(function () { btn.disabled = false; }, 800);
  });
}
function isPluginEnabled(pk) {
  for (var k = 0; k < plugins.length; k++) {
    if ((plugins[k].platform || plugins[k].name) === pk) return !!plugins[k].enabled;
  }
  return false;
}
function orderedCfg() {
  return orderUI.slice();
}
function saveSearchCfg() {
  var order = orderedCfg();
  cfgOrder = order; orderUI = order;
  post('/api/search/config', {
    sourceOrder: order,
    sortBy: el('cfgSortBy').value,
    asc: el('cfgSortAsc').value === '1',
    maxTotal: parseInt(el('cfgMaxTotal').value || '60', 10) || 60
  }).then(function (d) { toast(d.message || '已保存'); renderSrcList(); })
    .catch(function () { toast('保存失败'); });
}
function saveCfgInline() {
  post('/api/search/config', {
    sourceOrder: orderedCfg(),
    sortBy: el('sortSel').value,
    asc: el('ascSel').value === '1',
    maxTotal: parseInt((el('cfgMaxTotal') ? el('cfgMaxTotal').value : '60') || '60', 10)
  }).then(function (d) { toast(d.message || '已保存'); })
    .catch(function () { toast('保存失败'); });
}

/* ---------------- 搜索 ---------------- */
var searchResults = [], sPage = 1, sSize = 20, sPlugin = null;
var searchPerSrc = [];
var sTimer = null;
function doSearch() {
  var q = el('searchQ').value.trim();
  if (!q) return;
  if (sTimer) { clearInterval(sTimer); sTimer = null; }
  searchResults = [];
  searchPerSrc = [];
  sPlugin = null;
  sPage = 1;
  var url = '/api/search?q=' + encodeURIComponent(q);
  var sel = Object.keys(srcSel);
  if (sel.length) url += '&sources=' + encodeURIComponent(sel.join(','));
  var minD = parseInt(el('minD').value, 10);
  if (!isNaN(minD) && minD > 0) url += '&minD=' + minD;
  var maxD = parseInt(el('maxD').value, 10);
  if (!isNaN(maxD) && maxD > 0) url += '&maxD=' + maxD;
  if (el('needArt').checked) url += '&art=1';
  url += '&sort=' + encodeURIComponent(el('sortSel').value);
  url += '&asc=' + el('ascSel').value;
  toast('搜索中…');
  el('searchBox').innerHTML = '<div class="empty">正在搜索…</div>';
  el('searchInfo').textContent = '搜索中…';
  el('playAllBtn').style.display = 'none';
  el('collectAllBtn').style.display = 'none';
  el('searchCard').style.display = '';
  api(url).then(function (d) {
    if (!d || !d.ok) { toast(d && d.error || '搜索失败'); return; }
    if (!d.id) { searchResults = d.results || []; searchPerSrc = d.perSource || []; renderSearch(); return; }
    sTimer = setInterval(function () { pollSearch(d.id); }, 1200);
    pollSearch(d.id);
  }).catch(function () { toast('搜索失败'); });
}
var pollFetching = false;
function pollSearch(id) {
  /* 在途标记：慢网下响应未回时跳过本轮，避免 1.2s 轮询堆积并发拖死页面 */
  if (pollFetching) return;
  pollFetching = true;
  api('/api/search/poll?id=' + encodeURIComponent(id)).then(function (d) {
    pollFetching = false;
    if (!d || !d.ok) {
      clearInterval(sTimer); sTimer = null;
      el('searchInfo').textContent = '共 ' + (searchResults.length) + ' 条';
      return;
    }
    searchResults = d.results || [];
    searchPerSrc = d.perSource || [];
    if (!searchResults.length) sPage = 1;
    var prog = ' · 已搜索 ' + (d.done || 0) + '/' + (d.totalEnabled || 0) + ' 个音源…';
    el('searchInfo').textContent = '共 ' + (d.total || 0) + ' 条' + (d.finished ? '' : prog);
    el('playAllBtn').style.display = searchResults.length ? '' : 'none';
    el('collectAllBtn').style.display = searchResults.length ? '' : 'none';
    renderSearch();
    if (d.finished) { clearInterval(sTimer); sTimer = null; el('searchInfo').textContent = '共 ' + (d.total || 0) + ' 条'; }
  }).catch(function () { pollFetching = false; });
}
function playAllSearch() {
  if (!searchResults.length) return;
  // 跨源全部入队：当前站点过滤下的所有结果，每条 raw 补全自身 platform，
  // 后端按条目来源插件分别解析（旧实现只播放结果最多的单一音源，其余源被丢弃）
  var items = collectTargets();
  if (!items.length) { toast('没有可播放的结果'); return; }
  post('/api/play/queue', { plugin: items[0].platform, items: items }).then(function (d) {
    toast(d.message || '已播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  }).catch(function () { toast('播放失败'); });
}

/* ---------------- 全部收藏（搜索结果批量加入收藏夹） ---------------- */
function collectTargets() {
  // 当前站点过滤下可见的全部歌曲：raw 补全 platform 后返回（收藏/回放需要来源插件）
  var out = [];
  searchResults.forEach(function (it) {
    if (sPlugin && it.plugin !== sPlugin) return;
    var raw = it.raw || {};
    if (!raw.platform) {
      raw = JSON.parse(JSON.stringify(raw));
      raw.platform = it.plugin;
    }
    out.push(raw);
  });
  return out;
}
function openCollectAll() {
  var items = collectTargets();
  if (!items.length) { toast('当前没有可收藏的结果'); return; }
  el('collectModalTitle').textContent = '全部收藏到…';
  el('collectModalSub').textContent = '将当前列表的 ' + items.length + ' 首加入所选收藏夹（已收藏的自动跳过）';
  el('collectNewName').value = '';
  renderCollectList();
  el('collectModal').className = 'show';
}
function closeCollectAll() { el('collectModal').className = ''; }
function renderCollectList() {
  api('/api/fav/lists').then(function (d) {
    var box = el('collectList');
    var list = d.lists || [];
    window._collectLists = list;
    var items = collectTargets();
    var html = '';
    list.forEach(function (l, i) {
      html += '<div class="fitem" onclick="pickCollectList(' + i + ')">' +
        '<span class="ck">♡</span>' +
        '<span class="ellip">' + esc(l.name) + '</span>' +
        '<span class="cnt">' + l.count + ' 首</span></div>';
    });
    box.innerHTML = html || '<div class="empty">还没有收藏夹，可在下方新建</div>';
  }).catch(function () { toast('加载收藏夹失败'); });
}
function doCollectAll(listId, listName) {
  var items = collectTargets();
  if (!items.length) { toast('当前没有可收藏的结果'); return; }
  post('/api/fav/addAll', { listId: listId, items: items }).then(function (d) {
    if (d.ok) {
      toast(d.added > 0 ? '已加入「' + listName + '」' + d.added + ' 首' : '「' + listName + '」内已全部收藏');
      closeCollectAll();
    } else toast(d.error || '收藏失败');
  }).catch(function () { toast('收藏失败'); });
}
function pickCollectList(i) {
  var l = (window._collectLists || [])[i];
  if (l) doCollectAll(l.id, l.name);
}
function createCollectAndAdd() {
  var name = (el('collectNewName').value || '').trim();
  if (!name) { toast('请输入收藏夹名称'); return; }
  post('/api/fav/lists/create', { name: name }).then(function (d) {
    el('collectNewName').value = '';
    if (d.ok && d.id) doCollectAll(d.id, name);
    else toast('新建失败');
  }).catch(function () { toast('新建失败'); });
}
function renderSearch() {
  var box = el('searchBox');
  renderPluginBar();
  el('playAllBtn').style.display = searchResults.length ? '' : 'none';
  el('collectAllBtn').style.display = searchResults.length ? '' : 'none';
  var view = [];
  searchResults.forEach(function (it, i) { if (!sPlugin || it.plugin === sPlugin) view.push(i); });
  if (!searchResults.length) { box.innerHTML = '<div class="empty">没有结果</div>'; el('searchPager').innerHTML = ''; return; }
  if (!view.length) { box.innerHTML = '<div class="empty">该站点没有结果</div>'; el('searchPager').innerHTML = ''; return; }
  var pages = Math.max(1, Math.ceil(view.length / sSize));
  if (sPage > pages) sPage = pages;
  var html = '';
  view.slice((sPage - 1) * sSize, sPage * sSize).forEach(function (i) {
    var it = searchResults[i];
    html += '<div class="row">' +
      '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
      '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.plugin) + '</div></div>' +
      '<button class="small ghost" id="sfav' + i + '" onclick="favResult(' + i + ')">♡</button>' +
      '<button class="small" onclick="playResult(' + i + ')">播放</button></div>';
  });
  box.innerHTML = html;
  var pg = el('searchPager');
  pg.innerHTML = pages > 1 ?
    '<button class="ghost" ' + (sPage <= 1 ? 'disabled' : '') + ' onclick="sGo(' + (sPage - 1) + ')">‹</button>' +
    '<span>' + sPage + ' / ' + pages + '</span>' +
    '<button class="ghost" ' + (sPage >= pages ? 'disabled' : '') + ' onclick="sGo(' + (sPage + 1) + ')">›</button>' : '';
}
function sGo(p) { sPage = p; renderSearch(); }
function renderPluginBar() {
  var bar = el('searchPluginBar');
  if (!bar) return;
  var names = [], seen = {};
  var srcs = searchPerSrc.length ? searchPerSrc : [];
  srcs.forEach(function (s) { if (s.plugin && !seen[s.plugin]) { seen[s.plugin] = 1; names.push(s.plugin); } });
  if (names.length > 1) {
    bar.style.display = '';
    bar.innerHTML = '';
    var mk = function (label, val) {
      var chip = document.createElement('span');
      chip.className = 'chip' + (sPlugin === val ? ' on' : '');
      chip.textContent = label;
      chip.onclick = function () { sPlugin = val; sPage = 1; renderSearch(); };
      bar.appendChild(chip);
    };
    mk('全部', null);
    names.forEach(function (n) { mk(n, n); });
  } else {
    bar.style.display = 'none';
    bar.innerHTML = '';
  }
}
function playResult(i) {
  var it = searchResults[i];
  post('/api/play', { plugin: it.plugin, raw: it.raw }).then(function (d) {
    toast(d.message || '已播放');
  }).catch(function () { toast('播放失败'); });
}
/* ---------------- 收藏 ---------------- */
var favLists = [], curFavId = null, favPage = 1, favSize = 20;
var NOW_PLAYING_ID = '__nowplaying__';
var HISTORY_ID = '__history__';
var histCount = 0;
function loadFavLists() {
  /* 历史已并入收藏页（原独立页签已删）：并行拉一次计数（/api/history 本地 ≤100 条，开销可忽略） */
  Promise.all([
    api('/api/fav/lists'),
    api('/api/history').then(function (d) { histCount = (d.items || []).length; }).catch(function () { histCount = 0; })
  ]).then(function (rs) {
    var d = rs[0];
    favLists = d.lists || [];
    if (!curFavId && favLists.length) curFavId = favLists[0].id;
    var bar = el('favListBar');
    bar.innerHTML = '';
    /* 「正在播放」虚拟专辑置顶：内容是当前播放队列，点击看队列并可跳播/收藏 */
    var qn = (lastStatus && lastStatus.queue ? lastStatus.queue.length : 0);
    var nowChip = document.createElement('span');
    nowChip.className = 'chip' + (curFavId === NOW_PLAYING_ID ? ' on' : '');
    nowChip.textContent = '▶ 正在播放 (' + qn + ')';
    nowChip.onclick = function () { curFavId = NOW_PLAYING_ID; favPage = 1; loadFavLists(); };
    bar.appendChild(nowChip);
    /* 「历史」虚拟专辑：电视端最近播放，可回放/收藏/清空 */
    var histChip = document.createElement('span');
    histChip.className = 'chip' + (curFavId === HISTORY_ID ? ' on' : '');
    histChip.textContent = '🕘 历史 (' + histCount + ')';
    histChip.onclick = function () { curFavId = HISTORY_ID; favPage = 1; loadFavLists(); };
    bar.appendChild(histChip);
    favLists.forEach(function (l) {
      var chip = document.createElement('span');
      chip.className = 'chip' + (l.id === curFavId ? ' on' : '');
      chip.textContent = l.name + ' (' + l.count + ')';
      chip.onclick = function () { curFavId = l.id; favPage = 1; loadFavLists(); };
      if (l.id !== 'fav_default') {
        var rn = document.createElement('span'); rn.className = 'x'; rn.textContent = '✎';
        rn.onclick = function (e) { e.stopPropagation(); renameFavList(l); };
        chip.appendChild(rn);
        var rm = document.createElement('span'); rm.className = 'x'; rm.textContent = '✕';
        rm.onclick = function (e) { e.stopPropagation(); removeFavList(l); };
        chip.appendChild(rm);
      }
      bar.appendChild(chip);
    });
    loadFavItems();
  }).catch(function () {});
}
function createFavList() {
  var name = el('favNewName').value.trim();
  if (!name) return;
  post('/api/fav/lists/create', { name: name }).then(function () {
    toast('已创建：' + name); el('favNewName').value = ''; loadFavLists();
  }).catch(function () { toast('创建失败'); });
}
function renameFavList(l) {
  var name = prompt('重命名专辑', l.name);
  if (!name || !name.trim()) return;
  post('/api/fav/lists/rename', { id: l.id, name: name.trim() }).then(loadFavLists);
}
function removeFavList(l) {
  if (!confirm('删除专辑「' + l.name + '」及其收藏？')) return;
  post('/api/fav/lists/remove', { id: l.id }).then(function () {
    if (curFavId === l.id) curFavId = null;
    loadFavLists();
  });
}
function loadFavItems() {
  var box = el('favItems'), act = el('favActions');
  act.innerHTML = '';
  if (curFavId === NOW_PLAYING_ID) {
    /* 「正在播放」虚拟专辑：内容为当前播放队列，可跳播、可收藏，不可移出 */
    el('favPager').innerHTML = '';
    api('/api/player').then(function (d) {
      var q = (d && d.queue) || [];
      lastStatus = d;
      if (!q.length) { box.innerHTML = '<div class="empty">队列为空，去搜索推歌吧</div>'; return; }
      var html = '';
      q.forEach(function (it, k) {
        html += '<div class="row">' +
          '<div class="grow"><div class="ellip" style="font-size:15px;">' + (it.index === d.index ? '▶ ' : '') + esc(it.title) + '</div>' +
          '<div class="muted ellip">' + esc(it.artist) + (it.album ? ' · ' + esc(it.album) : '') + '</div></div>' +
          '<button class="small" onclick="skipTo(' + it.index + ')">播放</button>' +
          '<button class="ghost small" onclick="qfav(' + it.index + ')">' + (it.faved ? '♥' : '♡') + '</button></div>';
      });
      box.innerHTML = html;
    }).catch(function () { box.innerHTML = '<div class="empty">加载失败</div>'; });
    return;
  }
  if (curFavId === HISTORY_ID) {
    /* 「历史」虚拟专辑：电视端最近播放（最多 100 条），可回放/收藏；清空为两步确认 */
    el('favPager').innerHTML = '';
    api('/api/history').then(function (d) {
      var items = d.items || [];
      histCount = items.length;
      act.innerHTML = items.length ?
        '<button class="danger small" id="histClearBtn" onclick="clearHistory(this)">🗑 清空历史</button>' : '';
      if (!items.length) { box.innerHTML = '<div class="empty">还没有播放记录，去搜首歌吧</div>'; return; }
      window._histItems = items;
      var html = '';
      items.forEach(function (it, k) {
        html += '<div class="row">' +
          '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
          '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.platform || '') + '</div></div>' +
          '<button class="ghost small" id="hfav' + k + '" onclick="favHistSong(' + k + ')">♡</button>' +
          '<button class="small" onclick="playHistoryItem(' + k + ')">播放</button></div>';
      });
      box.innerHTML = html;
    }).catch(function () { box.innerHTML = '<div class="empty">加载失败</div>'; });
    return;
  }
  if (!curFavId) { box.innerHTML = '<div class="empty">还没有专辑，点上方「新建」。</div>'; el('favPager').innerHTML = ''; return; }
  api('/api/fav/items?id=' + encodeURIComponent(curFavId) + '&page=' + favPage + '&size=' + favSize).then(function (d) {
    if (!d.ok) { box.innerHTML = '<div class="empty">' + esc(d.error) + '</div>'; return; }
    var items = d.items || [];
    if (d.total > 0) {
      act.innerHTML = '<button class="ghost small" onclick="playFavAlbum()">▶ 播放整个专辑（' + d.total + ' 首）</button>';
    }
    if (!items.length) { box.innerHTML = '<div class="empty">这个专辑还没有歌。</div>'; el('favPager').innerHTML = ''; return; }
    var html = '';
    window._favItems = d.items;
    items.forEach(function (it, k) {
      html += '<div class="row">' +
        '<div class="grow"><div class="ellip" style="font-size:15px;">' + esc(it.title) + '</div>' +
        '<div class="muted ellip">' + esc(it.artist) + ' · ' + esc(it.platform || '') + '</div></div>' +
        '<button class="small" onclick="playFavItem(' + k + ')">播放</button>' +
        '<button class="danger small" onclick="removeFavItem(' + k + ')">移出</button></div>';
    });
    box.innerHTML = html;
    var pages = Math.max(1, Math.ceil(d.total / d.size));
    el('favPager').innerHTML = pages > 1 ?
      '<button class="ghost" ' + (d.page <= 1 ? 'disabled' : '') + ' onclick="favGo(' + (d.page - 1) + ')">‹</button>' +
      '<span>' + d.page + ' / ' + pages + ' · 共 ' + d.total + '</span>' +
      '<button class="ghost" ' + (d.page >= pages ? 'disabled' : '') + ' onclick="favGo(' + (d.page + 1) + ')">›</button>' : '';
  }).catch(function () {});
}
function favGo(p) { favPage = p; loadFavItems(); }
function playFavItem(i) {
  var it = (window._favItems || [])[i];
  if (!it) return;
  post('/api/play', { plugin: it.platform, raw: it.raw }).then(function (d) { toast(d.message || '已播放'); });
}
function removeFavItem(i) {
  var it = (window._favItems || [])[i];
  if (!it) return;
  post('/api/fav/toggle', { listId: curFavId, raw: it.raw }).then(loadFavLists);
}
function playFavAlbum() {
  post('/api/fav/play', { id: curFavId }).then(function (d) {
    toast(d.message || '已开始播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  });
}

/* ---------------- 播放历史（并入收藏页「🕘 历史」虚拟专辑） ---------------- */
function favHistSong(i) {
  var it = (window._histItems || [])[i];
  if (!it) return;
  favCtx = { mode: 'hist', idx: i, item: it };
  el('favModalTitle').textContent = '收藏到…（' + (it.title || '') + '）';
  loadFavAlbums();
  el('favModal').className = 'show';
}
function playHistoryItem(i) {
  var it = (window._histItems || [])[i];
  if (!it) return;
  post('/api/play', { plugin: it.platform, raw: it.raw }).then(function (d) {
    toast(d.message || '已播放');
    switchTab('player');
    setTimeout(loadPlayer, 600);
  }).catch(function () { toast('播放失败'); });
}
/* 微信等 WebView 会吞掉原生 confirm()（直接返回 false），改用两步确认：
   第一次点变红「确认清空？」，2.6 秒未操作自动复原，第二次点才执行。 */
function clearHistory(btn) {
  if (!btn) return;
  if (btn.getAttribute('data-armed') === '1') {
    btn.removeAttribute('data-armed');
    post('/api/history/clear', {}).then(function (d) {
      toast(d.message || '已清空');
      loadFavLists();
    });
    return;
  }
  btn.setAttribute('data-armed', '1');
  btn.textContent = '确认清空？';
  setTimeout(function () {
    if (btn.getAttribute('data-armed') === '1') {
      btn.removeAttribute('data-armed');
      btn.textContent = '🗑 清空历史';
    }
  }, 2600);
}

/* ---------------- 管理 ---------------- */
function loadSubs() {
  api('/api/subscriptions').then(function (d) {
    var box = el('subList');
    var list = d.subscriptions || [];
    if (!list.length) { box.innerHTML = '<div class="empty">还没有订阅源</div>'; return; }
    var html = '';
    list.forEach(function (s) {
      html += '<div class="row"><span class="grow muted ellip" title="' + esc(s.url) + '">' + esc(s.url) + '</span>' +
        '<button class="danger small" onclick="removeSub(this)" data-url="' + esc(s.url) + '">删除</button></div>';
    });
    box.innerHTML = html;
  }).catch(function () {});
}
function addSub() {
  var url = el('subUrl').value.trim();
  if (!url) return;
  post('/api/subscriptions', { url: url }).then(function (d) {
    toast(d.message || '已添加'); el('subUrl').value = '';
    loadSubs(); loadSearchCfg();
  }).catch(function () { toast('添加失败'); });
}
function removeSub(btn) {
  post('/api/subscriptions/remove', { url: btn.getAttribute('data-url') }).then(function () {
    toast('已删除订阅'); loadSubs();
  });
}
function syncAll() {
  api('/api/sync', { method: 'POST' }).then(function (d) {
    toast(d.message || '开始同步');
    setTimeout(loadSearchCfg, 4000);
  }).catch(function () { toast('同步失败'); });
}

/* ---------------- 主题 ---------------- */
var themes = [], curTheme = null;
function applyTheme(id) {
  var t = themes.filter(function (x) { return x.id === id; })[0];
  if (!t) return;
  var r = document.documentElement.style;
  r.setProperty('--accent', '#' + t.accent);
  r.setProperty('--accent2', '#' + (t.accent2 || t.accent));
  r.setProperty('--bg', '#' + t.bg);
  if (t.card) {
    r.setProperty('--card', '#' + t.card);
    r.setProperty('--card2', '#' + t.card);
  }
  // 文字/次要文字/描边跟随主题，避免切主题后出现与色板不搭的硬编码灰蓝
  if (t.text) r.setProperty('--text', '#' + t.text);
  if (t.muted) r.setProperty('--muted', '#' + t.muted);
  if (t.line) r.setProperty('--line', '#' + t.line);
  if (t.radius) r.setProperty('--radius', t.radius + 'px');
  curTheme = id;
  renderThemeBar();
}
function renderThemeBar() {
  var bar = el('themeBar');
  bar.innerHTML = '';
  themes.forEach(function (t) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (t.id === curTheme ? ' on' : '');
    chip.style.borderColor = '#' + t.accent;
    chip.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border-radius:50%;background:#' + t.accent + ';"></span>' + esc(t.name);
    chip.onclick = function () { setTheme(t.id); };
    bar.appendChild(chip);
  });
}
function setTheme(id) {
  post('/api/theme', { id: id }).then(function () {
    applyTheme(id);
    toast('已切换主题');
  }).catch(function () { toast('切换失败'); });
}
function loadThemes() {
  api('/api/themes').then(function (d) {
    themes = d.themes || [];
    applyTheme(d.current || (themes[0] && themes[0].id));
  }).catch(function () {});
}

/* ---------------- 播放页歌词设置 ---------------- */
var lyricCfg = { fontSizeSp: 40, colorHex: 'FFFFFF' };
var LRC_COLORS = [
  { hex: 'FFFFFF', name: '白' },
  { hex: 'FF6B9D', name: '粉' },
  { hex: '4A7DFF', name: '蓝' },
  { hex: 'FFB74D', name: '橙' },
  { hex: '34D399', name: '绿' }
];
function renderLyric() {
  el('lyricSize').textContent = lyricCfg.fontSizeSp + ' sp';
  el('lyricColor').value = '#' + lyricCfg.colorHex;
  var cb = el('lyricColorBar');
  cb.innerHTML = '';
  LRC_COLORS.forEach(function (c) {
    var chip = document.createElement('span');
    chip.className = 'chip' + (c.hex === lyricCfg.colorHex ? ' on' : '');
    chip.style.borderColor = '#' + c.hex;
    chip.innerHTML = '<span style="display:inline-block;width:12px;height:12px;border-radius:50%;background:#' + c.hex + ';"></span>' + c.name;
    chip.onclick = function () { saveLyric({ colorHex: c.hex }); };
    cb.appendChild(chip);
  });
}
function saveLyric(patch) {
  var body = {
    fontSizeSp: patch.fontSizeSp != null ? patch.fontSizeSp : lyricCfg.fontSizeSp,
    colorHex: patch.colorHex != null ? patch.colorHex : lyricCfg.colorHex
  };
  post('/api/lyric', body).then(function (d) {
    if (d.ok) { lyricCfg = body; renderLyric(); }
  }).catch(function () { toast('保存失败'); });
}
function stepLyricSize(delta) {
  saveLyric({ fontSizeSp: Math.min(40, Math.max(10, lyricCfg.fontSizeSp + delta)) });
}
function setLyricColor(v) { saveLyric({ colorHex: String(v).replace('#', '').toUpperCase() }); }
function loadLyric() {
  api('/api/lyric').then(function (d) {
    if (d.ok) {
      lyricCfg = { fontSizeSp: d.fontSizeSp, colorHex: d.colorHex };
      renderLyric();
    }
  }).catch(function () {});
}

/* ---------------- 歌词/封面补全（lrc.cx）+ 播放兜底 + 封面形状 ---------------- */
var metaCfg = { enabled: true, fallbackOtherSource: true, minPlaySeconds: 90, preferPlugin: '', fallbackStrategy: 'staggered', coverShape: 'circle', coverSpinMs: 10000, coverSpinDir: 'cw' };
/* 服务端每次 POST /api/meta 都回全量字段，统一用回显刷新本地副本，避免各处手工复制漏字段 */
function syncMeta(d) {
  metaCfg = {
    enabled: d.enabled != null ? d.enabled : true,
    fallbackOtherSource: d.fallbackOtherSource != null ? d.fallbackOtherSource : true,
    minPlaySeconds: d.minPlaySeconds != null ? d.minPlaySeconds : 90,
    preferPlugin: d.preferPlugin || '',
    fallbackStrategy: d.fallbackStrategy || 'staggered',
    coverShape: d.coverShape || 'circle',
    coverSpinMs: d.coverSpinMs != null ? d.coverSpinMs : 10000,
    coverSpinDir: d.coverSpinDir || 'cw'
  };
  renderMeta();
}
function renderMeta() {
  el('metaToggle').textContent = metaCfg.enabled ? '开' : '关';
  el('metaToggle').className = 'small' + (metaCfg.enabled ? '' : ' ghost');
  el('metaFallbackToggle').textContent = metaCfg.fallbackOtherSource ? '开' : '关';
  el('metaFallbackToggle').className = 'small' + (metaCfg.fallbackOtherSource ? '' : ' ghost');
  if (document.activeElement !== el('minPlaySeconds')) el('minPlaySeconds').value = metaCfg.minPlaySeconds;
  var sel = el('preferPlugin');
  if (document.activeElement !== sel && sel.options.length > 0) sel.value = metaCfg.preferPlugin || '';
  var ssel = el('fallbackStrategy');
  if (document.activeElement !== ssel && ssel.options.length > 0) ssel.value = metaCfg.fallbackStrategy || 'staggered';
  var csel = el('coverShape');
  if (document.activeElement !== csel) csel.value = metaCfg.coverShape === 'square' ? 'square' : 'circle';
  var dsel = el('coverSpinDir');
  if (document.activeElement !== dsel && dsel.options.length > 0) dsel.value = metaCfg.coverSpinDir === 'ccw' ? 'ccw' : 'cw';
  if (document.activeElement !== el('coverSpinSec')) el('coverSpinSec').value = Math.round((metaCfg.coverSpinMs || 0) / 1000);
}
function fillPreferOptions() {
  // 选项来自已启用插件列表（/api/plugins）；换源候选按 platform 匹配，故 value 用 platform
  api('/api/plugins').then(function (d) {
    var sel = el('preferPlugin');
    var cur = metaCfg.preferPlugin || '';
    var html = '<option value="">（按音源顺序）</option>';
    html += '<option value="*">聚合搜索（最快命中）</option>';
    ((d && d.plugins) || []).forEach(function (p) {
      if (p.enabled === false) return;
      var val = p.platform || p.name || '';
      var label = p.name || p.platform || '';
      if (!val) return;
      html += '<option value="' + val.replace(/"/g, '&quot;') + '">' + label + '</option>';
    });
    sel.innerHTML = html;
    sel.value = cur;
    if (sel.value !== cur) { sel.value = ''; }
  }).catch(function () {});
}
function savePrefer() {
  var v = el('preferPlugin').value || '';
  post('/api/meta', { preferPlugin: v }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(v === '*' ? '优先换源：聚合搜索（最快命中）' : (v ? '优先换源插件：' + v : '已清除优先插件，按音源顺序')); }
  }).catch(function () { toast('保存失败'); });
}
function saveFallbackStrategy() {
  var v = el('fallbackStrategy').value || 'staggered';
  var label = {
    staggered: '分批错峰',
    allParallel: '全候选同时启动',
    sequential: '严格顺序',
    preferFirst: '只试第 1 名'
  }[v] || v;
  post('/api/meta', { fallbackStrategy: v }).then(function (d) {
    if (d.ok) { syncMeta(d); toast('换源策略：' + label); }
  }).catch(function () { toast('保存失败'); });
}
function toggleMeta() {
  // 只改 enabled；其余字段由服务端保持原值（缺省字段不覆盖）
  post('/api/meta', { enabled: !metaCfg.enabled }).then(function (d) {
    if (d.ok) { syncMeta(d); }
  }).catch(function () { toast('保存失败'); });
}
function toggleMetaFallback() {
  post('/api/meta', { fallbackOtherSource: !metaCfg.fallbackOtherSource }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(d.fallbackOtherSource ? '已开启换插件救场' : '已关闭换插件救场'); }
  }).catch(function () { toast('保存失败'); });
}
function saveMinPlay() {
  var v = parseInt(el('minPlaySeconds').value || '90', 10);
  if (isNaN(v) || v < 0) v = 0; if (v > 300) v = 300;
  post('/api/meta', { minPlaySeconds: v }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(v > 0 ? '已保存：播放不足 ' + d.minPlaySeconds + ' 秒自动换源重播' : '已关闭短播换源'); }
  }).catch(function () { toast('保存失败'); });
}
function saveCoverShape() {
  var v = el('coverShape').value === 'square' ? 'square' : 'circle';
  post('/api/meta', { coverShape: v }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(v === 'square' ? '封面已改为方形圆角' : '封面已改为圆形'); }
  }).catch(function () { toast('保存失败'); });
}
function saveCoverSpin() {
  var sec = parseInt(el('coverSpinSec').value || '0', 10);
  if (isNaN(sec) || sec < 0) sec = 0; if (sec > 120) sec = 120;
  post('/api/meta', { coverSpinMs: sec * 1000 }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(sec > 0 ? ('封面 ' + sec + ' 秒转一圈') : '封面不旋转'); }
  }).catch(function () { toast('保存失败'); });
}
function saveCoverSpinDir() {
  var v = el('coverSpinDir').value === 'ccw' ? 'ccw' : 'cw';
  post('/api/meta', { coverSpinDir: v }).then(function (d) {
    if (d.ok) { syncMeta(d); toast(v === 'ccw' ? '封面逆时针旋转' : '封面顺时针旋转'); }
  }).catch(function () { toast('保存失败'); });
}
function quickCoverSpin(sec) { el('coverSpinSec').value = sec; saveCoverSpin(); }
function loadMeta() {
  api('/api/meta').then(function (d) {
    if (d.ok) {
      syncMeta(d);
      fillPreferOptions();
    }
  }).catch(function () {});
}

/* ---------------- 待机显示（无操作自动进入播放器页） ---------------- */
var idleCfg = { enabled: true, minutes: 1 };
function renderIdle() {
  el('idleToggle').textContent = idleCfg.enabled ? '开' : '关';
  el('idleToggle').className = 'small' + (idleCfg.enabled ? '' : ' ghost');
  if (document.activeElement !== el('idleMinutes')) el('idleMinutes').value = idleCfg.minutes || 1;
}
function toggleIdle() {
  post('/api/idle', { enabled: !idleCfg.enabled }).then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled, minutes: d.minutes }; renderIdle(); toast(d.enabled ? '已开启待机显示' : '已关闭待机显示'); }
  }).catch(function () { toast('保存失败'); });
}
function saveIdleMinutes() {
  var m = parseInt(el('idleMinutes').value || '1', 10) || 1;
  if (m < 1) m = 1; if (m > 60) m = 60;
  post('/api/idle', { minutes: m }).then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled, minutes: d.minutes }; renderIdle(); toast('已保存：无操作 ' + d.minutes + ' 分钟进入播放器'); }
  }).catch(function () { toast('保存失败'); });
}
function loadIdle() {
  api('/api/idle').then(function (d) {
    if (d.ok) { idleCfg = { enabled: d.enabled != null ? d.enabled : true, minutes: d.minutes || 1 }; renderIdle(); }
  }).catch(function () {});
}

/* ---------------- 配置导出/导入 ---------------- */
function exportConfig() {
  api('/api/export').then(function (d) {
    if (!d.ok) { toast('导出失败'); return; }
    var blob = new Blob([JSON.stringify(d, null, 2)], { type: 'application/json' });
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'musicfree-tv-config.json';
    document.body.appendChild(a);
    a.click();
    setTimeout(function () { document.body.removeChild(a); URL.revokeObjectURL(a.href); }, 200);
    toast('已导出配置');
  }).catch(function () { toast('导出失败'); });
}

/* ---------------- 插件备份（js 地址+订阅+音源顺序，不含源码） ---------------- */
function exportPluginsBackup() {
  // 服务端 respondDownload 带 attachment 头，直接跳转即触发浏览器下载
  var a = document.createElement('a');
  a.href = '/api/plugins/export';
  document.body.appendChild(a);
  a.click();
  setTimeout(function () { document.body.removeChild(a); }, 200);
  toast('已开始下载插件备份');
}
function importPluginsBackup(input) {
  var f = input.files && input.files[0];
  if (!f) return;
  var reader = new FileReader();
  reader.onload = function () {
    var data;
    try { data = JSON.parse(reader.result); } catch (e) { toast('文件不是合法 JSON'); input.value = ''; return; }
    toast('正在恢复插件（按地址拉取安装），请稍候…');
    post('/api/plugins/import', data).then(function (d) {
      if (d.ok) {
        toast(d.message || '恢复完成');
        loadSubs();
        loadSearchCfg();
      } else {
        toast(d.error || '导入失败');
      }
    }).catch(function () { toast('导入失败'); });
    input.value = '';
  };
  reader.readAsText(f);
}
function importConfig(input) {
  var f = input.files && input.files[0];
  if (!f) return;
  var reader = new FileReader();
  reader.onload = function () {
    var data;
    try { data = JSON.parse(reader.result); } catch (e) { toast('文件不是合法 JSON'); input.value = ''; return; }
    post('/api/import', data).then(function (d) {
      if (d.ok) {
        var im = d.imported || {};
        toast('导入成功：' + (im.theme ? '主题✓' : '') + (im.lyric ? ' 歌词✓' : '') + ' 专辑 ' + (im.lists || 0) + ' 个');
        loadThemes();
        loadLyric();
        curFavId = null;
        loadFavLists();
      } else {
        toast('导入失败');
      }
    }).catch(function () { toast('导入失败'); });
    input.value = '';
  };
  reader.readAsText(f, 'utf-8');
}

/* ---------------- 插件：与电视端 APK 同一套操作 ---------------- */
/* 排行榜：选平台 → 分组榜单卡片 → 榜单歌曲；推荐歌单：选平台 → 分类 → 歌单卡片 → 歌曲。
   点歌直接投屏播放；原始 JSON 只是排查用的折叠面板，不是另一套操作。 */
var pgCaps = [], pgView = '', pgPlatform = '', pgTags = [], pgTag = null;
var pgSheets = [], pgSheetPage = 1, pgSheetEnd = true, pgGroups = [];
var pgDetail = null, pgSongPage = 1, pgSongEnd = true, pgSongs = [], pgRawData = null;

/* 图片一律走电视端 /api/img 代理。手机浏览器可能没有外网出口（只能连到电视），
   或者被图床防盗链按 Referer 拦掉，直连 CDN 会整页裂图；电视端一定有外网。 */
function imgSrc(u) { return u ? '/api/img?url=' + encodeURIComponent(u) : ''; }
/* 图片加载失败时回落 ♪ 占位。onerror 属性是双引号包裹的，里面绝不能再出现双引号，
   否则提前闭合属性、后面全被当正文显示（详见 AGENTS.md 坑区）。 */
function artFail(img) {
  var ph = document.createElement('div');
  ph.className = 'ph';
  ph.textContent = '♪';
  if (img && img.parentNode) img.parentNode.replaceChild(ph, img);
}
function artHtml(u) {
  return u
    ? '<img src="' + esc(imgSrc(u)) + '" alt="" onerror="artFail(this)">'
    : '<div class="ph">♪</div>';
}
function rowArt(u) {
  return u ? '<img src="' + esc(imgSrc(u)) + '" alt="" onerror="this.style.display=\'none\'">' : '';
}

function pgHome() {
  pgView = ''; pgPlatform = ''; pgDetail = null;
  el('pgHome').style.display = '';
  el('pgBrowse').style.display = 'none';
  el('pgDetailCard').style.display = 'none';
}
function pgOpen(view) {
  pgView = view; pgPlatform = ''; pgDetail = null; pgTags = []; pgTag = null;
  pgSheets = []; pgGroups = []; pgSheetPage = 1; pgSheetEnd = true;
  el('pgHome').style.display = 'none';
  el('pgDetailCard').style.display = 'none';
  el('pgBrowse').style.display = '';
  el('pgTitle').textContent = view === 'toplist' ? '排行榜' : '推荐歌单';
  el('pgInfo').textContent = '';
  el('pgBody').innerHTML = '<div class="empty"><span class="spin"></span></div>';
  el('pgMoreBtn').style.display = 'none';
  pgRawHide();
  api('/api/plugin/caps').then(function (d) {
    pgCaps = (d && d.plugins) || [];
    // 平台 chip 只列出支持当前玩法的插件，与电视端 probePlugins 同条件
    var list = pgCaps.filter(function (p) { return view === 'toplist' ? p.topLists : p.recommend; });
    if (!list.length) {
      el('pgBody').innerHTML = '<div class="empty">' +
        (view === 'toplist' ? '已启用的插件均不提供排行榜' : '已启用的插件均不支持推荐歌单') + '</div>';
      el('pgPlatforms').innerHTML = '';
      return;
    }
    pgPlatform = list[0].platform;
    var html = '';
    list.forEach(function (p) {
      html += '<button class="chip' + (p.platform === pgPlatform ? ' on' : '') + '" onclick="pgPickPlatform(\'' +
        p.platform.replace(/'/g, '') + '\')">' + esc(p.name || p.platform) + '</button>';
    });
    el('pgPlatforms').innerHTML = html;
    if (view === 'toplist') pgLoadTop(); else pgLoadTags();
  }).catch(function () { el('pgBody').innerHTML = '<div class="empty">读取插件失败</div>'; });
}
function pgPickPlatform(p) {
  pgPlatform = p; pgTags = []; pgTag = null; pgSheets = []; pgGroups = [];
  pgSheetPage = 1; pgSheetEnd = true;
  var chips = el('pgPlatforms').children;
  for (var i = 0; i < chips.length; i++) chips[i].className = 'chip';
  el('pgBody').innerHTML = '<div class="empty"><span class="spin"></span></div>';
  el('pgMoreBtn').style.display = 'none';
  pgRawHide();
  if (pgView === 'toplist') pgLoadTop(); else pgLoadTags();
}

/* ---- 排行榜：分组标题 + 组内横向卡片条带（同 TopListScreen） ---- */
function pgLoadTop() {
  post('/api/plugin/toplists', { platform: pgPlatform }).then(function (d) {
    if (!d || !d.ok) {
      el('pgBody').innerHTML = '<div class="empty">' + esc(d && d.error ? d.error : '排行榜加载失败') + '</div>';
      return;
    }
    pgGroups = d.groups || [];
    pgRawData = d.data;
    el('pgInfo').textContent = (d.method || '') + ' · ' + (d.elapsedMs || 0) + 'ms';
    if (!pgGroups.length) { el('pgBody').innerHTML = '<div class="empty">暂无排行榜数据</div>'; return; }
    var html = '';
    pgGroups.forEach(function (g, gi) {
      html += '<div class="gname">' + esc(g.title) + '</div><div class="strip">';
      (g.boards || []).forEach(function (b, bi) {
        html += pgCardHtml('TOPLIST', gi, bi, b);
      });
      html += '</div>';
    });
    el('pgBody').innerHTML = html;
    el('pgMoreBtn').style.display = 'none';
  }).catch(function () { el('pgBody').innerHTML = '<div class="empty">排行榜加载失败</div>'; });
}

/* 歌单/榜单卡片：整块可点开。
   根节点必须是 <div> 而非 <button>——button 内不能放 <div>，会被提前闭合导致结构塌掉（见 AGENTS.md）。 */
function pgCardHtml(kind, gi, bi, it, sub) {
  return '<div class="mcard" onclick="pgOpenSheet(\'' + kind + '\',' + gi + ',' + bi + ')">' +
      artHtml(it.artwork) +
      '<div class="t">' + esc(it.title) + '</div>' +
      '<div class="s">' + esc(sub != null ? sub : pgPlatform) + '</div>' +
  '</div>';
}
/* 整张歌单/榜单批量收藏（pgFavSheet/pgFavDetail/详情头「♡ 收藏」）已于 2026-09-29
   按用户要求移除：一次灌几百首进收藏夹的处理方式不合适，收藏回归原方式
   （单曲 ♡ 与搜索结果批量收藏）。服务端 /api/plugin/collect 同步移除。 */
function pgFavSong(i) {
  var m = pgSongs[i];
  if (!m) return;
  favCtx = { mode: 'song', idx: i, platform: pgDetail.platform, item: m };
  el('favModalTitle').textContent = '收藏歌曲「' + (m.title || '') + '」';
  loadFavAlbums();
  el('favModal').className = 'show';
}

/* ---- 推荐歌单：分类 chip + 自适应网格（同 RecommendScreen） ---- */
function pgLoadTags() {
  el('pgTags').style.display = '';
  post('/api/plugin/tags', { platform: pgPlatform }).then(function (d) {
    pgTags = (d && d.tags) || [];
    pgTag = pgTags[0] || { id: '', title: '默认' };
    pgRawData = d && d.data;
    var html = '';
    pgTags.forEach(function (t, i) {
      html += '<button class="chip' + (i === 0 ? ' on' : '') + '" onclick="pgPickTag(' + i + ')">' + esc(t.title) + '</button>';
    });
    el('pgTags').innerHTML = html;
    pgSheetPage = 1; pgSheets = []; pgSheetEnd = true;
    pgLoadSheets();
  }).catch(function () { el('pgBody').innerHTML = '<div class="empty">读取分类失败</div>'; });
}
function pgPickTag(i) {
  pgTag = pgTags[i] || { id: '', title: '默认' };
  var chips = el('pgTags').children;
  for (var k = 0; k < chips.length; k++) chips[k].className = 'chip';
  chips[i].className = 'chip on';
  pgSheetPage = 1; pgSheets = []; pgSheetEnd = true;
  el('pgBody').innerHTML = '<div class="empty"><span class="spin"></span></div>';
  el('pgMoreBtn').style.display = 'none';
  pgLoadSheets();
}
function pgLoadSheets() {
  /* 整页重绘：pgSheets 是累积数组，每页回来都按它重建整个网格。
     这里以前写的是 pgSheetPage > 1 ? pgBodyAppend() : el('pgBody')，但 pgBodyAppend 从来没定义过，
     一进函数就抛 ReferenceError，于是「加载更多」永远无效（用户报的就是这个）。 */
  if (pgSheetPage === 1) el('pgBody').innerHTML = '<div class="empty"><span class="spin"></span></div>';
  post('/api/plugin/sheets', { platform: pgPlatform, tag: pgTag, page: pgSheetPage }).then(function (d) {
    if (!d || !d.ok) {
      if (pgSheetPage === 1) el('pgBody').innerHTML = '<div class="empty">' + esc(d && d.error ? d.error : '加载失败') + '</div>';
      return;
    }
    pgSheetEnd = !!d.isEnd;
    pgRawData = d.data;
    var got = d.sheets || [];
    pgSheets = pgSheets.concat(got);
    if (!pgSheets.length) {
      if (pgSheetPage === 1) el('pgBody').innerHTML = '<div class="empty">该分类下暂无歌单</div>';
      return;
    }
    el('pgInfo').textContent = (pgTag ? pgTag.title + ' · ' : '') + (d.method || '') + ' · ' + (d.elapsedMs || 0) + 'ms';
    el('pgBody').innerHTML = '<div class="mgrid">' + pgSheets.map(function (s, i) {
      return pgCardHtml('SHEET', 0, i, s, s.description || pgPlatform);
    }).join('') + '</div>';
    el('pgMoreBtn').style.display = pgSheetEnd ? 'none' : '';
  }).catch(function () {
    if (pgSheetPage === 1) el('pgBody').innerHTML = '<div class="empty">歌单加载失败</div>';
  });
}
function pgMore() {
  if (pgDetail) { pgSongPage++; pgLoadSongs(); } else { pgSheetPage++; pgLoadSheets(); }
}

/* ---- 详情：歌单 / 榜单歌曲列表，点歌即播（同 SheetScreen） ---- */
function pgOpenSheet(kind, gi, bi) {
  var src = (kind === 'TOPLIST' ? pgGroups[gi] : null);
  var item = (src ? (src.boards || [])[bi] : pgSheets[bi]);
  if (!item) return;
  pgDetail = { kind: kind, item: item, platform: item.platform || pgPlatform };
  pgSongPage = 1; pgSongs = []; pgSongEnd = true;
  el('pgBrowse').style.display = 'none';
  el('pgDetailCard').style.display = '';
  var fsb = el('pgFavSheetBtn'); if (fsb) { fsb.disabled = false; fsb.textContent = '♡ 收藏'; }
  el('pgDetailTitle').textContent = item.title || '详情';
  el('pgDetailDesc').textContent = item.description || '';
  el('pgDetailArt').style.display = item.artwork ? '' : 'none';
  if (item.artwork) el('pgDetailArt').src = imgSrc(item.artwork);
  el('pgSongs').innerHTML = '<div class="empty"><span class="spin"></span></div>';
  el('pgSongMore').style.display = 'none';
  pgLoadSongs();
}
function pgBack() { pgDetail = null; el('pgDetailCard').style.display = 'none'; el('pgBrowse').style.display = ''; }
function pgLoadSongs() {
  if (pgSongPage > 1) el('pgSongs').innerHTML += '<div class="empty"><span class="spin"></span></div>';
  post('/api/plugin/detail', {
    platform: pgDetail.platform, kind: pgDetail.kind, item: pgDetail.item.raw || pgDetail.item, page: pgSongPage
  }).then(function (d) {
    if (!d || !d.ok) {
      el('pgSongs').innerHTML = '<div class="empty">' + esc(d && d.error ? d.error : '加载失败') + '</div>';
      return;
    }
    pgSongEnd = !!d.isEnd;
    var head = d.header || {};
    el('pgDetailTitle').textContent = head.title || el('pgDetailTitle').textContent;
    el('pgDetailDesc').textContent = head.description || el('pgDetailDesc').textContent;
    if (head.artwork) { el('pgDetailArt').style.display = ''; el('pgDetailArt').src = imgSrc(head.artwork); }
    pgSongs = pgSongs.concat(d.music || []);
    el('pgDetailCount').textContent = '共 ' + pgSongs.length + ' 首' + (d.method ? ' · ' + d.method : '');
    el('pgSongs').innerHTML = pgSongs.map(function (m, i) {
      return '<div class="songrow" onclick="pgPlay(' + i + ')"><span class="no">' + (i + 1) + '</span>' +
        rowArt(m.artwork) +
        '<span class="g"><div class="t">' + esc(m.title) + '</div><div class="s">' +
        esc([m.artist, m.album].filter(Boolean).join(' · ')) + '</div></span>' +
        '<span class="rowbtns">' +
          '<button class="rowbtn" title="播放这首" onclick="event.stopPropagation();pgPlay(' + i + ')">▶</button>' +
          '<button class="rowbtn" id="pgfav' + i + '" title="收藏这首" onclick="event.stopPropagation();pgFavSong(' + i + ')">♡</button>' +
        '</span></div>';
    }).join('');
    el('pgSongMore').style.display = pgSongEnd ? 'none' : '';
  }).catch(function () { el('pgSongs').innerHTML = '<div class="empty">歌曲加载失败</div>'; });
}
function pgPlay(i) {
  var m = pgSongs[i];
  if (!m) return;
  post('/api/play/queue', {
    plugin: pgDetail.platform,
    items: pgSongs.map(function (x) { return x.raw || x; }),
    index: i,
    source: pgDetail.platform + ' · ' + (el('pgDetailTitle').textContent || '歌单')
  }).then(function (d) { toast(d.message || '已在电视端开始播放'); }).catch(function () { toast('播放失败'); });
}
function pgPlayAll() { pgPlay(0); }
/* 整张歌单/榜单收藏：选收藏夹后由服务端翻页取全量裸条目批量入库（/api/plugin/collect） */
function pgFavSheet() {
  if (!pgDetail) return;
  favCtx = { mode: 'sheet', kind: pgDetail.kind, platform: pgDetail.platform, item: pgDetail.item.raw || pgDetail.item };
  el('favModalTitle').textContent = '收藏整张…（' + (el('pgDetailTitle').textContent || '歌单') + '）';
  /* 新建专辑框预填歌单名（仅填入，不自动创建，用户点「新建」才建） */
  var newInp = el('favAlbumNew');
  if (newInp && !newInp.value.trim()) newInp.value = el('pgDetailTitle').textContent || '';
  loadFavAlbums();
  el('favModal').className = 'show';
}

function pgRawHide() {
  el('pgRawBox').style.display = 'none';
  el('pgRaw').textContent = '';
}
function pgRaw() {
  var b = el('pgRawBox');
  if (b.style.display === 'block') { b.style.display = 'none'; return; }
  el('pgRaw').textContent = JSON.stringify(pgRawData, null, 2);
  b.style.display = 'block';
}

/* ---------------- 状态与轮询 ---------------- */
var statusFetching = false;
function loadStatus() {
  if (statusFetching) return;
  statusFetching = true;
  api('/api/status').then(function (d) {
    statusFetching = false;
    if (d.status === 'ok') el('statusLine').textContent = '电视 ' + d.host + ':' + d.port + ' · v' + d.version + ' · 在线';
  }).catch(function () { statusFetching = false; el('statusLine').textContent = '无法连接电视端'; });
}

loadStatus();
loadThemes();
loadLyric();
loadPlayer();
loadFavLists();
loadSubs();
loadSearchCfg();

/* 页面切到后台时暂停连接：手机浏览器后台节流定时器不可靠，
   继续收发会在网络差时堆积请求把页面拖死；回到前台立即刷新一次并恢复。 */

/* 状态栏轮询（/api/status）保持原样 */
var statusTimer = null;
function startStatusPoll() {
  if (statusTimer) return;
  statusTimer = setInterval(loadStatus, 15000);
}
function stopStatusPoll() {
  if (!statusTimer) return;
  clearInterval(statusTimer);
  statusTimer = null;
}

/* 播放器状态：优先 SSE（/api/events）推送，失败自动降级回 2s 轮询 */
var evtSource = null;      /* 当前 EventSource，null 表示未在 SSE 模式 */
var playerPollTimer = null;/* 降级轮询定时器 */
var sseRetryTimer = null;  /* 30 秒后重试 SSE 的定时器 */
function startPlayerPoll() {
  if (playerPollTimer) return;
  playerPollTimer = setInterval(loadPlayer, 2000);
}
function stopPlayerPoll() {
  if (!playerPollTimer) return;
  clearInterval(playerPollTimer);
  playerPollTimer = null;
}
function openPlayerEvents() {
  if (evtSource || sseRetryTimer) return;
  if (!window.EventSource) { startPlayerPoll(); return; } /* 老内核浏览器直接走轮询 */
  var es;
  try {
    es = new EventSource('/api/events');
  } catch (e) { startPlayerPoll(); return; }
  evtSource = es;
  es.onmessage = function (ev) {
    /* 收到推送即视为 SSE 正常：停掉降级轮询，走与轮询相同的渲染路径 */
    stopPlayerPoll();
    var d;
    try { d = JSON.parse(ev.data); } catch (e) { return; }
    handlePlayerData(d);
  };
  es.onerror = function () {
    /* SSE 断开：降级回 2s 轮询，30 秒后重试 SSE，成功（收到推送）后自动停轮询 */
    es.close();
    if (evtSource === es) evtSource = null;
    startPlayerPoll();
    if (!sseRetryTimer) {
      sseRetryTimer = setTimeout(function () {
        sseRetryTimer = null;
        openPlayerEvents();
      }, 30000);
    }
  };
}
function closePlayerEvents() {
  if (sseRetryTimer) { clearTimeout(sseRetryTimer); sseRetryTimer = null; }
  if (evtSource) { evtSource.close(); evtSource = null; }
  stopPlayerPoll();
}

startStatusPoll();
openPlayerEvents();
document.addEventListener('visibilitychange', function () {
  if (document.hidden) {
    closePlayerEvents();
    stopStatusPoll();
  } else {
    loadStatus();
    loadPlayer();
    startStatusPoll();
    openPlayerEvents();
  }
});
</script>
</body>
</html>
"""
