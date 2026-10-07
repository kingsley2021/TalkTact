# -*- coding: utf-8 -*-
"""从 art/talktact-icon-512.png 生成自适应图标四件套（mdpi~xxxhdpi）。

这张新图标与上一版素材的性质**完全不同**，所以要按它的实际情况来生成：

- 旧素材是一张照片（`IMG_20261005_142341.jpg`，无透明通道），所以当时要「把很暗的像素抠成剪影」、
  背景要「整张铺满 + 模糊 + 压暗」。
- 新图标是**已经合成好的成品**：紫色渐变底 + 白色气泡（内含金星与两条正文线）+ 金色四角星，
  **自带 squircle 圆角（四角透明，约 4%）**，边缘内侧还有一圈很淡的高光框线。

于是三条层的做法跟着变：

| 层 | 做法 | 为什么 |
| :-- | :-- | :-- |
| background | 先铺图标自己的边缘紫，再把**整张图标 cover 到画布并轻微模糊** | 角落那圈透明区就会看到同色系的紫，不会出现「紫图标贴在一块异色底上」的接缝 |
| foreground | **满幅**（整张图标铺满 108dp），保留自带透明角 | 见下方「满幅出血」那条 |
| monochrome | 取**亮部**（白色气泡 + 亮星）做白色剪影 | 主题图标（Android 13+）只有一个颜色，剪影要能认出是这个图标 |

⚠️ **满幅出血，别内缩**：自适应图标的「可见区 72dp vs 画布 108dp」是给**稿子留安全区**用的，
真实裁剪交给启动器遮罩。把成品图自己缩到 66.7% 会变成「图标里套图标」——
因为这张图**自带 squircle 圆角与内框线**，内缩之后那圈圆角就浮在紫色底上了（试过，很难看）。
铺满之后：内容只占画布 56%，稳稳在安全区内；自带的内框线在半径 94% 处，
圆形遮罩（半径 50%）不会碰到它；四角的透明区本来就在遮罩之外，看不到。

顺带：**不再生成 `drawable-nodpi/bg_app.jpg`** —— 0.8.11 起内置背景改成程序化渐变，那张图已经删了。
"""
import os
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
RES = os.path.join(ROOT, "app/src/main/res")

ICON_SRC = os.path.join(ART, "talktact-icon-512.png")

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}
SAFE_RATIO = 1.0            # 满幅出血：可见区留白交给启动器遮罩去裁，**不要自己内缩**
BG_BLUR_RATIO = 0.0         # 背景层不模糊：它和前景是同一张图，不糊才对得齐（只在四角露出来）
MONO_LO, MONO_HI = 172, 234  # 亮部阈值：紫底亮度约 106~150，白气泡约 240


def load_icon():
    im = Image.open(ICON_SRC)
    return im.convert("RGBA")


def edge_purple(icon):
    """取图标「边缘那一圈」的平均色 —— 拿来垫在背景层最下面，防止 cover 时露透明。"""
    w, h = icon.size
    px = icon.load()
    pts = []
    for i in range(12, w - 12, 4):          # 沿中线取，避开四角的透明区
        for (x, y) in ((i, 24), (i, h - 25), (24, i), (w - 25, i)):
            r, g, b, a = px[x, y]
            if a > 240:
                pts.append((r, g, b))
    if not pts:
        return (124, 58, 237)
    n = len(pts)
    return tuple(round(sum(p[i] for p in pts) / n) for i in range(3))


