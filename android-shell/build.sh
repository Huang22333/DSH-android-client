#!/usr/bin/env bash
# 构建 DSH 安卓壳 APK（不依赖 Gradle：aapt2 + javac + d8 + zipalign + apksigner）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$ANDROID_SDK_ROOT}"
JDK="${JAVA_HOME:-$JAVA_HOME}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"

MIN_SDK=24          # Android 7.0：某 Android 15 手机 与某低端机型(12.1) 都覆盖
TARGET_SDK=35
VERSION_CODE=23
VERSION_NAME="4.3"

KS="$ROOT/release.keystore"
KS_PASS="${KS_PASS:?请设置 KS_PASS 环境变量}"
KS_ALIAS="dsh"
OUT="$ROOT/dist/DSH-${VERSION_NAME}.apk"

export PATH="$JDK/bin:$PATH"
export JAVA_HOME="$JDK"

BUILD="$ROOT/build"
APP="$ROOT/app"
rm -rf "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$BUILD/base.apk" "$BUILD/aligned.apk"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$ROOT/dist" "$ROOT/keystore"

command -v java >/dev/null || { echo "缺 java"; exit 1; }
[ -f "$PLATFORM" ] || { echo "缺 $PLATFORM"; exit 1; }
[ -x "$BT/aapt2" ] || { echo "缺 build-tools"; exit 1; }

echo "==> 0/7 生成图标"
python3 "$ROOT/tools/gen_icons.py"

echo "==> 0/7 纯 Java 自测（颜色 / 地址 / 文件名）+ 结构自检"
mkdir -p "$BUILD/selftest"
javac -encoding UTF-8 -d "$BUILD/selftest" \
    "$APP/java/app/dsh/shell/Css.java" "$APP/java/app/dsh/shell/Urls.java" \
    "$APP/java/app/dsh/shell/Filenames.java" \
    "$APP/java/app/dsh/shell/ImageSizing.java" \
    "$APP/java/app/dsh/shell/B64.java" \
    "$ROOT/tools/selftest/TestCss.java" "$ROOT/tools/selftest/TestUrls.java" \
    "$ROOT/tools/selftest/TestFilenames.java" "$ROOT/tools/selftest/TestNav.java" \
    "$ROOT/tools/selftest/TestImageSizing.java" "$ROOT/tools/selftest/TestB64.java"
java -cp "$BUILD/selftest" app.dsh.shell.TestCss | tail -1
java -cp "$BUILD/selftest" app.dsh.shell.TestUrls | tail -1
java -cp "$BUILD/selftest" app.dsh.shell.TestFilenames | tail -1
java -cp "$BUILD/selftest" app.dsh.shell.TestNav | tail -1
java -cp "$BUILD/selftest" app.dsh.shell.TestImageSizing | tail -1
java -cp "$BUILD/selftest" app.dsh.shell.TestB64 | tail -1
python3 "$ROOT/tools/selftest/test_capture_provider.py" | tail -1
# 结构自检：方法直接自递归（脚本误改的典型产物 —— showCard 那次就是这样漏到真机上的，
# 编译器不管语义，只有这类静态检查拦得住）
python3 "$ROOT/tools/selftest/check_recursion.py" | tail -1

# provider 的 authority 必须与包名严格一致（相机应用靠它找到我们的文件；
# Shizuku 也靠 provider 找 Binder）。aapt2 **不会**展开 ${applicationId}
# （那是 Gradle 的占位符），所以这里必须写字面量，并由本脚本校验二者一致，
# 防止改包名时漏改。
PKG=$(sed -n 's/.*package="\([^"]*\)".*/\1/p' "$APP/AndroidManifest.xml" | head -1)
for SUF in fileprovider shizuku; do
    if ! grep -q "android:authorities=\"$PKG.$SUF\"" "$APP/AndroidManifest.xml"; then
        echo "provider authority 失配：manifest 里没有 android:authorities=\"$PKG.$SUF\"" >&2
        exit 1
    fi
done

echo "==> 1/7 编译资源"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res.zip" --no-crunch

echo "==> 2/7 链接资源 + 生成 R.java"
"$BT/aapt2" link \
    -o "$BUILD/base.apk" \
    -I "$PLATFORM" \
    --manifest "$APP/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD/res.zip"

