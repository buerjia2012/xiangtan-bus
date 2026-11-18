import sys, zipfile, shutil, os

base_apk, dex_file, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base_apk, out_apk)
with zipfile.ZipFile(out_apk, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(dex_file, 'classes.dex')
print('added classes.dex ->', out_apk, os.path.getsize(out_apk), 'bytes')
