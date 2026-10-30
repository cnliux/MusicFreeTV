const https = require('https');
const fs = require('fs');

// Load the plugin list
https.get('https://hebijunge.github.io/musicfree-plugins/plugins.json', r => {
  let d = '';
  r.on('data', c => d += c);
  r.on('end', () => {
    const j = JSON.parse(d);
    const plugins = j.plugins || [];
    console.log('Total plugins in subscription:', plugins.length);
    
    // Check each plugin URL
    var results = { js: [], html: [], error: [], unknown: [] };
    var checked = 0;
    
    plugins.forEach(function(p, idx) {
      const url = p.url;
      const name = p.name;
      https.get(url, r2 => {
        var body = '';
        r2.on('data', c => body += c);
        r2.on('end', () => {
          checked++;
          var cat;
          if (body.indexOf('<!DOCTYPE') >= 0 || body.indexOf('<html') >= 0 || body.indexOf('<head>') >= 0) {
            cat = 'HTML_ERROR';
          } else if (body.length < 500) {
            cat = 'TOO_SMALL';
          } else if (body.indexOf('getMediaSource') >= 0) {
            cat = 'JS_OK';
          } else {
            cat = 'UNKNOWN';
          }
          results[cat] = results[cat] || [];
          results[cat].push({ name: name, url: url, size: body.length });
          
          if (checked === plugins.length) {
            console.log('\n=== SUMMARY ===');
            Object.keys(results).forEach(function(k) {
              console.log(k + ':', results[k].length);
              results[k].slice(0, 5).forEach(function(x) {
                console.log('  -', x.name, '(' + x.size + 'b)');
              });
              if (results[k].length > 5) console.log('  ... and', results[k].length - 5, 'more');
            });
            
            // Write full list to file
            fs.writeFileSync('_plugin_audit.json', JSON.stringify(results, null, 2));
            console.log('\nFull audit written to _plugin_audit.json');
          }
        });
      }).on('error', e => {
        checked++;
        results.error = results.error || [];
        results.error.push({ name: name, url: url, err: e.message });
        if (checked === plugins.length) {
          console.log('\n=== SUMMARY ===');
          Object.keys(results).forEach(function(k) {
            if (results[k]) console.log(k + ':', results[k].length);
          });
          fs.writeFileSync('_plugin_audit.json', JSON.stringify(results, null, 2));
        }
      });
    });
  });
});