def cover(im, w, h):
    """等价于 ContentScale.Crop：铺满 w×h 后居中裁切。"""
    s = max(w / im.width, h / im.height)
    nw, nh = max(1, round(im.width * s)), max(1, round(im.height * s))
    r = im.resize((nw, nh), Image.LANCZOS)
    return r.crop(((nw - w) // 2, (nh - h) // 2, (nw - w) // 2 + w, (nh - h) // 2 + h))


def circle_mask(size, ss=4):
    """抗锯齿的圆形遮罩（老版 API 那份 legacy 图标用）。"""
    big = Image.new("L", (size * ss, size * ss), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * ss - 1, size * ss - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def mono_from(icon, size, flat):
    """白色剪影：亮部不透明、暗部透明，边缘做一点羽化。"""
    small = icon.convert("RGB").resize((size, size), Image.LANCZOS)
    lum = list(small.convert("L").getdata())
    src_a = list(icon.getchannel("A").resize((size, size), Image.LANCZOS).getdata())
    span = max(1, MONO_HI - MONO_LO)
    alpha = Image.new("L", (size, size), 0)
    alpha.putdata([
        int(max(0.0, min(1.0, (v - MONO_LO) / span)) * (a / 255.0) * 255)
        for v, a in zip(lum, src_a)
    ])
    alpha = alpha.filter(ImageFilter.GaussianBlur(size * 0.006))
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    out.putalpha(alpha)
    return out


def build(icon, flat):
    report = {}
    for name, d in DENSITIES.items():
        canvas = round(108 * d)
        visible = round(canvas * SAFE_RATIO)

        # 背景：整张铺满（先垫同色紫，免得透明角把底色露出来）→ 轻微模糊
        base = Image.new("RGBA", icon.size, flat + (255,))
        base.alpha_composite(icon)
        bg = cover(base, canvas, canvas).filter(ImageFilter.GaussianBlur(canvas * BG_BLUR_RATIO)).convert("RGB")

        # 前景：整张图标缩到可见区，保留自带透明角
        fg = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(icon.resize((visible, visible), Image.LANCZOS),
                           ((canvas - visible) // 2, (canvas - visible) // 2))

        # 单色层
        mono = mono_from(icon, canvas, flat)

        # legacy（API < 26 用不到，minSdk 31；留着是为了图标在任何地方都不缺）：同色底 + 圆形裁切的图标
        legacy_size = round(48 * d)
        legacy = Image.new("RGBA", (legacy_size, legacy_size), flat + (255,))
        art = icon.resize((legacy_size, legacy_size), Image.LANCZOS)
        legacy.alpha_composite(art)

        outdir = os.path.join(RES, "mipmap-" + name)
        bg.save(os.path.join(outdir, "ic_launcher_background.png"), "PNG", optimize=True)
        fg.save(os.path.join(outdir, "ic_launcher_foreground.png"), "PNG", optimize=True)
        mono.save(os.path.join(outdir, "ic_launcher_monochrome.png"), "PNG", optimize=True)
        legacy.convert("RGB").save(os.path.join(outdir, "ic_launcher.png"), "PNG", optimize=True)
        report[name] = (canvas, visible, sum(1 for v in mono.getchannel("A").getdata() if v > 128))
    return report


def ascii_preview(im, w=52):
    im = im.convert("L").resize((w, max(1, int(w * im.height / im.width))), Image.LANCZOS)
    chars = " .:-=+*#%@"
    for y in range(im.height):
        print("".join(chars[min(9, im.getpixel((x, y)) * 10 // 256)] for x in range(im.width)))


if __name__ == "__main__":
    icon = load_icon()
    flat = edge_purple(icon)
    print("icon src", icon.size, "边缘紫 =", flat)
    rep = build(icon, flat)
    print("built:", rep)

    print("\n===== 前景（可见区，xxxhdpi 缩到 52 列）=====")
    fg = Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_foreground.png"))
    tmp = Image.new("RGB", fg.size, flat)
    tmp.paste(fg, (0, 0), fg)
    ascii_preview(tmp)

    print("\n===== 单色剪影 =====")
    mono = Image.open(os.path.join(RES, "mipmap-xxxhdpi/ic_launcher_monochrome.png"))
    tmp = Image.new("RGB", mono.size, (0, 0, 0))
    tmp.paste((255, 255, 255), mask=mono.getchannel("A"))
    ascii_preview(tmp)
