set -e

# PATH must include the executables needed for this script, i.e. `rm`, `cp`, and `ln`
# These are borrowed from the debian:bullseye mounted image
export PATH=/bullseye/usr/bin:/bullseye/bin:/bullseye/sbin

ARCH=$(uname -m)

# LD_LIBRARY_PATH must lead to system libs that get dynamically linked by the executables used in this script
# These are borrowed from the debian:bullseye mounted image
export LD_LIBRARY_PATH=/bullseye/lib/$ARCH-linux-gnu:/bullseye/usr/lib/$ARCH-linux-gnu/:/bullseye/lib64

case $ARCH in
  x86_64)
    OPENJDK_SUFFIX=amd64
    ;;
  aarch64)
    OPENJDK_SUFFIX=arm64
    ;;
  *)
    echo Unknown target arch $1
    exit 1
esac

# Remove libs and executables from the base image we don't want
rm /usr/lib/${ARCH}-linux-gnu/libssl.so*
rm -rf /usr/bin

# Install system libraries required for java
cp /java/lib/${ARCH}-linux-gnu/libz.so* /java/lib/${ARCH}-linux-gnu/libgcc_s.so* /lib/${ARCH}-linux-gnu/
cp /java/usr/lib/${ARCH}-linux-gnu/libstdc++.so* /usr/lib/${ARCH}-linux-gnu/
cp -r /java/usr/lib/locale /usr/lib/

# Install java
cp -r /java/etc/java-11-openjdk /etc/
cp -r /java/etc/ssl/certs/java /etc/ssl/certs/
cp -r /java/usr/lib/jvm /usr/lib/

# Link it to a architecture-agnostic path
ln -s /usr/lib/jvm/java-11-openjdk-$OPENJDK_SUFFIX /usr/lib/jvm/java-11-openjdk
