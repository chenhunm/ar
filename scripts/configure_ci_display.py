from pathlib import Path
import sys
p=Path(sys.argv[1]);assert p.is_file()
values={'hw.lcd.width':'480','hw.lcd.height':'800','hw.lcd.density':'160'}
lines=[x for x in p.read_text().splitlines() if x.partition('=')[0] not in values]
p.write_text('\n'.join(lines+[f'{k}={v}' for k,v in values.items()])+'\n')
print('CI software AVD: 480x800 pixels, 160 dpi; not Redmi K80 performance')
