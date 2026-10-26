"""Собирает иконки и заставку для @capacitor/assets из маленьких исходников."""
from PIL import Image
bg = Image.open('assets/bg-small.png').convert('RGB').resize((1024, 1024), Image.BICUBIC)
fg = Image.open('assets/icon-foreground.png').convert('RGBA').resize((1024, 1024), Image.LANCZOS)
bg.save('assets/icon-background.png')
fg.save('assets/icon-foreground.png')
big = fg.resize((1330, 1330), Image.LANCZOS).crop((153, 153, 1177, 1177))
only = bg.convert('RGBA'); only.alpha_composite(big); only.save('assets/icon-only.png')
for name in ('splash', 'splash-dark'):
    s = Image.new('RGBA', (2732, 2732), (5, 5, 11, 255))
    ic = only.resize((560, 560), Image.LANCZOS)
    m = Image.new('L', (560, 560), 0)
    from PIL import ImageDraw
    ImageDraw.Draw(m).rounded_rectangle((0, 0, 559, 559), 130, fill=255)
    s.paste(ic, ((2732 - 560) // 2, (2732 - 560) // 2), m)
    s.convert('RGB').save(f'assets/{name}.png')
print('icons ok')
