"""Render the Word-exported PDF for page-by-page visual review."""
from pathlib import Path
import json
import pypdfium2 as pdfium
import pdfplumber
from PIL import Image,ImageDraw,ImageFont
B=Path(__file__).resolve().parent
pdf=B.parent/'final/Reconis-系统实现总结报告.pdf'
out=B.parents[2]/'artifacts/report-authoring-20261104/render-word-delivery'
out.mkdir(parents=True,exist_ok=True)
doc=pdfium.PdfDocument(str(pdf))
textdoc=pdfplumber.open(pdf)
info=[]
for n,page in enumerate(doc,1):
    page.render(scale=140/72).to_pil().save(out/f'page-{n}.png')
    tp=textdoc.pages[n-1]
    info.append({'page':n,'text':tp.extract_text(),'words':tp.extract_words(),'images':len(tp.images)})
(out/'page-text.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf8')
for start in range(1,len(doc)+1,4):
    sheet=Image.new('RGB',(1640,2370),'#d1d5db'); draw=ImageDraw.Draw(sheet)
    for i,n in enumerate(range(start,min(start+4,len(doc)+1))):
        img=Image.open(out/f'page-{n}.png'); img.thumbnail((800,1132))
        x=(i%2)*820+10;y=(i//2)*1185+35
        sheet.paste(img,(x,y));draw.text((x,y-25),f'PDF page {n}',fill='black',font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',18))
    sheet.save(out/f'contact-{start:02d}-{min(start+3,len(doc)):02d}.png')
print(str(out),len(doc),'pages')
