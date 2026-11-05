(function(){
  if(window.stvToolsReady || !window.LocalReader) return;
  window.stvToolsReady=true;
  const root=document.getElementById('stv-content')||document.getElementById('pane');
  if(!root) return;
  const $=id=>document.getElementById(id);
  const ui=(id,html)=>{const el=document.createElement('aside');el.id=id;el.className='stv-ui';el.innerHTML=html;document.body.appendChild(el);return el;};
  let cfg={}, appearance={}, scope='global', source='', original=false, sentences=[], current=0, playing=false, speed=1, voice='';
  const call=(fn,...args)=>{try{return LocalReader[fn](...args);}catch(e){toast('Không thể lưu/thực hiện. Vui lòng thử lại.');return null;}};
  const parse=x=>{try{return JSON.parse(x);}catch(e){return {};}};
  function toast(text){let el=$('stv-toast');if(!el){el=document.createElement('div');el.id='stv-toast';document.body.appendChild(el);}el.textContent=text;el.hidden=false;setTimeout(()=>el.hidden=true,2200);}
  const show=(id,value)=>$(id).classList.toggle('stv-hide',!value);
  const toggle=id=>show(id,$(id).classList.contains('stv-hide'));
  ui('stv-tools',`<div class="stv-head"><b>Giao diện đọc</b><button data-hide="stv-tools" aria-label="Đóng giao diện đọc">✖</button></div><div class="stv-colors">Màu nền: <input aria-label="Màu nền tùy chọn" id="stv-bg" type="color"></div><div id="stv-bg-swatches" class="stv-swatches"></div>
    <div class="stv-colors">Màu chữ: <input aria-label="Màu chữ tùy chọn" id="stv-fg" type="color"> Bóng: <input id="stv-shadow" type="checkbox" aria-label="Bóng chữ"></div><div id="stv-fg-swatches" class="stv-swatches"></div>
    <div class="stv-control"><button id="stv-smaller" aria-label="Giảm cỡ chữ">−</button><span>A</span><input id="stv-size" type="number" min="12" max="40" aria-label="Cỡ chữ"><button id="stv-larger" aria-label="Tăng cỡ chữ">+</button><button id="stv-line-less">−</button><input id="stv-line" type="number" min="1" max="3" step="0.1" aria-label="Giãn dòng"><button id="stv-line-more">+</button></div>
    <div class="stv-control"><select id="stv-font" aria-label="Phông chữ"><option>Helvetica</option><option>Arial</option><option>Tahoma</option><option>Times New Roman</option><option value="serif">Serif</option><option value="sans-serif">Sans</option><option value="monospace">Mono</option></select></div>
    <div class="stv-align"><button data-align="left" title="Căn trái">☰</button><button data-align="center" title="Căn giữa">≡</button><button data-align="right" title="Căn phải">☰</button><button data-align="justify" title="Căn đều">▤</button></div>
    <div class="stv-foot"><button id="stv-listen">▶ Nghe sách</button><button id="stv-names">☑ Name</button></div><div class="stv-foot"><button id="stv-language">中文 → Việt</button></div>`).classList.add('stv-hide');
  window.stvToggleTools=()=>toggle('stv-tools');
  const palette=['#ffffff','#dedede','#1b1b1b','#505050','#6a3678','#704331','#401e22','#b5b5b5','#898989','#71aeb5','#68a6bd','#95bb5d','#c27baf','#4f6553','#d8ccb4','#252525'];
  for(const [id,key] of [['stv-bg-swatches','background'],['stv-fg-swatches','foreground']]) for(const color of palette){const b=document.createElement('button');b.textContent='A';b.style.background=color;b.style.color=(key==='background'?'#ddd':color);if(key==='foreground')b.style.background='#242424';b.title=color;b.onclick=()=>{appearance[key]=color;applyAppearance(true);};$(id).appendChild(b);}
  ui('stv-name',`<div class="stv-head"><button id="stv-save">Lưu lại</button><button id="stv-run">Chạy</button><b></b><button data-hide="stv-name">Ẩn</button></div><div class="stv-tabs"><button id="stv-global">Global</button><button id="stv-story">Riêng truyện</button></div><textarea id="stv-rules" aria-label="Bảng dịch Name" spellcheck="false" placeholder="$[张三]=Trương Tam"></textarea><div id="stv-name-status"></div>`).classList.add('stv-hide');
  ui('stv-word',`<div class="stv-head"><button data-close-word aria-label="Đóng không lưu">✖</button><button id="stv-expand-left">◀ Mở rộng</button><button id="stv-expand-right">Mở rộng ▶</button><button data-close-word aria-label="Đóng không lưu">✖</button></div>
    <div class="stv-word-row"><label for="stv-global-value">Dịch global</label><input id="stv-global-value" aria-label="Dịch global"><button data-save-scope="global" aria-label="Lưu dịch global">☑</button></div>
    <div class="stv-word-row"><label for="stv-story-value">Dịch riêng</label><input id="stv-story-value" aria-label="Dịch riêng"><button data-save-scope="story" aria-label="Lưu dịch riêng">☑</button></div>
    <div class="stv-word-row"><label for="stv-source">Cụm gốc</label><input id="stv-source" aria-label="Cụm gốc đang chọn" readonly></div>
    <div class="stv-word-row"><label for="stv-cap">Hoa đầu</label><input id="stv-cap" aria-label="Bản dịch viết hoa chữ cái đầu"><button id="stv-save-cap" aria-label="Lưu bản dịch viết hoa">☑</button></div>
    <div class="stv-foot"><button id="stv-manage">☷ Bảng dịch</button><select id="stv-word-scope" aria-label="Phạm vi lưu bản viết hoa"><option value="global">Global</option><option value="story">Riêng truyện</option></select></div>`).classList.add('stv-hide');
  ui('stv-speech',`<div class="stv-head"><span>◖</span><b>Text-to-Speech</b><button id="stv-min-speech">−</button><button id="stv-close-speech">×</button></div><div id="stv-speech-body"><div class="stv-speech-nav"><span id="stv-sentence-count">☷ Câu (0)</span><label>Đi đến</label><input id="stv-jump" type="number" min="1" aria-label="Đi đến câu"><button id="stv-go">➜</button></div><ol id="stv-sentences"></ol><div id="stv-speech-status">Đã dừng</div><div class="stv-speech-footer"><button id="stv-stop" aria-label="Dừng nghe">■</button><button id="stv-play">▶ Phát</button><button id="stv-voice-toggle" aria-label="Cài đặt giọng đọc">⚙</button></div><div id="stv-voice" class="stv-hide"><select id="stv-speed" aria-label="Tốc độ đọc"><option>0.75</option><option selected>1</option><option>1.25</option><option>1.5</option><option>2</option></select><select id="stv-voices" aria-label="Giọng đọc"><option value="">Giọng mặc định</option></select></div></div>`).classList.add('stv-hide');
  document.querySelectorAll('[data-hide]').forEach(b=>b.onclick=()=>show(b.dataset.hide,false));
  function rules(){const data=parse(call('names'));$('stv-rules').value=scope==='global'?data.global||'':data.story||'';$('stv-global').classList.toggle('active',scope==='global');$('stv-story').classList.toggle('active',scope==='story');$('stv-word-scope').value=scope;$('stv-name-status').textContent=scope==='global'?'$[cụm từ]=bản dịch · Global':cfg.title||'Truyện đang đọc';}
  function saveRules(){const result=parse(call('saveRules',scope,$('stv-rules').value));if(!result.ok){toast(result.error||'Lưu Name thất bại');return;}window.stvRefresh();toast('Đã lưu và chạy');}
  $('stv-global').onclick=()=>{scope='global';rules();};$('stv-story').onclick=()=>{scope='story';rules();};$('stv-save').onclick=saveRules;$('stv-run').onclick=saveRules;
  $('stv-word-scope').onchange=e=>{scope=e.target.value;updateCapital();};$('stv-manage').onclick=()=>{rules();closeWord();show('stv-name',true);};
  $('stv-names').onclick=()=>{rules();toggle('stv-name');};
  const capital=value=>value.replace(/\p{L}/u,letter=>letter.toLocaleUpperCase('vi'));
  function updateCapital(){const value=$('stv-'+scope+'-value').value,d=/\p{Script=Han}/u.test(value)&&value.length<=200?parse(call('describe',value)):{};$('stv-cap').value=capital(d[scope]||value);$('stv-word-scope').value=scope;}
  function describe(value){const d=parse(call('describe',value));source=value;$('stv-source').value=value;$('stv-global-value').value=d.global||d.smooth||value;$('stv-story-value').value=d.story||d.smooth||value;updateCapital();}
  for(const name of ['global','story']){const field=$('stv-'+name+'-value');field.onfocus=()=>{scope=name;updateCapital();};field.oninput=()=>{scope=name;updateCapital();};}
  function tick(name,capitalized=false){const src=source.trim(),target=$(capitalized?'stv-cap':'stv-'+name+'-value').value.trim();if(!src||!target){toast('Nhập cụm từ và bản dịch');return;}const result=parse(call(capitalized?'saveCapitalName':'saveName',name,src,capitalized?capital(target):target));if(!result.ok){toast(result.error||'Lưu bản dịch thất bại');return;}scope=name;closeWord();window.stvRefresh();rules();toast('Đã lưu và chạy: $['+src+']='+target);}
  document.querySelectorAll('[data-save-scope]').forEach(b=>b.onclick=()=>tick(b.dataset.saveScope));$('stv-save-cap').onclick=()=>tick(scope,true);
  let firstWord=null,lastWord=null,selectionBlock=null;
  function clearSelection(){root.querySelectorAll('.stv-selected').forEach(word=>word.classList.remove('stv-selected'));}
  function closeWord(){clearSelection();show('stv-word',false);firstWord=lastWord=selectionBlock=null;source='';}
  document.querySelectorAll('[data-close-word]').forEach(b=>b.onclick=closeWord);
  function selectedSource(first,last){const start=Math.min(Number(first.dataset.start),Number(last.dataset.start)),end=Math.max(Number(first.dataset.end),Number(last.dataset.end));return selectionBlock.dataset.original.slice(start,end);}
  function highlightSelection(){const words=[...selectionBlock.querySelectorAll('.stv-word')],first=words.indexOf(firstWord),last=words.indexOf(lastWord);clearSelection();words.slice(first,last+1).forEach(word=>word.classList.add('stv-selected'));describe(selectedSource(firstWord,lastWord));$('stv-expand-left').disabled=first===0;$('stv-expand-right').disabled=last===words.length-1;}
  function expand(direction){if(!selectionBlock)return;const words=[...selectionBlock.querySelectorAll('.stv-word')],index=words.indexOf(direction<0?firstWord:lastWord)+direction,next=words[index];if(!next)return;const first=direction<0?next:firstWord,last=direction>0?next:lastWord;if(selectedSource(first,last).length>200){toast('Chọn tối đa 200 ký tự');return;}firstWord=first;lastWord=last;highlightSelection();}
  $('stv-expand-left').onclick=()=>expand(-1);$('stv-expand-right').onclick=()=>expand(1);
  function positionWord(){if(!firstWord)return;const box=$('stv-word'),anchor=firstWord.getBoundingClientRect(),viewport=window.visualViewport,left=viewport?viewport.offsetLeft:0,top=viewport?viewport.offsetTop:0,width=viewport?viewport.width:innerWidth,height=viewport?viewport.height:innerHeight,x=anchor.left+anchor.width/2,y=anchor.bottom+8+box.offsetHeight<=top+height?anchor.bottom+8:anchor.top-box.offsetHeight-8;box.style.left=Math.max(left+4,Math.min(left+width-box.offsetWidth-4,x-box.offsetWidth/2))+'px';box.style.top=Math.max(top+4,Math.min(top+height-box.offsetHeight-4,y))+'px';}
  root.addEventListener('click',event=>{const word=event.target.closest('.stv-word');if(!word)return;event.preventDefault();event.stopPropagation();selectionBlock=word.closest('.stv-block');if(!selectionBlock)return;firstWord=lastWord=word;highlightSelection();show('stv-word',true);positionWord();},true);
  document.addEventListener('keydown',event=>{if(event.key==='Escape')closeWord();});
  if(window.visualViewport)window.visualViewport.addEventListener('resize',()=>{if(!$('stv-word').classList.contains('stv-hide'))positionWord();});
  function applyAppearance(save){const a=appearance;for(const [key,value] of Object.entries({'--stv-bg':a.background||'#1b1b1b','--stv-fg':a.foreground||'#ddd','--stv-font':a.font||'Helvetica','--stv-size':(a.size||19)+'px','--stv-line':a.line||1.7,'--stv-align':a.align||'left','--stv-shadow':a.shadow?'1px 1px 2px '+(a.shadowColor||'#000000'):'none'}))document.documentElement.style.setProperty(key,value);document.body.style.background=a.background||'#1b1b1b';$('stv-bg').value=a.background||'#1b1b1b';$('stv-fg').value=a.foreground||'#dddddd';$('stv-size').value=a.size||19;$('stv-line').value=Number(a.line||1.7).toFixed(1);$('stv-font').value=a.font||'Helvetica';$('stv-shadow').checked=!!a.shadow;if(save)call('saveAppearance',JSON.stringify(a));}
  $('stv-bg').onchange=e=>{appearance.background=e.target.value;applyAppearance(true);};$('stv-fg').onchange=e=>{appearance.foreground=e.target.value;applyAppearance(true);};$('stv-shadow').onchange=e=>{appearance.shadow=e.target.checked;applyAppearance(true);};$('stv-font').onchange=e=>{appearance.font=e.target.value;applyAppearance(true);};
  function size(delta){appearance.size=Math.max(12,Math.min(40,Number($('stv-size').value)+delta));applyAppearance(true);}function line(delta){appearance.line=Math.max(1,Math.min(3,Number($('stv-line').value)+delta));applyAppearance(true);}
  $('stv-smaller').onclick=()=>size(-1);$('stv-larger').onclick=()=>size(1);$('stv-size').onchange=()=>size(0);$('stv-line-less').onclick=()=>line(-.1);$('stv-line-more').onclick=()=>line(.1);$('stv-line').onchange=()=>line(0);document.querySelectorAll('[data-align]').forEach(b=>b.onclick=()=>{appearance.align=b.dataset.align;applyAppearance(true);});
  $('stv-language').onclick=()=>{original=!original;window.stvRefresh();};
  window.stvRefresh=function(){const fraction=scrollY/Math.max(1,document.documentElement.scrollHeight-innerHeight);if(firstWord&&!firstWord.isConnected)closeWord();root.querySelectorAll('.stv-block').forEach(block=>{const value=call('render',block.dataset.original,original);if(value!==null)block.innerHTML=value;});if(firstWord&&!firstWord.isConnected)closeWord();$('stv-language').textContent=original?'中文 · Bản gốc':'中文 → Việt';requestAnimationFrame(()=>scrollTo(0,fraction*Math.max(1,document.documentElement.scrollHeight-innerHeight)));if(!$('stv-speech').classList.contains('stv-hide'))prepareSpeech();};
  function prepareSpeech(){const wasPlaying=playing;stop();sentences=(root.innerText||'').split(/(?<=[.!?。！？])\s+|\n+/).map(t=>t.trim()).filter(Boolean).flatMap(t=>{const a=[];for(let i=0;i<t.length;i+=2000)a.push(t.slice(i,i+2000));return a;});current=Math.min(current,Math.max(0,sentences.length-1));const list=$('stv-sentences');list.replaceChildren();sentences.forEach((text,index)=>{const li=document.createElement('li'),num=document.createElement('small'),label=document.createElement('span');num.textContent=index+1;label.textContent=text;li.append(num,label);li.onclick=()=>{current=index;highlight();if(playing)speak();};list.appendChild(li);});$('stv-sentence-count').textContent='☷ Câu ('+sentences.length+')';highlight();if(wasPlaying){playing=true;speak();}}
  function highlight(){
    const list=$('stv-sentences');
    [...list.children].forEach((li,i)=>li.classList.toggle('active',i===current));
    if(list.children[current])list.children[current].scrollIntoView({block:'nearest'});
    $('stv-speech-status').textContent='Câu '+(sentences.length?current+1:0)+'/'+sentences.length+' · '+(playing?'Đang đọc':'Đã dừng');
    const text=sentences[current]; if(!text)return;
    const walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),nodes=[];
    let node,full='';
    while((node=walker.nextNode())){nodes.push([node,full.length]);full+=node.textContent;}
    // Locate the actual occurrence, not the first identical sentence in the chapter.
    let cursor=0,start=-1;
    for(let i=0;i<=current;i++){
      const found=full.indexOf(sentences[i],cursor);
      if(found<0)continue;
      if(i===current)start=found;
      cursor=found+sentences[i].length;
    }
    if(start<0)return;
    const end=start+text.length,range=document.createRange();let began=false;
    for(const [n,offset]of nodes){
      if(!began&&start>=offset&&start<offset+n.length){range.setStart(n,start-offset);began=true;}
      if(began&&end<=offset+n.length){range.setEnd(n,end-offset);break;}
    }
    if(!began)return;
    if(window.CSS&&CSS.highlights&&window.Highlight)CSS.highlights.set('stv-speaking',new Highlight(range));
    const rect=range.getBoundingClientRect();
    if(rect.height)scrollBy({top:rect.top-innerHeight*.4,behavior:'smooth'});
  }
  function speak(){if(!sentences.length){toast('Không có nội dung để đọc');return;}playing=true;$('stv-play').textContent='Ⅱ Tạm dừng';highlight();call('speak',sentences[current],current,speed,voice,original?'zh-CN':'vi-VN');}
  function stop(){playing=false;call('stop');$('stv-play').textContent='▶ Phát';$('stv-speech-status').textContent='Câu '+(sentences.length?current+1:0)+'/'+sentences.length+' · Đã dừng';}
  window.stvSpeechDone=function(index){if(!playing||index!==current)return;if(current+1<sentences.length){current++;speak();}else stop();};
  window.stvSpeechError=function(message){stop();toast(message||'Không có giọng đọc phù hợp');};
  window.stvVoiceList=function(voices){const select=$('stv-voices');select.replaceChildren(new Option('Giọng mặc định',''));voices.forEach(v=>select.add(new Option(v,v)));};
  window.stvToggleSpeech=function(){show('stv-speech',true);if(!sentences.length)prepareSpeech();if(playing)stop();else speak();};
  $('stv-listen').onclick=()=>{show('stv-speech',true);if(!sentences.length)prepareSpeech();};$('stv-play').onclick=()=>playing?stop():speak();$('stv-stop').onclick=stop;$('stv-close-speech').onclick=()=>{stop();show('stv-speech',false);};$('stv-min-speech').onclick=()=>toggle('stv-speech-body');$('stv-voice-toggle').onclick=()=>toggle('stv-voice');$('stv-speed').onchange=e=>{speed=Number(e.target.value);if(playing)speak();};$('stv-voices').onchange=e=>{voice=e.target.value;if(playing)speak();};$('stv-go').onclick=()=>{current=Math.max(0,Math.min(sentences.length-1,Number($('stv-jump').value)-1));highlight();if(playing)speak();};
  for(const id of ['stv-name','stv-word','stv-speech']){const panel=$(id),head=panel.querySelector('.stv-head');let drag=null;head.addEventListener('pointerdown',e=>{if(e.target.closest('button,input,select'))return;const r=panel.getBoundingClientRect();drag=[e.clientX-r.left,e.clientY-r.top];head.setPointerCapture(e.pointerId);});head.addEventListener('pointermove',e=>{if(!drag)return;panel.style.bottom='auto';panel.style.left=Math.max(0,Math.min(innerWidth-panel.offsetWidth,e.clientX-drag[0]))+'px';panel.style.top=Math.max(0,Math.min(innerHeight-30,e.clientY-drag[1]))+'px';});head.addEventListener('pointerup',()=>drag=null);}
  window.stvConfigure=function(value){const initial=!cfg.title;cfg=value;appearance=value.appearance||{};if(initial)original=value.original||false;applyAppearance(false);rules();$('stv-language').textContent=original?'中文 · Bản gốc':'中文 → Việt';};
  window.stvConfigure(parse(call('config')));
})();
