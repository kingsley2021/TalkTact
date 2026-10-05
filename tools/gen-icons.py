# -*- coding: utf-8 -*-
"""从 art/ 里的两张图生成：内置背景 drawable + 自适应图标四件套。"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageOps

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
RES = os.path.join(ROOT, "app/src/main/res")

ICON_SRC = os.path.join(ART, "IMG_20261005_142341.jpg")
BG_SRC = os.path.join(ART, "074cda01ebf34e64bf15d85eed969c0c_6.jpg")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
CIRCLE_RATIO = 0.898      # 沿用旧图标：前景圆直径 = 画布的 89.8%
BG_BLUR_RATIO = 0.05      # 背景层高斯模糊半径 = 画布 × 5%
BG_DARKEN = 0.66          # 背景层压暗系数
MONO_COVERAGE = 0.18      # 单色剪影覆盖圆面积的目标比例


def load(path):
    im = Image.open(path)
    im = ImageOps.exif_transpose(im)
    return im.convert("RGB")


def circle_mask(size, ss=4):
    """抗锯齿的圆形遮罩。"""
    big = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def cover(im, w, h):
    """等价于 ContentScale.Crop：铺满 w×h 后居中裁切。"""
    s = max(w / im.width, h / im.height)
    nw, nh = max(1, round(im.width * s)), max(1, round(im.height * s))
    r = im.resize((nw, nh), Image.LANCZOS)
    return r.crop(((nw - w) // 2, (nh - h) // 2, (nw - w) // 2 + w, (nh - h) // 2 + h))


def mono_from(im, size, mask, coverage=MONO_COVERAGE):
    """把「很暗的那些像素」（头发、深色衣服）抠成白色剪影。"""
    small = im.resize((size, size), Image.LANCZOS)
    lum = small.convert("L")
    px = list(lum.getdata())
    m = list(mask.getdata())
    inside = sorted(p for p, a in zip(px, m) if a > 128)
    if not inside:
        return Image.new("RGBA", (size, size), (255, 255, 255, 0))
    thr = inside[max(0, min(len(inside) - 1, int(len(inside) * coverage)))]
    soft = max(12.0, thr * 0.55)
    alpha = Image.new("L", (size, size), 0)
    alpha.putdata([int(max(0.0, min(1.0, (thr - p) / soft)) * (a / 255.0) * 255)
                   for p, a in zip(px, m)])
    alpha = alpha.filter(ImageFilter.GaussianBlur(size * 0.004))
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def build(icon):
    report = {}
    for name, d in DENSITIES.items():
        canvas = round(108 * d)
        dia = round(canvas * CIRCLE_RATIO)
        mask = circle_mask(dia)

        # 前景：图缩到圆直径 → 圆形裁剪 → 居中
        art = icon.resize((dia, dia), Image.LANCZOS).convert("RGBA")
        art.putalpha(mask)
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(art, ((canvas - dia) // 2, (canvas - dia) // 2))

        # 背景：整张铺满 + 高斯模糊 + 压暗（不留透明边，启动器怎么裁都不露底）
        bg = cover(icon, canvas, canvas).filter(ImageFilter.GaussianBlur(canvas * BG_BLUR_RATIO))
        bg = Image.eval(bg, lambda v: int(v * BG_DARKEN)).convert("RGB")

        # 单色层：白色剪影
        mono = mono_from(icon, canvas, mask)

        # 旧版（API < 26 用不到，minSdk 31；保留是为了图标在任何地方都不缺）
        legacy_size = round(48 * d)
        legacy = cover(icon, legacy_size, legacy_size).filter(
            ImageFilter.GaussianBlur(legacy_size * BG_BLUR_RATIO))
        legacy = Image.eval(legacy, lambda v: int(v * BG_DARKEN)).convert("RGBA")
        art2 = icon.resize((legacy_size, legacy_size), Image.LANCZOS).convert("RGBA")
        art2.putalpha(circle_mask(legacy_size))
        legacy.alpha_composite(art2)
        legacy = legacy.convert("RGB")

        outdir = os.path.join(RES, "mipmap-" + name)
        bg.save(os.path.join(outdir, "ic_launcher_background.png"), "PNG", optimize=True)
        fg.save(os.path.join(outdir, "ic_launcher_foreground.png"), "PNG", optimize=True)
        mono.save(os.path.join(outdir, "ic_launcher_monochrome.png"), "PNG", optimize=True)
        legacy.save(os.path.join(outdir, "ic_launcher.png"), "PNG", optimize=True)
        report[name] = (canvas, dia, sum(1 for v in mono.getchannel("A").getdata() if v > 128))
    return report


def ascii_preview(im, w=56):
    im = im.convert("L").resize((w, max(1, int(w * im.height / im.width))), Image.LANCZOS)
    chars = " .:-=+*#%@"
    for y in range(im.height):
        print("".join(chars[min(9, im.getpixel((x, y)) * 10 // 256)] for x in range(im.width)))


if __name__ == "__main__":
    icon = load(ICON_SRC)
    print("icon src", icon.size)

    # 1) 内置背景 drawable：缩到 1080 宽，质量 88
    bg = load(BG_SRC)
    h = round(bg.height * 1080 / bg.width)
    bg.resize((1080, h), Image.LANCZOS).save(
        os.path.join(RES, "drawable-nodpi", "bg_app.jpg"), "JPEG", quality=88, optimize=True, progressive=True)
    print("bg_app.jpg", (1080, h))

    rep = build(icon)
    print("built:", rep)

    # 2) 预览
    print("\n===== 前景（圆形裁剪，xxxhdpi 缩到 56 列）=====")
    ascii_preview(Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_foreground.png")),
                  )
    print("\n===== 单色剪影 =====")
    mono = Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_monochrome.png"))
    tmp = Image.new("RGB", mono.size, (0, 0, 0))
    tmp.paste((255, 255, 255), mask=mono.getchannel("A"))
    ascii_preview(tmp)
    print("\n===== 背景层（模糊）=====")
    ascii_preview(Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_background.png")))
