package com.offlinegemma.app

/** Canned tutor answers for testing demos in developer builds (see MainActivity test_demo). */
object TestDemos {
  /** A demo that tries to reach the internet; it should report "blocked" for both. */
  fun netProbe(): String = """
Network test.

```html
<div id="out">testing…</div>
<img id="im" src="https://www.google.com/favicon.ico">
<script>
var o=document.getElementById('out'),r=[];
var im=document.getElementById('im');
im.onload=function(){r.push('img: LOADED (bad)');show()};im.onerror=function(){r.push('img: blocked');show()};
fetch('https://www.google.com/').then(function(){r.push('fetch: LOADED (bad)');show()}).catch(function(){r.push('fetch: blocked');show()});
function show(){o.textContent=r.join(' / ')}
</script>
```
""".trimIndent()

  fun reply(broken: Boolean): String {
    val bug = if (broken) "drawBob(x, y);" else ""
    return """
A pendulum swings because gravity pulls the bob back toward the middle. Think of a playground swing.

```html
<div style="width:100%">
<canvas id="c" width="460" height="260" style="width:100%;border:1px solid #ccc"></canvas>
<label>Length: <span id="lv">150</span> px</label>
<input type="range" id="len" min="60" max="220" value="150">
</div>
<script>
var c=document.getElementById('c'),g=c.getContext('2d'),len=document.getElementById('len');
var a=0.6,v=0;
function step(){
  var L=+len.value; document.getElementById('lv').textContent=L;
  v+=-9.8/L*Math.sin(a)*0.5; v*=0.999; a+=v*0.5;
  g.clearRect(0,0,460,260);
  var x=230+L*Math.sin(a), y=10+L*Math.cos(a);
  g.beginPath();g.moveTo(230,10);g.lineTo(x,y);g.stroke();
  g.beginPath();g.arc(x,y,14,0,6.3);g.fillStyle='#4a7';g.fill();
  $bug
  requestAnimationFrame(step);
}
step();
</script>
```

NEXT: Why does a longer pendulum swing slower? | Does the weight of the bob matter? | <question 3>
""".trimIndent()
  }
}
