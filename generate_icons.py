import os
from PIL import Image, ImageDraw

source_image_path = r"C:\Users\mason\.gemini\antigravity\brain\6f37fa7b-b852-4f51-86ab-355d5b186e80\bookreader_app_icon_1792348858048.jpg"
res_dir = r"d:\11\bookreader\app\src\main\res"

sizes = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

img = Image.open(source_image_path).convert("RGBA")

# 1. 复制高清大图到 app 根目录
app_dir = r"d:\11\bookreader\app\src\main"
img_512 = img.resize((512, 512), Image.Resampling.LANCZOS)
img_512.save(os.path.join(app_dir, "ic_launcher-web.png"), "PNG")

# 2. 为各密度生成方形与圆形图标
for folder, size in sizes.items():
    target_folder = os.path.join(res_dir, folder)
    os.makedirs(target_folder, exist_ok=True)
    
    # 方形/圆角方形
    resized = img.resize((size, size), Image.Resampling.LANCZOS)
    resized.save(os.path.join(target_folder, "ic_launcher.png"), "PNG")
    
    # 圆形裁切
    mask = Image.new('L', (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse((0, 0, size, size), fill=255)
    
    round_img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
    round_img.paste(resized, (0, 0), mask=mask)
    round_img.save(os.path.join(target_folder, "ic_launcher_round.png"), "PNG")

print("All Android app icons generated successfully!")
