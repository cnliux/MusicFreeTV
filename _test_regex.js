const https = require('https');
// Test detectPlatformFromUrl logic
function detectFromUrl(url, source) {
  var fileName = url.split('/').pop().split('.js')[0].split('-source')[0].split('-plugin')[0].trim();
  if (!fileName || fileName.length > 30) return null;
  // Verify: source must contain the platform name
  if (source.indexOf('"' + fileName + '"') === -1 && source.indexOf("'" + fileName + "'") === -1) return null;
  return fileName;
}

const plugins = [
  ['网易云音乐', 'netease-source.plugin.v1.9.27.js'],
  ['酷我音乐', 'kuwo-source.plugin.v1.9.24.js'],
  ['QQ音乐', 'qq-source.plugin.v1.9.26.js'],
  ['汽水音乐', 'qishui-source.plugin.v1.9.23.js'],
  ['聚合', 'agg-source.plugin.v1.9.18.js'],
];

plugins.forEach(function(p) {
  const url = 'https://hebijunge.github.io/musicfree-plugins/plugins/' + p[1];
  https.get(url, r => {
    let d = '';
    r.on('data', c => d += c);
    r.on('end', () => {
      // Check if HTML error
      if (d.indexOf('<!DOCTYPE') >= 0 || d.indexOf('<html') >= 0) {
        console.log(p[0] + ' => HTML ERROR (file not found)');
        return;
      }
      var result = detectFromUrl(url, d);
      console.log(p[0] + ' => ' + (result || 'NULL'));
    });
  }).on('error', e => console.log(p[0], 'ERROR:', e.message));
});
