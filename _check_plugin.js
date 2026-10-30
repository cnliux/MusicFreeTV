const https = require('https');
var urls = [
  ['酷我音乐', 'kuwo-source.plugin.v1.9.24.js'],
  ['酷狗音乐', 'kugou-source.plugin.v1.9.24.js'],
];
urls.forEach(function(p){
  var url='https://hebijunge.github.io/musicfree-plugins/plugins/'+p[1];
  https.get(url,function(r){
    var d='';r.on('data',function(c){d+=c});
    r.on('end',function(){
      console.log('\n=== '+p[0]+' ('+d.length+'b) ===');
      console.log('first 300:', d.slice(0, 300));
      console.log('\nlast 300:', d.slice(-300));
      // Find all string literals with potential platform names
      var re = /['"]([^'"]{2,20})['"]/g;
      var candidates = [];
      var m;
      while((m=re.exec(d))!==null){
        var s=m[1];
        if(['kuwo','Kuwo','KUWO','酷我','qq','qmusic'].indexOf(s)>=0){
          candidates.push({s:s, i:m.index});
          console.log('CANDIDATE:', s, '@'+m.index, ':', d.slice(Math.max(0,m.index-60), m.index+s.length+60));
        }
      }
      if(candidates.length===0) console.log('No platform candidate found');
    })
  }).on('error',function(e){console.log(p[0],e.message)});
});
