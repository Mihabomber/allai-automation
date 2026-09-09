import subprocess, os
# Склейка + удаление водяного Dola + прыгающий водяной знак all ai
# Использование: python video_finalize.py part1.mp4 part2.mp4 logo.png out.mp4

import sys
p1, p2, logo, out = sys.argv[1:5]

# 1. concat
open('/tmp/list.txt','w').write(f"file '{os.path.abspath(p1)}'\nfile '{os.path.abspath(p2)}'\n")
subprocess.run(['ffmpeg','-y','-f','concat','-safe','0','-i','/tmp/list.txt','-c','copy','/tmp/joined.mp4'], check=True)

# 2. delogo Dola (подобрать координаты x,y,w,h под реальный знак!) + прыгающий логотип
# Прыганье: x='(W-w)*abs(sin(t*1.2))' y='(H-h)*abs(cos(t*0.9))'
# Текст all ai + картинка logo.png поверх
subprocess.run([
 'ffmpeg','-y','-i','/tmp/joined.mp4','-i',logo,
 '-filter_complex',
 "[0:v]delogo=x=10:y=H-60:w=120:h=40[v0];"
 "[v0][1:v]overlay=x='(W-w)*abs(sin(t*1.2))':y='(H-h)*abs(cos(t*0.9))',"
 "drawtext=text='all ai':fontsize=48:fontcolor=white:borderw=2:x='(w-text_w)*abs(cos(t*1.2))':y='(h-text_h)*abs(sin(t*0.9))'[v]",
 '-map','[v]','-map','0:a?','-c:a','aac', out
], check=True)
print('OK', out)