echo "==> 3/7 编译 Java"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
# 第三方库（Shizuku API：Maven 的 aar 解出 classes.jar 放 libs/，不用 Gradle）
LIBJARS=$(find "$ROOT/libs" -name '*.jar' | sort | paste -sd: -)
# source/target 8 + android.jar 当 bootclasspath（JDK17 对 target>=9 禁止该选项），
# 编译期即锁定 Android API 面；再加 build-tools 自带的 core-lambda-stubs.jar，
# 否则 javac 找不到 java.lang.invoke.LambdaMetafactory（lambda 的脱糖在 d8 阶段做）。
javac -encoding UTF-8 -source 8 -target 8 -nowarn -Xlint:-options \
    -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" -classpath "$PLATFORM:$LIBJARS" \
    -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "==> 4/7 dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
find "$ROOT/libs" -name '*.jar' | sort >> "$BUILD/classes.txt"
"$BT/d8" --release --min-api "$MIN_SDK" --lib "$PLATFORM" \
    --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "==> 5/7 打包 + 对齐"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import sys, zipfile, shutil
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copyfile(base, out)
# classes.dex 以 STORED 写入（不压缩），避免个别 rom 直接 mmap 失败
with zipfile.ZipFile(out, 'a', zipfile.ZIP_STORED) as z:
    z.write(dex, 'classes.dex')
print('apk entries:', len(zipfile.ZipFile(out).namelist()))
PY
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "==> 6/7 签名"
# 从备份恢复密钥；两份都丢了才新建。绝不能因为 keystore 缺失就默默换新密钥 ——
# 签名变了老版本就覆盖安装不上（用户得先卸载），这曾真实发生过一次。
mkdir -p "$ROOT/backup"
if [ -f "$ROOT/backup/dsh.keystore.bak" ] && [ ! -f "$KS" ]; then
    cp "$ROOT/backup/dsh.keystore.bak" "$KS"
    chmod 600 "$KS"
    echo "（已从 backup/ 恢复签名密钥）"
fi
if [ ! -f "$KS" ]; then
    echo "⚠️  没有可用签名密钥，正在新建。注意：新密钥无法覆盖安装此前用别的密钥签的版本。" >&2
    keytool -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=DSH-Shell, O=xybcm.cn, C=CN" >/dev/null 2>&1
    chmod 600 "$KS"
fi
# 每次构建都同步一份备份
cp "$KS" "$ROOT/backup/dsh.keystore.bak"
chmod 600 "$ROOT/backup/dsh.keystore.bak"
"$BT/apksigner" sign \
    --ks "$KS" --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT" "$BUILD/aligned.apk"

# 版本号必须真的落进包里：manifest 里的同名属性会压过 aapt2 命令行参数（实测），
# 曾出现「build.sh 写 1.1、装出来还是 1.0」的静默失配，所以这里硬校验一次。
# ⚠️ 别写成 `aapt2 ... | head -1`：head 读完即退出，aapt2 收到 SIGPIPE，
#    在 set -e -o pipefail 下整个构建会随机以 141 中断（实测）。先落文件再解析。
BADGING="$BUILD/badging.txt"
"$BT/aapt2" dump badging "$OUT" > "$BADGING" 2>/dev/null || true
BUILT_CODE=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" "$BADGING" | head -1)
BUILT_NAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" "$BADGING" | head -1)
if [ "$BUILT_CODE" != "$VERSION_CODE" ] || [ "$BUILT_NAME" != "$VERSION_NAME" ]; then
    echo "版本号失配：期望 $VERSION_CODE/$VERSION_NAME，包里是 $BUILT_CODE/$BUILT_NAME" >&2
    exit 1
fi
echo "版本校验通过：versionCode=$BUILT_CODE versionName=$BUILT_NAME"

# 签名指纹必须与上次构建一致，否则老用户无法覆盖安装。
FP_FILE="$ROOT/backup/signer-sha256.txt"
FP=$("$BT/apksigner" verify --print-certs "$OUT" 2>/dev/null \
    | sed -n 's/.*SHA-256 digest: //p' | head -1)
if [ -f "$FP_FILE" ]; then
    PREV=$(cat "$FP_FILE")
    if [ "$FP" != "$PREV" ]; then
        echo "签名指纹变了！旧版将无法覆盖安装（需先卸载）。" >&2
        echo "  上次: $PREV" >&2
        echo "  本次: $FP" >&2
        exit 1
    fi
    echo "签名指纹一致（可覆盖安装）：$FP"
else
    echo "$FP" > "$FP_FILE"
    echo "已记录签名指纹：$FP"
fi

"$BT/apksigner" verify --print-certs "$OUT" | head -3
ls -la "$OUT"
echo "OK -> $OUT"
