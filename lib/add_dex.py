import sys, zipfile

# Usage: add_dex.py <base.apk> <classes.dex> <out.apk>
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]

with zipfile.ZipFile(base, 'r') as zin, \
     zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == 'classes.dex':
            continue  # avoid duplicate
        zout.writestr(item, zin.read(item.filename))
    zout.write(dex, 'classes.dex')

print("added classes.dex ->", out)
